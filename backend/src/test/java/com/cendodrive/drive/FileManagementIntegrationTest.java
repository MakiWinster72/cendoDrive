package com.cendodrive.drive;

import com.cendodrive.auth.AuthService;
import com.cendodrive.storage.FileStorage;
import com.cendodrive.storage.StorageCleanupTask;
import com.cendodrive.storage.StorageCleanupTaskRepository;
import com.cendodrive.storage.StorageCleanupWorker;
import com.cendodrive.upload.*;
import com.cendodrive.index.*;
import com.cendodrive.user.*;
import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:filefeatures;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true","cendo.upload.cleanup-delay-ms=86400000",
    "cendo.storage.cleanup-delay-ms=86400000"})
@AutoConfigureMockMvc
class FileManagementIntegrationTest {
  static final Path STAGING=staging();
  @DynamicPropertySource static void config(DynamicPropertyRegistry registry) { registry.add("cendo.upload.root",STAGING::toString); }
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired DriveFileRepository files;
  @Autowired UploadSessionRepository sessions;
  @Autowired UploadService uploads;
  @Autowired DriveService drive;
  @Autowired FileQuotaService quota;
  @Autowired AiIndexTaskRepository indexTasks;
  @Autowired StorageCleanupTaskRepository cleanupTasks;
  @Autowired StorageCleanupWorker cleanupWorker;
  @Autowired TransactionTemplate transactions;
  @MockBean AuthService auth;
  @MockBean FileStorage storage;
  User owner,other;
  Map<String,byte[]> blobs=new ConcurrentHashMap<>();
  Map<String,Long> sizes=new ConcurrentHashMap<>();

  @BeforeEach void setup() throws Exception {
    cleanupTasks.deleteAllInBatch(); indexTasks.deleteAllInBatch(); sessions.deleteAllInBatch(); files.deleteAllInBatch(); users.deleteAllInBatch();
    owner=users.saveAndFlush(new User("owner","test-hash","用户一"));
    other=users.saveAndFlush(new User("other","test-hash","用户二"));
    when(auth.authenticate("owner")).thenReturn(owner);
    when(auth.authenticate("other")).thenReturn(other);
    when(storage.upload(any(),anyLong(),anyString())).thenAnswer(call -> {
      InputStream input=call.getArgument(0); long size=call.getArgument(1);
      String key="group1/"+UUID.randomUUID();
      long received;
      if (size<=1048576) { byte[] bytes=input.readAllBytes(); blobs.put(key,bytes); received=bytes.length; }
      else received=input.transferTo(OutputStream.nullOutputStream());
      assertEquals(size,received,"FileStorage must receive the complete stream"); sizes.put(key,size); return key;
    });
    doAnswer(call -> {
      byte[] bytes=blobs.get(call.getArgument(0)); if (bytes==null) throw new IOException("Missing test blob");
      ((OutputStream)call.getArgument(1)).write(bytes); return null;
    }).when(storage).download(anyString(),any());
    doAnswer(call -> { blobs.remove(call.getArgument(0)); sizes.remove(call.getArgument(0)); return null; }).when(storage).delete(anyString());
  }

  @Test void sharedCopiesRespectRecipientReservationsAndRefreshUsage() throws Exception {
    byte[] content="hello".getBytes();
    var source=drive.upload(other,new MockMultipartFile("file","shared.txt","text/plain",content),null);
    long tasksBeforeSave=indexTasks.count();
    limit(9);
    byte[] pending=new byte[5];
    String reservation=init("owner","pending.bin",5,md5(pending),null,5,1,201).path("uploadId").asText();
    clearInvocations(storage);
    assertEquals("QUOTA_EXCEEDED",assertThrows(DriveFailure.class,
        () -> drive.saveSharedFile(owner,other,Long.valueOf(source.id()),null)).code());
    verifyNoInteractions(storage);
    assertEquals(0,usage().path("usedBytes").asLong());
    assertEquals(5,usage().path("reservedBytes").asLong());
    sessions.deleteById(reservation);
    var copy=drive.saveSharedFile(owner,other,Long.valueOf(source.id()),null);
    assertEquals(tasksBeforeSave,indexTasks.count(),"share saves do not enter this indexing scope");
    assertEquals(5,usage().path("usedBytes").asLong());
    assertEquals(5,users.findById(owner.getId()).orElseThrow().getStorageUsed());
    var original=files.findById(Long.valueOf(source.id())).orElseThrow();
    var copied=files.findById(Long.valueOf(copy.id())).orElseThrow();
    assertNotEquals(original.getStorageKey(),copied.getStorageKey());
    download(copy.id(),content);
    assertArrayEquals(content,blobs.get(original.getStorageKey()));
  }

