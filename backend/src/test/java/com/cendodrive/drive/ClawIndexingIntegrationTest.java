package com.cendodrive.drive;

import com.cendodrive.ai.claw.*;
import com.cendodrive.storage.FileStorage;
import com.cendodrive.upload.UploadSessionRepository;
import com.cendodrive.user.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:clawindex;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "cendo.ai.claw.enabled=true","cendo.ai.claw.worker-enabled=false","cendo.ai.claw.embedding.base-url=https://model.test/v1",
    "cendo.ai.claw.embedding.key=test-key","cendo.ai.claw.embedding.dimensions=2","cendo.ai.claw.vector.base-url=http://vector.test"})
class ClawIndexingIntegrationTest {
  @Autowired DriveService drive;@Autowired FileFeatureService features;@Autowired DriveFileRepository files;
  @Autowired UserRepository users;@Autowired UploadSessionRepository sessions;@Autowired JdbcTemplate jdbc;
  @Autowired ClawIndexQueue queue;@Autowired ClawIndexWorker worker;@org.springframework.boot.test.mock.mockito.SpyBean ClawFileAccess access;@Autowired TransactionTemplate tx;
  @MockBean FileStorage storage;@MockBean ClawEmbeddingClient embeddings;@MockBean ClawVectorStore vectors;
  User owner,other;Map<String,byte[]> blobs=new HashMap<>();
  @BeforeEach void setup() throws Exception {
    jdbc.update("DELETE FROM claw_tasks");jdbc.update("DELETE FROM claw_documents");sessions.deleteAllInBatch();files.deleteAllInBatch();users.deleteAllInBatch();
    owner=users.saveAndFlush(new User("clawowner","hash","测试一"));other=users.saveAndFlush(new User("clawother","hash","测试二"));
    when(storage.upload(any(),anyLong(),anyString())).thenAnswer(call->{String key=UUID.randomUUID().toString();blobs.put(key,((InputStream)call.getArgument(0)).readAllBytes());return key;});
    doAnswer(call->{((OutputStream)call.getArgument(1)).write(blobs.get(call.getArgument(0)));return null;}).when(storage).download(anyString(),any());
    when(embeddings.embed(anyList())).thenAnswer(call->((List<?>)call.getArgument(0)).stream().map(x->List.of(1.0,0.0)).toList());
  }
  long upload(String name,Long parent) throws Exception { return Long.parseLong(drive.upload(owner,new MockMultipartFile("file",name,"text/plain","合成文档：预算三万元".getBytes(java.nio.charset.StandardCharsets.UTF_8)),parent).id()); }
  void drain() { for(int i=0;i<30;i++)if(!worker.processOne())return;fail("Queue did not drain"); }
  @Test void uploadOnlyEnqueuesAndWorkerPublishesAfterRemoteWrite() throws Exception {
    long id=upload("budget.txt",null);verifyNoInteractions(embeddings,vectors);
    assertEquals("PENDING",queue.documents(owner.getId()).get(0).status());assertTrue(worker.processOne());
    assertEquals("READY",queue.documents(owner.getId()).get(0).status());assertEquals(1,queue.documents(owner.getId()).get(0).chunks());
    verify(vectors).upsert(eq(owner.getId()),eq(id),eq(1L),anyList(),anyList());
  }
  @Test void outerRollbackLeavesNoMetadataOrIndexTask() {
    tx.executeWithoutResult(status->{try { upload("rollback.txt",null); }catch(Exception ex){throw new RuntimeException(ex);}status.setRollbackOnly();});
    assertTrue(files.findAllByOwnerId(owner.getId()).isEmpty());assertTrue(queue.documents(owner.getId()).isEmpty());
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM claw_tasks",Integer.class));verifyNoInteractions(embeddings,vectors);
  }
  @Test void hiddenAncestorNeverLeavesContentForEmbeddingAndUnhideRequeues() throws Exception {
    long folder=Long.parseLong(drive.createFolder(owner,new DriveDtos.CreateFolderRequest("私有",null)).id());
    features.flag(owner,new FileFeatureDtos.FlagRequest(List.of(folder),true),true);long id=upload("hidden.txt",folder);
    assertNull(access.visible(owner.getId(),id));worker.processOne();verifyNoInteractions(embeddings);
    assertEquals("SKIPPED",queue.documents(owner.getId()).get(0).status());
    features.flag(owner,new FileFeatureDtos.FlagRequest(List.of(folder),false),true);drain();assertEquals("READY",queue.documents(owner.getId()).get(0).status());
    drive.trash(owner,new DriveDtos.FileIdsRequest(List.of(folder)));assertNull(access.visible(owner.getId(),id));
    drive.restore(owner,new DriveDtos.FileIdsRequest(List.of(folder)));drain();assertNotNull(access.visible(owner.getId(),id));
  }
  @Test void permanentDeleteLeavesDurableTombstoneAndNoDocument() throws Exception {
    long id=upload("delete.txt",null);drain();drive.trash(owner,new DriveDtos.FileIdsRequest(List.of(id)));
    drive.deleteForever(owner,new DriveDtos.FileIdsRequest(List.of(id)));assertTrue(queue.documents(owner.getId()).isEmpty());
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM claw_tasks WHERE operation='DELETE'",Integer.class));drain();
    verify(vectors).deleteCollection(anyString(),eq(owner.getId()),eq(id),isNull());
  }
  @Test void slowOldWriteCannotPublishOverRenameGeneration() throws Exception {
    long id=upload("old.txt",null);
    doAnswer(call->{drive.rename(owner,id,new DriveDtos.RenameRequest("new.txt"));return null;}).when(vectors).upsert(eq(owner.getId()),eq(id),eq(1L),anyList(),anyList());
    worker.processOne();var document=queue.documents(owner.getId()).get(0);assertEquals(2,document.generation());assertEquals("PENDING",document.status());
    drain();assertEquals("READY",queue.documents(owner.getId()).get(0).status());
    verify(vectors).deleteCollection(anyString(),eq(owner.getId()),eq(id),eq(1L));
  }
  @Test void staleReclaimedLeaseCannotDeleteSameGenerationPublishedByNewWorker() throws Exception {
    long id=upload("lease.txt",null);
    doAnswer(call->{
      jdbc.update("UPDATE claw_tasks SET next_attempt_at=? WHERE operation='UPSERT'",java.time.LocalDateTime.now(java.time.Clock.systemUTC()).minusMinutes(1));
      var reclaimed=queue.claim();assertNotNull(reclaimed);assertTrue(queue.publish(reclaimed,1));queue.finish(reclaimed);return null;
    }).when(vectors).upsert(eq(owner.getId()),eq(id),eq(1L),anyList(),anyList());
    worker.processOne();assertEquals("READY",queue.documents(owner.getId()).get(0).status());
    verify(vectors,never()).deleteCollection(anyString(),anyLong(),anyLong(),any());
  }
  @Test void providerFailureDoesNotRollBackUploadAndIsRetriedWithSafeCode() throws Exception {
    when(embeddings.embed(anyList())).thenThrow(new ClawFailure("CLAW_EMBEDDING_UNAVAILABLE"));long id=upload("retry.txt",null);worker.processOne();
    assertTrue(files.existsById(id));assertEquals("FAILED",queue.documents(owner.getId()).get(0).status());
    assertEquals("CLAW_EMBEDDING_UNAVAILABLE",queue.documents(owner.getId()).get(0).errorCode());
    assertEquals("RETRY",jdbc.queryForObject("SELECT status FROM claw_tasks WHERE operation='UPSERT'",String.class));
  }
  @Test void incompatibleModelInstanceDoesNotConsumeAnotherCollection() throws Exception {
    long id=upload("model.txt",null);jdbc.update("UPDATE claw_documents SET collection_name='different_model' WHERE file_id=?",id);jdbc.update("UPDATE claw_tasks SET collection_name='different_model' WHERE file_id=?",id);
    assertFalse(worker.processOne());assertEquals("PENDING",queue.documents(owner.getId()).get(0).status());verifyNoInteractions(embeddings,vectors);
  }
  @Test void deletionTombstoneKeepsRetryingAfterLongOutage() throws Exception {
    long id=upload("outage.txt",null);drain();drive.trash(owner,new DriveDtos.FileIdsRequest(List.of(id)));drive.deleteForever(owner,new DriveDtos.FileIdsRequest(List.of(id)));
    doThrow(new ClawFailure("CLAW_VECTOR_UNAVAILABLE")).when(vectors).deleteCollection(anyString(),anyLong(),anyLong(),any());
    for(int i=0;i<6;i++) {assertTrue(worker.processOne());assertEquals("RETRY",jdbc.queryForObject("SELECT status FROM claw_tasks WHERE operation='DELETE'",String.class));jdbc.update("UPDATE claw_tasks SET next_attempt_at=?",java.time.LocalDateTime.now(java.time.Clock.systemUTC()).minusMinutes(1));}
    doNothing().when(vectors).deleteCollection(anyString(),anyLong(),anyLong(),any());drain();assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM claw_tasks",Integer.class));
  }
  @Test void latePartialWriteAfterReclaimAndDeletionLeavesDurableCleanup() throws Exception {
    long id=upload("late.txt",null);
    doAnswer(call->{
      jdbc.update("UPDATE claw_tasks SET next_attempt_at=? WHERE operation='UPSERT'",java.time.LocalDateTime.now(java.time.Clock.systemUTC()).minusMinutes(1));var newer=queue.claim();assertTrue(queue.publish(newer,1));queue.finish(newer);
      drive.trash(owner,new DriveDtos.FileIdsRequest(List.of(id)));drive.deleteForever(owner,new DriveDtos.FileIdsRequest(List.of(id)));assertTrue(worker.processOne());
      throw new ClawFailure("CLAW_VECTOR_UNAVAILABLE"); // Late partial upsert after original tombstone already completed.
    }).when(vectors).upsert(eq(owner.getId()),eq(id),eq(1L),anyList(),anyList());
    worker.processOne();assertTrue(queue.documents(owner.getId()).isEmpty());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM claw_tasks WHERE operation='DELETE' AND generation=1",Integer.class));drain();verify(vectors).deleteCollection(anyString(),eq(owner.getId()),eq(id),eq(1L));
  }
  @Test void publishingFencesLeaseUntilDocumentMutationCommits() throws Exception {
    long id=upload("fence.txt",null);var task=queue.claim();jdbc.update("UPDATE claw_tasks SET next_attempt_at=? WHERE id=?",java.time.LocalDateTime.now(java.time.Clock.systemUTC()).minusMinutes(1),task.id());
    var paused=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var started=new java.util.concurrent.CountDownLatch(1);
    var executor=java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(r,"old-publisher"));
    doAnswer(call->{if(Thread.currentThread().getName().equals("old-publisher")) {paused.countDown();assertTrue(release.await(3,java.util.concurrent.TimeUnit.SECONDS));}return call.callRealMethod();}).when(access).visible(owner.getId(),id);
    try {
      var published=executor.submit(()->queue.publish(task,2));assertTrue(paused.await(2,java.util.concurrent.TimeUnit.SECONDS));
      var reclaimed=java.util.concurrent.CompletableFuture.supplyAsync(()->{started.countDown();return queue.claim();});assertTrue(started.await(1,java.util.concurrent.TimeUnit.SECONDS));
      assertThrows(java.util.concurrent.TimeoutException.class,()->reclaimed.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));release.countDown();assertTrue(published.get(2,java.util.concurrent.TimeUnit.SECONDS));
      var newer=reclaimed.get(2,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(newer);assertTrue(queue.publish(newer,3));queue.finish(newer);assertEquals(3,queue.documents(owner.getId()).get(0).chunks());
    } finally {release.countDown();executor.shutdownNow();}
  }
  @Test void otherOwnerAndBrokenParentChainsFailClosed() throws Exception {
    long id=upload("owner.txt",null);assertNull(access.visible(other.getId(),id));
    tx.executeWithoutResult(status->{DriveFile file=files.findById(id).orElseThrow();file.moveTo(id);files.saveAndFlush(file);});
    assertNull(access.visible(owner.getId(),id));assertTrue(access.visibleFiles(owner.getId()).isEmpty());
  }
}
