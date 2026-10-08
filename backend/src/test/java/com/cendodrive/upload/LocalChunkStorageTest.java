package com.cendodrive.upload;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalChunkStorageTest {
    private static final String UPLOAD_ID = "b".repeat(64);

    @TempDir Path root;
    LocalChunkStorage storage;

    @BeforeEach void setup() {
        storage = new LocalChunkStorage(root.toString());
    }

    @Test void readsBackChunksInIndexOrder() throws Exception {
        storage.save(UPLOAD_ID, 0, stream("hello"), hashOf("hello"));
        storage.save(UPLOAD_ID, 1, stream("world"), hashOf("world"));

        assertEquals("helloworld", read(storage.openAll(UPLOAD_ID, 2)));
    }

    @Test void keepsThePreviousChunkWhenAnOverwriteFailsChecksum() throws Exception {
        storage.save(UPLOAD_ID, 0, stream("good"), hashOf("good"));

        assertThrows(ChunkChecksumException.class,
                () -> storage.save(UPLOAD_ID, 0, stream("bad"), hashOf("good")));

        assertEquals("good", read(storage.openAll(UPLOAD_ID, 1)));
    }

    @Test void leavesNoPartialFileBehindWhenChecksumFails() throws Exception {
        assertThrows(ChunkChecksumException.class,
                () -> storage.save(UPLOAD_ID, 0, stream("bad"), hashOf("good")));

        try (Stream<Path> entries = Files.walk(root)) {
            assertTrue(entries.filter(Files::isRegularFile).findAny().isEmpty());
        }
    }

    @Test void reportsWhichChunkIndexIsMissing() throws Exception {
        storage.save(UPLOAD_ID, 0, stream("a"), hashOf("a"));
        storage.save(UPLOAD_ID, 2, stream("c"), hashOf("c"));

        MissingChunkException error = assertThrows(MissingChunkException.class,
                () -> storage.openAll(UPLOAD_ID, 3));

        assertEquals(1, error.index());
    }

    @Test void rejectsUploadIdsThatCouldEscapeTheChunkRoot() {
        assertThrows(IllegalArgumentException.class,
                () -> storage.save("../outside", 0, stream("x"), hashOf("x")));
        assertThrows(IllegalArgumentException.class,
                () -> storage.save(UPLOAD_ID.toUpperCase(Locale.ROOT), 0, stream("x"), hashOf("x")));
        assertThrows(IllegalArgumentException.class,
                () -> storage.save(UPLOAD_ID, -1, stream("x"), hashOf("x")));
    }

    @Test void cleansUpTheWholeSessionDirectory() throws Exception {
        storage.save(UPLOAD_ID, 0, stream("a"), hashOf("a"));

        storage.cleanup(UPLOAD_ID);

        assertFalse(Files.exists(root.resolve(UPLOAD_ID)));
    }

    @Test void listsOnlyWellFormedSessionDirectories() throws Exception {
        storage.save(UPLOAD_ID, 0, stream("a"), hashOf("a"));
        Files.createDirectories(root.resolve("not-a-session"));

        List<String> sessions = storage.listSessions().stream().map(ChunkStorage.Session::uploadId).toList();

        assertEquals(List.of(UPLOAD_ID), sessions);
    }

    @Test void concurrentSavesOfTheSameIndexNeverInterleaveContent() throws Exception {
        String first = "A".repeat(4096);
        String second = "B".repeat(4096);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                Future<?> a = pool.submit(() -> save(first));
                Future<?> b = pool.submit(() -> save(second));
                a.get();
                b.get();
                String actual = read(storage.openAll(UPLOAD_ID, 1));
                assertTrue(actual.equals(first) || actual.equals(second),
                        "chunk content must come from exactly one writer, got " + actual.length() + " chars");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void save(String value) {
        try {
            storage.save(UPLOAD_ID, 0, stream(value), hashOf(value));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static InputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InputStream input) throws Exception {
        try (InputStream content = input) {
            return new String(content.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String hashOf(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