  @Test void chunksResumeOutOfOrderRetryAndMergeExactlyOnce() throws Exception {
    byte[] bytes="hello-world".getBytes(); String hash=md5(bytes);
    JsonNode initial=init("owner","hello.txt",bytes.length,hash,null,4,3,201);
    String id=initial.path("uploadId").asText();
    assertEquals(id,init("owner","hello.txt",bytes.length,hash,null,4,3,201).path("uploadId").asText());
    assertEquals(bytes.length,usage().path("reservedBytes").asLong());
    chunk("owner",id,2,3,hash,Arrays.copyOfRange(bytes,8,11),200);
    chunk("owner",id,0,3,hash,Arrays.copyOfRange(bytes,0,4),200);
    chunk("owner",id,0,3,hash,Arrays.copyOfRange(bytes,0,4),200);
    assertEquals(List.of(0,2),json.convertValue(call(get("/api/upload/"+id+"/status"),"owner",200).path("uploadedChunks"),List.class));
    // A new service instance uses the same committed database and disk, not a Java in-memory map.
    UploadService restarted=new UploadService(sessions,quota,drive,STAGING.toString(),24);
    assertEquals(List.of(0,2),transactions.execute(status -> {
      try { return restarted.status(owner,id).uploadedChunks(); } catch (IOException e) { throw new UncheckedIOException(e); }
    }));
    assertEquals("INCOMPLETE_UPLOAD",merge("owner",id,hash,409).path("code").asText());
    chunk("owner",id,1,3,hash,Arrays.copyOfRange(bytes,4,8),200);
    JsonNode saved=merge("owner",id,hash,201);
    assertEquals(saved.path("id").asText(),merge("owner",id,hash,201).path("id").asText());
    assertEquals(1,files.count()); assertEquals(1,sizes.size());
    assertEquals(1,indexTasks.count());
    AiIndexTask task=indexTasks.findAll().getFirst();
    assertEquals(AiIndexTask.Operation.UPSERT,task.getOperation()); assertEquals(saved.path("id").asLong(),task.getFileId());
    assertEquals(bytes.length,usage().path("usedBytes").asLong()); assertEquals(0,usage().path("reservedBytes").asLong());
    assertEquals(bytes.length,users.findById(owner.getId()).orElseThrow().getStorageUsed());
    assertFalse(Files.exists(STAGING.resolve(owner.getId().toString()).resolve(id)));
    download(saved.path("id").asText(),bytes);
  }

  @Test void fileLargerThan100MiBUploadsInRealFiveMiBChunksWithoutWholeFileRam() throws Exception {
    int chunkSize=5*1024*1024; long size=101L*1024*1024; int count=21;
    byte[] full=new byte[chunkSize]; full[0]=17;
    byte[] last=Arrays.copyOf(full,1024*1024);
    MessageDigest digest=MessageDigest.getInstance("MD5");
    for (int i=0;i<count;i++) digest.update(i==count-1?last:full);
    String hash=HexFormat.of().formatHex(digest.digest());
    String id=init("owner","large.bin",size,hash,null,chunkSize,count,201).path("uploadId").asText();
    for (int i=0;i<count;i++) chunk("owner",id,i,count,hash,i==count-1?last:full,200);
    JsonNode result=merge("owner",id,hash,201);
    assertEquals(size,result.path("size").asLong()); assertEquals(size,usage().path("usedBytes").asLong());
    assertEquals(List.of(size),new ArrayList<>(sizes.values())); assertTrue(blobs.isEmpty());
    assertEquals(0,usage().path("reservedBytes").asLong());
  }

