package com.cendodrive.file;

import com.cendodrive.file.FileDtos.*;
import com.cendodrive.user.User;
import com.cendodrive.user.UserRepository;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only: FILE_TEST_DB_* must point to a dedicated MySQL 8.0.16+ test database. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_FILE_INTEGRATION_TESTS", matches = "true")
class FileIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnv("FILE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> requiredEnv("FILE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> requiredEnv("FILE_TEST_DB_PASSWORD"));
    }
    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing dedicated test database setting: " + name);
        return value;
    }
    @Autowired FileService files;
    @Autowired FileMetadataService metadata;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    long owner;
    long other;
    @BeforeEach void createUsers() {
        owner = users.saveAndFlush(new User("file_it_" + UUID.randomUUID().toString().replace("-", ""), "unused", "test")).getId();
        other = users.saveAndFlush(new User("file_it_" + UUID.randomUUID().toString().replace("-", ""), "unused", "test")).getId();
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM file_entries WHERE user_id IN (?, ?)", owner, other);
        if (owner != 0) users.deleteById(owner);
        if (other != 0) users.deleteById(other);
    }
    private long id(FileEntryResponse entry) { return Long.parseLong(entry.id()); }
    private RegisterStoredFileCommand command(long parent, String name, long size, String ingest) {
        return new RegisterStoredFileCommand(parent, name, size, "FASTDFS", "test/" + ingest, "text/plain", null, ingest);
    }
    @Test void directoryAndFileLifecycleIsIsolated() {
        long a = id(files.createFolder(owner, 0, "A"));
        long b = id(files.createFolder(owner, 0, "B"));
        var file = metadata.registerStoredFile(owner, command(a, "note.txt", 10, "task1"));
        files.rename(owner, id(file), "renamed.txt");
        files.move(owner, id(file), b);
        assertEquals(0, files.list(owner, a, 0, 50).totalElements());
        assertEquals("renamed.txt", files.list(owner, b, 0, 50).items().getFirst().name());
        assertEquals(0, files.list(other, 0, 0, 50).totalElements());
        assertEquals("FILE_NOT_FOUND", assertThrows(FileBusinessException.class, () -> files.list(other, a, 0, 50)).code());
        assertThrows(FileBusinessException.class, () -> files.rename(other, id(file), "stolen"));
        long foreign = id(files.createFolder(other, 0, "foreign"));
        assertThrows(FileBusinessException.class, () -> files.move(owner, id(file), foreign));
        assertThrows(FileBusinessException.class, () -> files.createFolder(owner, foreign, "intruder"));
        var retry = metadata.registerStoredFile(owner, command(a, "note.txt", 10, "task1"));
        assertEquals(file.id(), retry.id());
        assertEquals("renamed.txt", retry.name());
        assertEquals(10, users.findById(owner).orElseThrow().getStorageUsed());
    }
    @Test void databaseConstraintProtectsRootNames() {
        files.createFolder(owner, 0, "Docs");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO file_entries(user_id,parent_id,name,name_key,entry_type,size_bytes,created_at,updated_at) "
                        + "VALUES (?,0,'DOCS','docs','FOLDER',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", owner));
        files.createFolder(other, 0, "Docs");
    }
    @Test void listingPaginatesFoldersBeforeFiles() {
        metadata.registerStoredFile(owner, command(0, "first.txt", 1, "first"));
        var folder = files.createFolder(owner, 0, "folder");
        var first = files.list(owner, 0, 0, 1);
        assertEquals(2, first.totalElements());
        assertEquals(folder.id(), first.items().getFirst().id());
        assertEquals(FileEntry.EntryType.FILE, files.list(owner, 0, 1, 1).items().getFirst().entryType());
    }
    @Test void outerTransactionRollsBackEntryAndQuotaTogether() {
        var transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            metadata.registerStoredFile(owner, command(0, "rollback.txt", 10, "rollback"));
            throw new IllegalStateException("Force rollback after metadata flush");
        }));
        assertEquals(0, files.list(owner, 0, 0, 50).totalElements());
        assertEquals(0, users.findById(owner).orElseThrow().getStorageUsed());
    }
    @Test void concurrentSameNameCreatesOnlyOneFolder() throws Exception {
        assertOneSuccess(() -> files.createFolder(owner, 0, "Docs"),
                () -> files.createFolder(owner, 0, "DOCS"), "NAME_CONFLICT");
        assertEquals(1, files.list(owner, 0, 0, 50).totalElements());
    }
    @Test void concurrentMovesCannotCreateCycle() throws Exception {
        long a = id(files.createFolder(owner, 0, "A"));
        long b = id(files.createFolder(owner, 0, "B"));
        assertOneSuccess(() -> files.move(owner, a, b), () -> files.move(owner, b, a), "INVALID_MOVE");
        assertEquals(1, files.list(owner, 0, 0, 50).totalElements());
    }
    @Test void concurrentUploadsCannotExceedQuota() throws Exception {
        long bytes = 700L * 1024 * 1024;
        assertOneSuccess(() -> metadata.registerStoredFile(owner, command(0, "a.txt", bytes, "a")),
                () -> metadata.registerStoredFile(owner, command(0, "b.txt", bytes, "b")), "STORAGE_QUOTA_EXCEEDED");
        assertEquals(bytes, users.findById(owner).orElseThrow().getStorageUsed());
        assertEquals(1, files.list(owner, 0, 0, 50).totalElements());
    }
    @Test void concurrentRetriesRegisterOnce() throws Exception {
        var c = command(0, "a.txt", 10, "same");
        var results = concurrently(() -> metadata.registerStoredFile(owner, c).id(),
                () -> metadata.registerStoredFile(owner, c).id());
        assertEquals(results.get(0), results.get(1));
        assertEquals(10, users.findById(owner).orElseThrow().getStorageUsed());
        assertEquals(1, files.list(owner, 0, 0, 50).totalElements());
    }
    private void assertOneSuccess(Runnable a, Runnable b, String expectedCode) throws Exception {
        var results = concurrently(() -> outcome(a), () -> outcome(b));
        assertEquals(1, results.stream().filter("OK"::equals).count());
        assertEquals(1, results.stream().filter(expectedCode::equals).count());
    }
    private String outcome(Runnable action) {
        try { action.run(); return "OK"; }
        catch (FileBusinessException ex) { return ex.code(); }
    }
    private java.util.List<String> concurrently(Callable<String> a, Callable<String> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException(); return a.call(); });
            Future<String> second = pool.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException(); return b.call(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return java.util.List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            pool.shutdownNow();
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test workers did not terminate");
        }
    }
}
