package com.cendodrive.ai.claw;

import com.cendodrive.drive.DriveFile;
import com.cendodrive.drive.DriveFileRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in regression against an isolated, disposable MySQL database, never the application's database. */
@EnabledIfEnvironmentVariable(named="CENDO_CLAW_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/claw_qa(?:\\?.*)?")
class ClawMysqlConcurrencyTest {
  JdbcTemplate jdbc;
  TransactionTemplate tx;
  ClawIndexQueue queue;
  DriveFile file;

  @BeforeEach void setup() {
    var ds=new DriverManagerDataSource(System.getenv("CENDO_CLAW_MYSQL_URL"),"root","claw_qa_only");
    jdbc=new JdbcTemplate(ds);
    jdbc.execute("DROP TABLE IF EXISTS claw_tasks");
    jdbc.execute("DROP TABLE IF EXISTS claw_documents");
    jdbc.execute("DROP TABLE IF EXISTS drive_files");
    jdbc.execute("CREATE TABLE drive_files(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V14__create_claw_documents_and_tasks.sql")).execute(ds);
    jdbc.update("INSERT INTO drive_files(id) VALUES(12)");
    tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(20);
    var settings=new ClawSettings(true,"https://model.test/v1","test-model","test-key",2,"http://vector.test","","claw_qa");
    var access=mock(ClawFileAccess.class);var repository=mock(DriveFileRepository.class);
    queue=new ClawIndexQueue(jdbc,settings,repository,access);
    when(repository.findAllByOwnerId(7L)).thenAnswer(call->List.of(file));
    file=mock(DriveFile.class);
    when(file.getId()).thenReturn(12L);when(file.getOwnerId()).thenReturn(7L);
    when(file.getName()).thenReturn("预算.txt");when(file.getSize()).thenReturn(12L);
    when(access.visible(7L,12L)).thenReturn(file);
  }

  @Test void reindexAfterOldSnapshotAllocatesNextCommittedGeneration() throws Exception { race(false); }
  @Test void firstIndexAfterEmptySnapshotDoesNotInsertDuplicateDocument() throws Exception { race(true); }
  @Test void metadataChangeAfterEmptySnapshotFencesConcurrentFirstIndex() throws Exception { race(true,true); }

  @Test void concurrentFirstIndexesForDifferentOwnersDoNotDeadlock() throws Exception {
    jdbc.update("INSERT INTO drive_files(id) VALUES(13)");
    var other=mock(DriveFile.class);when(other.getId()).thenReturn(13L);when(other.getOwnerId()).thenReturn(8L);
    when(other.getName()).thenReturn("第二份.txt");when(other.getSize()).thenReturn(12L);
    var missingDocuments=new CyclicBarrier(2);var emptyTasks=new CyclicBarrier(2);
    var sawMissingDocument=new java.util.concurrent.atomic.AtomicBoolean();
    // Coordinate database boundary reads, not production hooks. Existing-key reads never pause.
    var coordinated=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
      @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
        var result=super.query(sql,mapper,args);
        if(sql.equals("SELECT * FROM claw_documents WHERE file_id=? FOR UPDATE") && result.isEmpty()) {
          sawMissingDocument.set(true);awaitBoundary(missingDocuments);
        }
        return result;
      }
      @Override public int update(String sql,Object... args) {
        int count=super.update(sql,args);
        if(sql.startsWith("DELETE FROM claw_tasks WHERE file_id=") && !sawMissingDocument.get()
            && super.queryForObject("SELECT COUNT(*) FROM claw_tasks WHERE file_id=?",Integer.class,args[0])==0)
          awaitBoundary(emptyTasks);
        return count;
      }
    };
    var settings=new ClawSettings(true,"https://model.test/v1","test-model","test-key",2,"http://vector.test","","claw_qa");
    var queued=new ClawIndexQueue(coordinated,settings,mock(DriveFileRepository.class),mock(ClawFileAccess.class));
    var pool=Executors.newFixedThreadPool(2);
    try {
      var first=pool.submit(()->tx.executeWithoutResult(status->queued.enqueue(file)));
      var second=pool.submit(()->tx.executeWithoutResult(status->queued.enqueue(other)));
      assertDoesNotThrow(()->first.get(10,TimeUnit.SECONDS),"Independent first indexes must both commit");
      assertDoesNotThrow(()->second.get(10,TimeUnit.SECONDS),"Independent first indexes must both commit");
      assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM claw_documents WHERE generation=1",Integer.class));
      assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM claw_tasks WHERE operation='UPSERT' AND status='PENDING'",Integer.class));
    } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
  }
  private static void awaitBoundary(CyclicBarrier barrier) {
    try {barrier.await(5,TimeUnit.SECONDS);}
    catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
    catch(BrokenBarrierException | TimeoutException ex) {throw new IllegalStateException("Isolated MySQL boundary coordination failed",ex);}
  }
  @Test void manualReindexWaitsForMetadataOwnerFence() throws Exception {
    jdbc.execute("DROP TABLE IF EXISTS claw_qa_owner_fence");
    jdbc.execute("CREATE TABLE claw_qa_owner_fence(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
    jdbc.update("INSERT INTO claw_qa_owner_fence(id) VALUES(7)");
    assertManualReindexWaits(false);assertManualReindexWaits(true);
  }
  private void assertManualReindexWaits(boolean all) throws Exception {
    tx.executeWithoutResult(status->queue.enqueue(file));
    long before=jdbc.queryForObject("SELECT generation FROM claw_documents WHERE file_id=12",Long.class);
    var access=mock(ClawFileAccess.class);
    var info=new ClawFileAccess.FileInfo(12L,null,"预算.txt","file",false,false);
    when(access.snapshot(7L)).thenReturn(new ClawFileAccess.Snapshot(Map.of(12L,info),Map.of(12L,info)));
    when(access.visible(7L,12L)).thenReturn(file);when(access.visibleFiles(7L)).thenReturn(Map.of(12L,file));
    var owners=mock(com.cendodrive.user.UserRepository.class);var owner=mock(com.cendodrive.user.User.class);
    when(owner.isActive()).thenReturn(true);
    when(owners.lock(7L)).thenAnswer(call->{
      jdbc.queryForObject("SELECT id FROM claw_qa_owner_fence WHERE id=? FOR UPDATE",Long.class,7L);
      return Optional.of(owner);
    });
    var settings=new ClawSettings(true,"https://model.test/v1","test-model","test-key",2,"http://vector.test","","claw_qa");
    var search=new ClawSearchService(settings,access,queue,mock(ClawEmbeddingClient.class),mock(ClawVectorStore.class),mock(com.cendodrive.ai.AiChatService.class),new com.fasterxml.jackson.databind.ObjectMapper(),owners);
    var started=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
    var work=new java.util.concurrent.atomic.AtomicReference<Future<ClawDtos.ReindexResult>>();
    try {
      tx.executeWithoutResult(status->{
        jdbc.queryForObject("SELECT id FROM claw_qa_owner_fence WHERE id=? FOR UPDATE",Long.class,7L);
        work.set(pool.submit(()->tx.execute(inner->{
          started.countDown();
          return all?search.reindexAll(7L,new ClawDtos.ReindexRequest(true)):search.reindexFile(7L,12L,new ClawDtos.ReindexRequest(true));
        })));
        try {assertTrue(started.await(5,TimeUnit.SECONDS));}
        catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
        assertThrows(TimeoutException.class,()->work.get().get(500,TimeUnit.MILLISECONDS),"Manual reindex must wait behind the existing metadata owner fence");
        verify(access,never()).snapshot(7L);verify(access,never()).visibleFiles(7L);
        queue.changed(file);
      });
      assertEquals(1,work.get().get(5,TimeUnit.SECONDS).queued());
      verify(owners).lock(7L);
      assertEquals(before+2,jdbc.queryForObject("SELECT generation FROM claw_documents WHERE file_id=12",Long.class));
    } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
  }
  @Test void claimRechecksEligibilityAfterWaitingForFileFence() throws Exception {
    tx.executeWithoutResult(status->queue.enqueue(file));
    assertNull(fenced(queue::claim));
    assertEquals(2,jdbc.queryForObject("SELECT generation FROM claw_documents WHERE file_id=12",Long.class));
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM claw_tasks WHERE file_id=12 AND generation=2 AND operation='UPSERT' AND status='PENDING'",Integer.class));
  }
  @Test void publicationDoesNotCrossConcurrentMetadataFence() throws Exception {
    tx.executeWithoutResult(status->queue.enqueue(file));var task=tx.execute(status->queue.claim());
    assertNotNull(task);assertFalse(fenced(()->queue.publish(task,2)));
    assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM claw_documents WHERE file_id=12",String.class));
  }
  @Test void failureDoesNotCrossConcurrentMetadataFence() throws Exception {
    tx.executeWithoutResult(status->queue.enqueue(file));var task=tx.execute(status->queue.claim());
    assertNotNull(task);fenced(()->{queue.failed(task,"CLAW_EMBEDDING_UNAVAILABLE",false);return true;});
    assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM claw_documents WHERE file_id=12",String.class));
    assertEquals(2,jdbc.queryForObject("SELECT generation FROM claw_documents WHERE file_id=12",Long.class));
  }
  private <T> T fenced(java.util.function.Supplier<T> work) throws Exception {
    var pool=Executors.newSingleThreadExecutor();var future=new java.util.concurrent.atomic.AtomicReference<Future<T>>();
    try {
      tx.executeWithoutResult(status->{
        jdbc.queryForList("SELECT id FROM drive_files WHERE id=12 FOR UPDATE",Long.class);
        var started=new CountDownLatch(1);
        future.set(pool.submit(()->tx.execute(worker->{started.countDown();return work.get();})));
        assertDoesNotThrow(()->assertTrue(started.await(5,TimeUnit.SECONDS)));
        assertThrows(TimeoutException.class,()->future.get().get(500,TimeUnit.MILLISECONDS),"Document mutators must wait for the metadata fence before taking task locks");
        queue.enqueue(file);
      });
      return future.get().get(10,TimeUnit.SECONDS);
    } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
  }
  private void race(boolean initiallyAbsent) throws Exception { race(initiallyAbsent,false); }
  private void race(boolean initiallyAbsent,boolean metadataChange) throws Exception {
    if(!initiallyAbsent) tx.executeWithoutResult(status->queue.enqueue(file));
    var snapshotReady=new CountDownLatch(1);var otherCommitted=new CountDownLatch(1);
    var pool=Executors.newSingleThreadExecutor();
    try {
      Future<Long> delayed=pool.submit(()->tx.execute(status->{
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
        var prior=jdbc.queryForList("SELECT generation FROM claw_documents WHERE file_id=12",Long.class);
        assertEquals(initiallyAbsent?0:1,prior.size());
        snapshotReady.countDown();
        try {assertTrue(otherCommitted.await(10,TimeUnit.SECONDS));}
        catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
        // The metadata-change entry point must also bypass the earlier nonlocking snapshot.
        if(metadataChange) queue.changed(file);else queue.enqueue(file);
        return jdbc.queryForObject("SELECT generation FROM claw_documents WHERE file_id=12",Long.class);
      }));
      assertTrue(snapshotReady.await(10,TimeUnit.SECONDS));
      tx.executeWithoutResult(status->queue.enqueue(file));
      otherCommitted.countDown();
      long expected=initiallyAbsent?2:3;
      assertEquals(expected,delayed.get(10,TimeUnit.SECONDS));
      assertEquals(expected,jdbc.queryForObject("SELECT generation FROM claw_documents WHERE file_id=12",Long.class));
    } finally {
      otherCommitted.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));
    }
  }
}