  @Test void uploadSessionsAndAllFileFeaturesAreOwnerIsolated() throws Exception {
    byte[] bytes="private".getBytes(); String hash=md5(bytes);
    String id=init("owner","private.txt",bytes.length,hash,null,7,1,201).path("uploadId").asText();
    call(get("/api/upload/"+id+"/status"),"other",404);
    chunk("other",id,0,1,hash,bytes,404); merge("other",id,hash,404);
    chunk("owner",id,0,1,hash,bytes,200); long file=merge("owner",id,hash,201).path("id").asLong();
    call(get("/api/files/"+file+"/details"),"other",404);
    call(postJson("/api/files/favorite",Map.of("ids",List.of(file),"value",true)),"other",404);
    call(postJson("/api/files/hidden",Map.of("ids",List.of(file),"value",true)),"other",404);
    call(postJson("/api/files/copy",target(List.of(file),null)),"other",404);
    call(postJson("/api/files/move",target(List.of(file),null)),"other",404);
    call(postJson("/api/files/organize",Map.of("ids",List.of(file))),"other",404);
    assertEquals(0,call(get("/api/files/favorites"),"other",200).size());
    assertEquals(0,call(get("/api/files/hidden"),"other",200).size());
    for (String endpoint:List.of("/api/files/usage","/api/files/hidden","/api/files/favorites","/api/upload/"+id+"/status"))
      mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
    mvc.perform(postJson("/api/upload/init",initBody("anonymous.bin",1,md5(new byte[1]),null,1,1))).andExpect(status().isUnauthorized());
  }

  @Test void invalidChunkLayoutAndLengthAreRejectedAndCorruptionIsRecoverable() throws Exception {
    byte[] bytes="hello".getBytes(); String hash=md5(bytes);
    init("owner","bad.txt",5,hash,null,2,2,400);
    init("owner","bad.txt",0,hash,null,1,1,400);
    init("owner","bad.txt",5,"not-a-hash",null,5,1,400);
    String id=init("owner","good.txt",5,hash,null,5,1,201).path("uploadId").asText();
    chunk("owner",id,1,1,hash,bytes,400); chunk("owner",id,0,2,hash,bytes,400);
    chunk("owner",id,0,1,hash,new byte[4],400);
    chunk("owner",id,0,1,hash,"wrong".getBytes(),200);
    assertEquals("HASH_MISMATCH",merge("owner",id,hash,400).path("code").asText());
    assertEquals(0,call(get("/api/upload/"+id+"/status"),"owner",200).path("uploadedChunks").size());
    chunk("owner",id,0,1,hash,bytes,200); merge("owner",id,hash,201);
    assertEquals(5,usage().path("usedBytes").asLong());
  }

  @Test void expirationReleasesReservationAndCleanupRemovesOnlyExpiredStaging() throws Exception {
    byte[] bytes="expire".getBytes(); String hash=md5(bytes);
    String id=init("owner","old.txt",6,hash,null,6,1,201).path("uploadId").asText();
    chunk("owner",id,0,1,hash,bytes,200);
    UploadSession session=sessions.findById(id).orElseThrow();
    ReflectionTestUtils.setField(session,"expiresAt",LocalDateTime.now(Clock.systemUTC()).minusMinutes(1)); sessions.saveAndFlush(session);
    call(get("/api/upload/"+id+"/status"),"owner",410); assertEquals(0,usage().path("reservedBytes").asLong());
    String fresh=init("owner","fresh.txt",6,hash,null,6,1,201).path("uploadId").asText();
    chunk("owner",fresh,0,1,hash,bytes,200);
    assertEquals(1,uploads.cleanupExpired()); assertFalse(sessions.existsById(id)); assertTrue(sessions.existsById(fresh));
    assertFalse(Files.exists(STAGING.resolve(owner.getId().toString()).resolve(id)));
    assertTrue(Files.exists(STAGING.resolve(owner.getId().toString()).resolve(fresh)));
  }

  @Test void quotaIncludesReservationsTrashAndHiddenAndOnlyPurgeReleasesUsedBytes() throws Exception {
    limit(10);
    byte[] bytes=new byte[8]; String hash=md5(bytes);
    String id=init("owner","reserved.bin",8,hash,null,8,1,201).path("uploadId").asText();
    assertEquals(2,usage().path("availableBytes").asLong());
    assertEquals("QUOTA_EXCEEDED",upload("too-big.bin",new byte[3],null,507).path("code").asText());
    long small=upload("small.bin",new byte[2],null,201).path("id").asLong();
    assertEquals(id,init("owner","reserved.bin",8,hash,null,8,1,201).path("uploadId").asText());
    chunk("owner",id,0,1,hash,bytes,200); long file=merge("owner",id,hash,201).path("id").asLong();
    assertEquals(10,usage().path("usedBytes").asLong()); assertEquals(0,usage().path("availableBytes").asLong());
    call(postJson("/api/files/hidden",Map.of("ids",List.of(file),"value",true)),"owner",200);
    call(postJson("/api/files/favorite",Map.of("ids",List.of(file),"value",true)),"owner",200);
    assertEquals(1,call(get("/api/files/hidden"),"owner",200).size()); assertEquals(0,call(get("/api/files/favorites"),"owner",200).size());
    call(postJson("/api/files/trash",Map.of("ids",List.of(small))),"owner",200);
    assertEquals(10,usage().path("usedBytes").asLong()); assertEquals(2,usage().path("trashBytes").asLong());
    call(post("/api/files/trash/delete").contentType("application/json").content(json.writeValueAsString(Map.of("ids",List.of(small)))),"owner",204);
    cleanupWorker.cleanup();
    assertEquals(8,usage().path("usedBytes").asLong()); assertEquals(8,users.findById(owner.getId()).orElseThrow().getStorageUsed());
    call(postJson("/api/files/hidden",Map.of("ids",List.of(file),"value",false)),"owner",200);
    assertEquals(1,call(get("/api/files/favorites"),"owner",200).size());
  }

