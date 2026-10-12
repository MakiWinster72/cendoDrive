package com.cendodrive.drive;

import com.cendodrive.ai.*;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:clawsearch;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
    "cendo.ai.claw.enabled=true","cendo.ai.claw.worker-enabled=false","cendo.ai.claw.embedding.base-url=https://model.test/v1",
    "cendo.ai.claw.embedding.key=test-key","cendo.ai.claw.embedding.dimensions=2","cendo.ai.claw.vector.base-url=http://vector.test"})
class ClawSearchIntegrationTest {
  @Autowired DriveService drive;@Autowired FileFeatureService features;@Autowired DriveFileRepository files;
  @Autowired UserRepository users;@Autowired UploadSessionRepository sessions;@Autowired JdbcTemplate jdbc;
  @Autowired ClawIndexQueue queue;@Autowired ClawIndexWorker worker;@Autowired ClawSearchService search;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;@Autowired jakarta.persistence.EntityManager em;
  @MockBean FileStorage storage;@MockBean ClawEmbeddingClient embeddings;@MockBean ClawVectorStore vectors;@MockBean AiChatService chat;
  User owner,other;Map<String,byte[]> blobs=new HashMap<>();
  @BeforeEach void setup() throws Exception {
    jdbc.update("DELETE FROM claw_tasks");jdbc.update("DELETE FROM claw_documents");sessions.deleteAllInBatch();files.deleteAllInBatch();users.deleteAllInBatch();
    owner=users.saveAndFlush(new User("clawask","hash","测试一"));other=users.saveAndFlush(new User("clawaskother","hash","测试二"));
    when(storage.upload(any(),anyLong(),anyString())).thenAnswer(call->{String key=UUID.randomUUID().toString();blobs.put(key,((InputStream)call.getArgument(0)).readAllBytes());return key;});
    doAnswer(call->{((OutputStream)call.getArgument(1)).write(blobs.get(call.getArgument(0)));return null;}).when(storage).download(anyString(),any());
    when(embeddings.embed(anyList())).thenAnswer(call->((List<?>)call.getArgument(0)).stream().map(x->List.of(1.0,0.0)).toList());
    when(chat.status()).thenReturn(new AiChatDtos.StatusResponse(true,"chat-model"));
    when(chat.groundedAnswer(anyString(),anyString())).thenReturn(new AiChatDtos.ChatResponse("项目预算为三万元。[1]"));
  }
  long ready(User user,String name,Long parent) throws Exception {
    long id=Long.parseLong(drive.upload(user,new MockMultipartFile("file",name,"text/plain","合成文档：预算三万元".getBytes(java.nio.charset.StandardCharsets.UTF_8)),parent).id());
    for(int i=0;i<30;i++)if(!worker.processOne())return id;throw new AssertionError("Queue did not drain");
  }
  ClawVectorStore.Hit hit(long id,long generation) {return new ClawVectorStore.Hit(owner.getId(),id,generation,0,"项目预算为三万元。",0.9);}
  @Test void retrievedAnswerCarriesServerGeneratedNamePathAndCitation() throws Exception {
    var folder=drive.createFolder(owner,new DriveDtos.CreateFolderRequest("项目",null));long id=ready(owner,"预算.txt",Long.parseLong(folder.id()));
    when(vectors.query(eq(owner.getId()),anyMap(),anyList())).thenReturn(List.of(hit(id,1)));
    var answer=search.ask(owner.getId(),new ClawDtos.Question("项目预算是多少？",null));
    assertTrue(answer.matched());assertEquals("项目预算为三万元。[1]",answer.content());assertEquals(1,answer.sources().size());
    var source=answer.sources().get(0);assertEquals(Long.toString(id),source.fileId());assertEquals("预算.txt",source.name());assertEquals("/项目/预算.txt",source.path());assertEquals(1,source.citation());
    verify(chat).groundedAnswer(eq("项目预算是多少？"),argThat(context->context.contains("三万元")&&context.contains("预算.txt")));
  }
  @Test void foreignStaleLowScoreAndDuplicateHitsNeverBecomeEvidence() throws Exception {
    long own=ready(owner,"预算.txt",null),foreign=ready(other,"保密.txt",null);
    when(vectors.query(eq(owner.getId()),anyMap(),anyList())).thenReturn(List.of(
        new ClawVectorStore.Hit(owner.getId(),foreign,1,0,"外国用户的机密",0.99),
        new ClawVectorStore.Hit(other.getId(),own,1,0,"错误所有者",0.99),
        new ClawVectorStore.Hit(owner.getId(),own,0,0,"旧代次",0.99),
        new ClawVectorStore.Hit(owner.getId(),own,1,0,"低分资料",0.1),hit(own,1),hit(own,1)));
    var answer=search.ask(owner.getId(),new ClawDtos.Question("预算是多少？",null));assertEquals(1,answer.sources().size());
    verify(vectors).query(eq(owner.getId()),eq(Map.of(own,1L)),anyList());
    verify(chat).groundedAnswer(anyString(),argThat(context->!context.contains("机密")&&!context.contains("旧代次")&&!context.contains("错误所有者")&&!context.contains("低分资料")));
  }
  @Test void hiddenDeletedAndBrokenAncestorFilesAreExcludedBeforeEmbedding() throws Exception {
    long hidden=ready(owner,"隐藏.txt",null),deleted=ready(owner,"回收.txt",null),broken=ready(owner,"断链.txt",null);
    long foreignFolder=Long.parseLong(drive.createFolder(other,new DriveDtos.CreateFolderRequest("外部目录",null)).id());
    jdbc.update("UPDATE drive_files SET hidden=TRUE WHERE id=?",hidden);jdbc.update("UPDATE drive_files SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",deleted);
    jdbc.update("UPDATE drive_files SET parent_id=? WHERE id=?",foreignFolder,broken);clearInvocations(embeddings,vectors,chat);
    assertFalse(search.ask(owner.getId(),new ClawDtos.Question("预算？",null)).matched());verifyNoInteractions(embeddings,vectors);verify(chat,never()).groundedAnswer(anyString(),anyString());
  }
  @Test void hiddenAncestorDuringVectorLookupPreventsSendingPassagesToChat() throws Exception {
    long folder=Long.parseLong(drive.createFolder(owner,new DriveDtos.CreateFolderRequest("项目",null)).id());long id=ready(owner,"预算.txt",folder);
    when(vectors.query(eq(owner.getId()),anyMap(),anyList())).thenAnswer(call->{jdbc.update("UPDATE drive_files SET hidden=TRUE WHERE id=?",folder);return List.of(hit(id,1));});
    var error=assertThrows(ClawFailure.class,()->search.ask(owner.getId(),new ClawDtos.Question("预算？",null)));
    assertEquals("CLAW_FILE_UNAVAILABLE",error.code());verify(chat,never()).groundedAnswer(anyString(),anyString());
  }
  @Test void hiddenDuringInferenceDiscardsTheEntireGeneratedAnswer() throws Exception {
    long id=ready(owner,"预算.txt",null);when(vectors.query(eq(owner.getId()),anyMap(),anyList())).thenReturn(List.of(hit(id,1)));
    when(chat.groundedAnswer(anyString(),anyString())).thenAnswer(call->{jdbc.update("UPDATE drive_files SET hidden=TRUE WHERE id=?",id);return new AiChatDtos.ChatResponse("不能返回这个答案");});
    assertEquals("CLAW_FILE_UNAVAILABLE",assertThrows(ClawFailure.class,()->search.ask(owner.getId(),new ClawDtos.Question("预算？",null))).code());
  }
  @Test void ancestorRenameDuringInferenceDiscardsStalePathAndAnswer() throws Exception {
    long folder=Long.parseLong(drive.createFolder(owner,new DriveDtos.CreateFolderRequest("旧目录",null)).id());long id=ready(owner,"预算.txt",folder);
    when(vectors.query(eq(owner.getId()),anyMap(),anyList())).thenReturn(List.of(hit(id,1)));
    when(chat.groundedAnswer(anyString(),anyString())).thenAnswer(call->{jdbc.update("UPDATE drive_files SET name='新目录' WHERE id=?",folder);return new AiChatDtos.ChatResponse("资料位于旧目录");});
    assertEquals("CLAW_FILE_UNAVAILABLE",assertThrows(ClawFailure.class,()->search.ask(owner.getId(),new ClawDtos.Question("预算？",null))).code());
  }
  @Test void longPathAncestorRenameDuringInferenceDiscardsStaleAnswer() throws Exception {
    long root=Long.parseLong(drive.createFolder(owner,new DriveDtos.CreateFolderRequest("旧根",null)).id());
    Long parent=root;
    for(int i=0;i<9;i++) parent=Long.parseLong(drive.createFolder(owner,new DriveDtos.CreateFolderRequest("目录"+i+"甲".repeat(245),parent)).id());
    long id=ready(owner,"预算.txt",parent);
    when(vectors.query(eq(owner.getId()),anyMap(),anyList())).thenReturn(List.of(hit(id,1)));
    when(chat.groundedAnswer(anyString(),anyString())).thenAnswer(call->{
      drive.rename(owner,root,new DriveDtos.RenameRequest("新根"));
      assertEquals(1L,jdbc.queryForObject("SELECT generation FROM claw_documents WHERE file_id=?",Long.class,id));
      return new AiChatDtos.ChatResponse("不能返回包含旧路径的答案");
    });
    assertEquals("CLAW_FILE_UNAVAILABLE",assertThrows(ClawFailure.class,()->search.ask(owner.getId(),new ClawDtos.Question("预算？",null))).code());
  }
  @Test void noVectorMatchDoesNotCallChatAndPreviousQuestionIsOnlyQueryContext() throws Exception {
    ready(owner,"预算.txt",null);clearInvocations(embeddings,vectors,chat);when(vectors.query(anyLong(),anyMap(),anyList())).thenReturn(List.of());
    assertFalse(search.ask(owner.getId(),new ClawDtos.Question("是多少？","项目预算")).matched());
    verify(embeddings).embed(eq(List.of("前一个问题：项目预算\n当前问题：是多少？")));verify(chat,never()).groundedAnswer(anyString(),anyString());
  }
  @Test void changedEmbeddingCollectionIsNeverQueried() throws Exception {
    long id=ready(owner,"预算.txt",null);jdbc.update("UPDATE claw_documents SET collection_name='another_model' WHERE file_id=?",id);clearInvocations(embeddings,vectors,chat);
    assertFalse(search.ask(owner.getId(),new ClawDtos.Question("预算？",null)).matched());verifyNoInteractions(embeddings,vectors);
  }
  @Test void freshPermissionSnapshotDoesNotTrustCachedJpaEntities() throws Exception {
    long id=ready(owner,"预算.txt",null);clearInvocations(embeddings,vectors,chat);
    tx.execute(state->{
      var cached=em.find(DriveFile.class,id);assertFalse(cached.isHidden());jdbc.update("UPDATE drive_files SET hidden=TRUE WHERE id=?",id);
      assertFalse(em.find(DriveFile.class,id).isHidden());assertFalse(search.ask(owner.getId(),new ClawDtos.Question("预算？",null)).matched());return null;
    });verifyNoInteractions(embeddings,vectors);verify(chat,never()).groundedAnswer(anyString(),anyString());
  }
  @Test void statusOnlyListsVisibleOwnedFilesAndIdentifiesOldCollections() throws Exception {
    ready(owner,"预算.txt",null);long hidden=ready(owner,"隐藏秘密.txt",null);ready(other,"其他用户.txt",null);
    jdbc.update("UPDATE drive_files SET hidden=TRUE WHERE id=?",hidden);
    drive.upload(owner,new MockMultipartFile("file","排队.txt","text/plain","待索引".getBytes()),null);
    drive.upload(owner,new MockMultipartFile("file","照片.jpg","image/jpeg",new byte[]{1}),null);
    var state=search.status(owner.getId());assertTrue(state.configured());assertEquals(1,state.ready());assertEquals(1,state.pending());assertEquals(1,state.unsupported());assertEquals(3,state.files().size());
    assertTrue(state.files().stream().noneMatch(file->file.name().contains("秘密")||file.name().contains("其他用户")));
    jdbc.update("UPDATE claw_documents SET collection_name='old_model' WHERE owner_id=?",owner.getId());assertEquals(3,search.status(owner.getId()).needsReindex());
  }
  @Test void retryStatusIsDistinctFromPermanentFailureAndNeverLeaksDiagnostics() throws Exception {
    when(embeddings.embed(anyList())).thenThrow(new ClawFailure("CLAW_EMBEDDING_UNAVAILABLE"));
    drive.upload(owner,new MockMultipartFile("file","重试.txt","text/plain","待重试".getBytes()),null);assertTrue(worker.processOne());
    var state=search.status(owner.getId());assertEquals(1,state.pending());assertEquals(0,state.failed());assertEquals("RETRY",state.files().get(0).status());
    jdbc.update("UPDATE claw_tasks SET status='DEAD' WHERE operation='UPSERT'");jdbc.update("UPDATE claw_documents SET error_code='private-provider-credential'");
    state=search.status(owner.getId());assertEquals(0,state.pending());assertEquals(1,state.failed());assertEquals("CLAW_EXTRACTION_FAILED",state.files().get(0).errorCode());
  }
  @Test void reindexRequiresConsentAndRejectsForeignOrHiddenFilesWithoutModelCalls() throws Exception {
    long own=ready(owner,"预算.txt",null),foreign=ready(other,"保密.txt",null);clearInvocations(embeddings,vectors,chat);
    assertEquals("CLAW_CONSENT_REQUIRED",assertThrows(ClawFailure.class,()->search.reindexFile(owner.getId(),own,new ClawDtos.ReindexRequest(false))).code());
    assertEquals("CLAW_FILE_UNAVAILABLE",assertThrows(ClawFailure.class,()->search.reindexFile(owner.getId(),foreign,new ClawDtos.ReindexRequest(true))).code());
    jdbc.update("UPDATE drive_files SET hidden=TRUE WHERE id=?",own);
    assertEquals("CLAW_FILE_UNAVAILABLE",assertThrows(ClawFailure.class,()->search.reindexFile(owner.getId(),own,new ClawDtos.ReindexRequest(true))).code());verifyNoInteractions(embeddings,vectors);
  }
  @Test void bulkReindexOnlyEnqueuesVisibleSupportedOwnedDocuments() throws Exception {
    long own=ready(owner,"预算.txt",null),hidden=ready(owner,"隐藏.txt",null),foreign=ready(other,"其他.txt",null);
    jdbc.update("UPDATE drive_files SET hidden=TRUE WHERE id=?",hidden);clearInvocations(embeddings,vectors,chat);
    assertEquals(1,search.reindexAll(owner.getId(),new ClawDtos.ReindexRequest(true)).queued());
    var documents=queue.documents(owner.getId());assertEquals(2,documents.stream().filter(doc->doc.fileId()==own).findFirst().orElseThrow().generation());
    assertEquals("PENDING",documents.stream().filter(doc->doc.fileId()==own).findFirst().orElseThrow().status());assertEquals(1,queue.documents(other.getId()).get(0).generation());
    assertEquals(foreign,queue.documents(other.getId()).get(0).fileId());verifyNoInteractions(embeddings,vectors);
  }
  @Test void passageBudgetIsFourFilesAndTwoChunksPerFile() throws Exception {
    var hits=new ArrayList<ClawVectorStore.Hit>();
    for(int i=0;i<5;i++) {
      long id=ready(owner,"预算"+i+".txt",null);jdbc.update("UPDATE claw_documents SET chunks=3 WHERE file_id=?",id);
      hits.add(hit(id,1));hits.add(new ClawVectorStore.Hit(owner.getId(),id,1,1,"第二片段",0.8));hits.add(new ClawVectorStore.Hit(owner.getId(),id,1,2,"不应出现的第三片段",0.7));
    }
    when(vectors.query(anyLong(),anyMap(),anyList())).thenReturn(hits);
    var answer=search.ask(owner.getId(),new ClawDtos.Question("预算？",null));assertEquals(4,answer.sources().size());
    verify(chat).groundedAnswer(anyString(),argThat(context->!context.contains("第三片段")&&!context.contains("预算4.txt")));
  }
  @Test void escapingHeavyEvidenceFitsJsonBudgetAndKeepsSourceExcerptsConsistent() throws Exception {
    var hits=new ArrayList<ClawVectorStore.Hit>();
    for(int i=0;i<4;i++) {
      long id=ready(owner,"预算"+i+".txt",null);jdbc.update("UPDATE claw_documents SET chunks=2 WHERE file_id=?",id);
      hits.add(new ClawVectorStore.Hit(owner.getId(),id,1,0,"预算"+"\u0001".repeat(1770),0.9));
      hits.add(new ClawVectorStore.Hit(owner.getId(),id,1,1,"😀".repeat(899),0.8));
    }
    when(vectors.query(anyLong(),anyMap(),anyList())).thenReturn(hits);
    var answer=search.ask(owner.getId(),new ClawDtos.Question("预算？",null));assertEquals(4,answer.sources().size());
    var captured=org.mockito.ArgumentCaptor.forClass(String.class);verify(chat).groundedAnswer(eq("预算？"),captured.capture());
    assertTrue(captured.getValue().length()<=24000,"Serialized JSON, including escaping, must fit the model evidence budget");
    var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(captured.getValue());
    for(int i=0;i<4;i++) {
      var passages=data.get(i).path("passages");
      assertEquals(passages.get(0).asText()+"\n…\n"+passages.get(1).asText(),answer.sources().get(i).excerpt());
      assertEquals(answer.sources().get(i).citation(),data.get(i).path("citation").asInt());
      var emoji=passages.get(1).asText().codePoints().toArray();
      for(int code:emoji) assertFalse(code>=0xD800 && code<=0xDFFF,"Truncation must not split UTF-16 surrogate pairs");
    }
  }
  @Test void concurrentAsksAreBoundedAndPermitsAreReleasedAfterProviderFailures() throws Exception {
    long id=ready(owner,"预算.txt",null);var entered=new java.util.concurrent.CountDownLatch(4);var release=new java.util.concurrent.CountDownLatch(1);
    var executor=java.util.concurrent.Executors.newFixedThreadPool(4);var tasks=new ArrayList<java.util.concurrent.Future<?>>();
    when(vectors.query(anyLong(),anyMap(),anyList())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));throw new ClawFailure("CLAW_VECTOR_UNAVAILABLE");});
    try {
      for(int i=0;i<4;i++) tasks.add(executor.submit(()->search.ask(owner.getId(),new ClawDtos.Question("预算？",null))));
      assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals("CLAW_BUSY",assertThrows(ClawFailure.class,()->search.ask(owner.getId(),new ClawDtos.Question("预算？",null))).code());
      release.countDown();for(var task:tasks) assertThrows(java.util.concurrent.ExecutionException.class,()->task.get(5,java.util.concurrent.TimeUnit.SECONDS));
      doReturn(List.of(hit(id,1))).when(vectors).query(anyLong(),anyMap(),anyList());assertTrue(search.ask(owner.getId(),new ClawDtos.Question("预算？",null)).matched());
    } finally {release.countDown();executor.shutdownNow();}
  }
  @Test void singleReindexOnlyQueuesFreshGenerationWithoutSynchronousModelWork() throws Exception {
    long id=ready(owner,"预算.txt",null);clearInvocations(embeddings,vectors,chat);
    assertEquals(1,search.reindexFile(owner.getId(),id,new ClawDtos.ReindexRequest(true)).queued());
    var doc=queue.documents(owner.getId()).get(0);assertEquals(2,doc.generation());assertEquals("PENDING",doc.status());verifyNoInteractions(embeddings,vectors);
  }
  @Test void missingChatConfigurationFailsBeforeModelCalls() {
    when(chat.status()).thenReturn(new AiChatDtos.StatusResponse(false,null));assertFalse(search.status(owner.getId()).configured());
    assertEquals("CLAW_NOT_CONFIGURED",assertThrows(ClawFailure.class,()->search.ask(owner.getId(),new ClawDtos.Question("预算？",null))).code());verifyNoInteractions(embeddings,vectors);
  }
  @Test void noIndexedFileDoesNotCallAnyModel() {
    var answer=search.ask(owner.getId(),new ClawDtos.Question("预算是多少？",null));assertFalse(answer.matched());assertTrue(answer.sources().isEmpty());
    verifyNoInteractions(embeddings,vectors);verify(chat,never()).groundedAnswer(anyString(),anyString());
  }
}