  @Test void deletedAncestorBlocksAccessAndRecursivePurgeDeletesContentAndMetadata() throws Exception {
    long parent=folder("parent",null); long child=folder("child",parent);
    long file=upload("private.txt","secret".getBytes(),child,201).path("id").asLong();
    call(postJson("/api/files/trash",Map.of("ids",List.of(parent))),"owner",200);
    assertEquals(List.of(AiIndexTask.Operation.UPSERT,AiIndexTask.Operation.DEACTIVATE),
        indexTasks.findAll().stream().map(AiIndexTask::getOperation).toList());
    assertEquals(List.of(1L,2L),indexTasks.findAll().stream().map(AiIndexTask::getRevision).toList());
    call(get("/api/files/"+file+"/download"),"owner",404);
    call(get("/api/files/"+file+"/details"),"owner",404);
    call(get("/api/files").param("parentId",String.valueOf(child)),"owner",404);
    call(put("/api/files/"+file+"/rename").contentType("application/json").content("{\"name\":\"leak.txt\"}"),"owner",404);
    assertEquals(6,usage().path("trashBytes").asLong()); assertEquals(0,call(get("/api/files/folders"),"owner",200).size());
    call(post("/api/files/trash/delete").contentType("application/json").content(json.writeValueAsString(Map.of("ids",List.of(parent)))),"owner",204);
    assertEquals(AiIndexTask.Operation.DELETE,indexTasks.findAll().getLast().getOperation());
    assertEquals(3,indexTasks.findAll().getLast().getRevision());
    assertEquals(1,cleanupTasks.count()); assertFalse(sizes.isEmpty());
    cleanupWorker.cleanup();
    assertEquals(StorageCleanupTask.Status.SUCCEEDED,cleanupTasks.findAll().getFirst().getStatus());
    assertEquals(0,files.count()); assertTrue(sizes.isEmpty()); assertEquals(0,usage().path("usedBytes").asLong());
  }

  @Test void restoreCreatesNewerUpsertOnlyForSearchableIndexedFiles() throws Exception {
    long folder=folder("folder",null);
    long indexed=upload("indexed.txt","hello".getBytes(),folder,201).path("id").asLong();
    long copied=call(postJson("/api/files/copy",target(List.of(indexed),folder)),"owner",201).get(0).path("id").asLong();
    call(postJson("/api/files/trash",Map.of("ids",List.of(folder))),"owner",200);
    call(postJson("/api/files/trash/restore",Map.of("ids",List.of(folder))),"owner",200);
    List<AiIndexTask> lifecycle=indexTasks.findAll();
    assertEquals(List.of(AiIndexTask.Operation.UPSERT,AiIndexTask.Operation.DEACTIVATE,AiIndexTask.Operation.UPSERT),
        lifecycle.stream().map(AiIndexTask::getOperation).toList());
    assertEquals(List.of(1L,2L,3L),lifecycle.stream().map(AiIndexTask::getRevision).toList());
    assertEquals(indexed,lifecycle.getLast().getFileId());
    assertEquals(0,files.findById(copied).orElseThrow().getIndexRevision());
  }

  @Test void failedPhysicalDeleteIsRecordedAndRetried() throws Exception {
    long file=upload("cleanup.txt","content".getBytes(),null,201).path("id").asLong();
    String key=files.findById(file).orElseThrow().getStorageKey();
    call(postJson("/api/files/trash",Map.of("ids",List.of(file))),"owner",200);
    call(post("/api/files/trash/delete").contentType("application/json")
        .content(json.writeValueAsString(Map.of("ids",List.of(file)))),"owner",204);
    assertEquals(0,files.count()); assertTrue(sizes.containsKey(key));
    doThrow(new IOException("FastDFS temporarily offline")).doAnswer(call -> {
      blobs.remove(key); sizes.remove(key); return null;
    }).when(storage).delete(key);
    cleanupWorker.cleanup();
    StorageCleanupTask task=cleanupTasks.findAll().getFirst();
    assertEquals(StorageCleanupTask.Status.RETRY,task.getStatus()); assertEquals(1,task.getAttempts());
    assertTrue(task.getLastError().contains("temporarily offline")); assertTrue(sizes.containsKey(key));
    ReflectionTestUtils.setField(task,"nextAttemptAt",LocalDateTime.now(Clock.systemUTC()).minusSeconds(1));
    cleanupTasks.saveAndFlush(task);
    cleanupWorker.cleanup();
    assertEquals(StorageCleanupTask.Status.SUCCEEDED,cleanupTasks.findById(task.getId()).orElseThrow().getStatus());
    assertFalse(sizes.containsKey(key)); verify(storage,times(2)).delete(key);
  }

  @Test void recursiveCopyPreservesHiddenFlagsButUsesIndependentContentAndQuota() throws Exception {
    long parent=folder("source",null); long child=folder("child",parent);
    long file=upload("hello.txt","hello".getBytes(),child,201).path("id").asLong();
    long tasksBeforeCopy=indexTasks.count();
    call(postJson("/api/files/hidden",Map.of("ids",List.of(file),"value",true)),"owner",200);
    call(postJson("/api/files/favorite",Map.of("ids",List.of(file),"value",true)),"owner",200);
    JsonNode copied=call(postJson("/api/files/copy",target(List.of(parent,child),null)),"owner",201);
    assertEquals(tasksBeforeCopy,indexTasks.count(),"copies do not enter this indexing scope");
    assertEquals(3,copied.size()); assertEquals("source - 副本",copied.get(0).path("name").asText());
    JsonNode copiedFile=copied.get(2); assertTrue(copiedFile.path("hidden").asBoolean()); assertFalse(copiedFile.path("favorite").asBoolean());
    assertEquals(10,usage().path("usedBytes").asLong());
    call(postJson("/api/files/copy",target(List.of(parent),child)),"owner",400);
    call(postJson("/api/files/trash",Map.of("ids",List.of(parent))),"owner",200);
    call(post("/api/files/trash/delete").contentType("application/json").content(json.writeValueAsString(Map.of("ids",List.of(parent)))),"owner",204);
    cleanupWorker.cleanup();
    download(copiedFile.path("id").asText(),"hello".getBytes()); assertEquals(5,usage().path("usedBytes").asLong());
    JsonNode details=call(get("/api/files/"+copied.get(0).path("id").asText()+"/details"),"owner",200);
    assertEquals(5,details.path("contentSize").asLong()); assertEquals(1,details.path("fileCount").asLong());
    assertEquals(1,details.path("folderCount").asLong()); assertFalse(details.toString().contains("storageKey"));
  }

  @Test void batchMoveConflictRollsBackEveryEarlierMove() throws Exception {
    long source=folder("source",null); long target=folder("target",null);
    long first=upload("first.txt",new byte[1],source,201).path("id").asLong();
    long second=upload("same.txt",new byte[1],source,201).path("id").asLong();
    upload("same.txt",new byte[1],target,201);
    JsonNode error=call(postJson("/api/files/move",target(List.of(first,second),target)),"owner",409);
    assertEquals("NAME_CONFLICT",error.path("code").asText());
    assertEquals(source,files.findById(first).orElseThrow().getParentId()); assertEquals(source,files.findById(second).orElseThrow().getParentId());
  }

  @Test void ruleOrganizationIsDeterministicIdempotentAndNeverOverwrites() throws Exception {
    List<Long> selected=new ArrayList<>();
    for (String name:List.of("paper.PDF","photo.png","music.mp3","archive.zip")) selected.add(upload(name,new byte[1],null,201).path("id").asLong());
    call(postJson("/api/files/organize",Map.of("ids",selected)),"owner",200);
    assertEquals(Set.of("文档","图片","音频","其他"),new HashSet<>(files.findAllByOwnerId(owner.getId()).stream().filter(DriveFile::isFolder).map(DriveFile::getName).toList()));
    assertEquals(8,files.count()); call(postJson("/api/files/organize",Map.of("ids",selected)),"owner",200); assertEquals(8,files.count());
    long conflict=upload("paper.PDF",new byte[1],null,201).path("id").asLong();
    assertEquals("NAME_CONFLICT",call(postJson("/api/files/organize",Map.of("ids",List.of(conflict))),"owner",409).path("code").asText());
    assertNull(files.findById(conflict).orElseThrow().getParentId()); assertEquals(5,usage().path("usedBytes").asLong());
  }

  @Test void concurrentUploadsCannotExceedQuota() throws Exception {
    limit(10); CountDownLatch start=new CountDownLatch(1);
    try (ExecutorService pool=Executors.newFixedThreadPool(2)) {
      List<Future<String>> futures=new ArrayList<>();
      for (String name:List.of("one.bin","two.bin")) futures.add(pool.submit(() -> {
        start.await();
        try { drive.upload(owner,new MockMultipartFile("file",name,"application/octet-stream",new byte[7]),null); return "OK"; }
        catch (DriveFailure e) { return e.code(); }
      }));
      start.countDown(); List<String> results=new ArrayList<>();
      for (Future<String> future:futures) results.add(future.get(20,TimeUnit.SECONDS));
      assertEquals(Set.of("OK","QUOTA_EXCEEDED"),new HashSet<>(results));
    }
    assertEquals(1,files.count()); assertEquals(7,users.findById(owner.getId()).orElseThrow().getStorageUsed()); assertEquals(1,sizes.size());
  }

  private JsonNode call(MockHttpServletRequestBuilder request,String token,int expected) throws Exception {
    String body=mvc.perform(request.header("Authorization","Bearer "+token)).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    return body.isBlank()?json.nullNode():json.readTree(body);
  }
  private MockHttpServletRequestBuilder postJson(String endpoint,Object body) throws Exception {
    return post(endpoint).contentType("application/json").content(json.writeValueAsString(body));
  }
  private JsonNode init(String token,String name,long size,String hash,Long parent,int chunkSize,int count,int status) throws Exception {
    return call(postJson("/api/upload/init",initBody(name,size,hash,parent,chunkSize,count)),token,status);
  }
  private Map<String,Object> initBody(String name,long size,String hash,Long parent,int chunkSize,int count) {
    Map<String,Object> body=new HashMap<>(); body.put("fileName",name); body.put("fileSize",size); body.put("fileHash",hash);
    body.put("parentId",parent); body.put("chunkSize",chunkSize); body.put("totalChunks",count); return body;
  }
  private JsonNode merge(String token,String id,String hash,int status) throws Exception { return call(postJson("/api/upload/merge",Map.of("uploadId",id,"fileHash",hash)),token,status); }
  private void chunk(String token,String id,int number,int count,String hash,byte[] bytes,int expected) throws Exception {
    mvc.perform(multipart("/api/upload/chunk").file(new MockMultipartFile("chunk","part.bin","application/octet-stream",bytes))
        .param("uploadId",id).param("chunkNumber",String.valueOf(number)).param("totalChunks",String.valueOf(count)).param("fileHash",hash)
        .header("Authorization","Bearer "+token)).andExpect(status().is(expected));
  }
  private JsonNode upload(String name,byte[] bytes,Long parent,int expected) throws Exception {
    var request=multipart("/api/files/upload").file(new MockMultipartFile("file",name,"application/octet-stream",bytes));
    if (parent!=null) request.param("parentId",parent.toString());
    return call(request,"owner",expected);
  }
  private long folder(String name,Long parent) throws Exception {
    Map<String,Object> body=new HashMap<>(); body.put("name",name); body.put("parentId",parent);
    return call(postJson("/api/files/folder",body),"owner",201).path("id").asLong();
  }
  private Map<String,Object> target(List<Long> ids,Long parent) { Map<String,Object> body=new HashMap<>(); body.put("ids",ids); body.put("parentId",parent); return body; }
  private JsonNode usage() throws Exception { return call(get("/api/files/usage"),"owner",200); }
  private void limit(long bytes) { ReflectionTestUtils.setField(owner,"storageLimit",bytes); users.saveAndFlush(owner); }
  private void download(String id,byte[] bytes) throws Exception {
    MvcResult result=mvc.perform(get("/api/files/"+id+"/download").header("Authorization","Bearer owner"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
    mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().bytes(bytes));
  }
  private static String md5(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)); }
  private static Path staging() { try { return Files.createTempDirectory("cendo-upload-test-"); } catch (IOException e) { throw new UncheckedIOException(e); } }
  @AfterAll static void cleanup() throws IOException { try (var paths=Files.walk(STAGING)) { for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
}
