package com.cendodrive.integration;

import com.cendodrive.auth.AuthService;
import com.cendodrive.drive.DriveFileRepository;
import com.cendodrive.storage.FileStorage;
import com.cendodrive.user.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import static org.junit.jupiter.api.Assertions.*;

/** Real sockets and real infrastructure; never point this at the user's database. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=${CENDO_ACCEPTANCE_DB_URL}",
    "spring.datasource.username=${CENDO_ACCEPTANCE_DB_USER}",
    "spring.datasource.password=${CENDO_ACCEPTANCE_DB_PASSWORD}",
    "cendo.storage.backend=fastdfs"})
@EnabledIfEnvironmentVariable(named="RUN_REAL_STACK_TESTS",matches="true")
class RealStackAcceptanceTest {
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository users;
  @Autowired UserDeletionCleanup cleanup;
  @Autowired DriveFileRepository files;
  @Autowired FileStorage storage;
  @Autowired StringRedisTemplate redis;
  @Autowired AuthService auth;
  final List<Long> ids=new ArrayList<>();
  final List<String> sessions=new ArrayList<>(), shares=new ArrayList<>();
  static final String PASSWORD="acceptance-password123";
  static final String NEW_PASSWORD="acceptance-new-password123";
  static final byte[] CONTENT="真实 FastDFS 分片上传与独立转存验收\n".getBytes(StandardCharsets.UTF_8);

  @BeforeEach void requireIsolatedMySql() {
    assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("cendo_acceptance_"));
    assertEquals("MySQL",jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
        connection -> connection.getMetaData().getDatabaseProductName()));
  }
  HttpHeaders headers(String bearer,String code) {
    HttpHeaders h=new HttpHeaders();
    if (bearer!=null) h.setBearerAuth(bearer);
    if (code!=null) h.set("X-Share-Code",code);
    return h;
  }
  ResponseEntity<JsonNode> call(HttpMethod method,String url,String bearer,Object body,String code) {
    return http.exchange(url,method,new HttpEntity<>(body,headers(bearer,code)),JsonNode.class);
  }
  JsonNode ok(HttpMethod method,String url,String bearer,Object body,int status,String code) {
    var response=call(method,url,bearer,body,code);
    assertEquals(status,response.getStatusCode().value(),url+" "+response.getBody()); return response.getBody();
  }
  JsonNode login(String name,String password) {
    JsonNode result=ok(HttpMethod.POST,"/api/auth/login",null,Map.of("username",name,"password",password),200,null);
    sessions.add(result.get("token").asText()); return result;
  }
  JsonNode register() {
    String username="real_"+UUID.randomUUID().toString().replace("-","");
    JsonNode user=ok(HttpMethod.POST,"/api/auth/register",null,Map.of("username",username,"password",PASSWORD),201,null);
    ids.add(user.get("id").asLong()); return login(username,PASSWORD);
  }
  JsonNode multipart(HttpMethod method,String url,String bearer,LinkedMultiValueMap<String,Object> body,int status) {
    HttpHeaders h=headers(bearer,null); h.setContentType(MediaType.MULTIPART_FORM_DATA);
    var response=http.exchange(url,method,new HttpEntity<>(body,h),JsonNode.class);
    assertEquals(status,response.getStatusCode().value(),url+" "+response.getBody()); return response.getBody();
  }
  ByteArrayResource resource(byte[] bytes,String name) {
    return new ByteArrayResource(bytes) { @Override public String getFilename() { return name; } };
  }
  JsonNode share(String bearer,String file,int seconds,String code) {
    Map<String,Object> body=new HashMap<>(Map.of("fileId",file,"expiresInSeconds",seconds));
    if (code!=null) body.put("extractionCode",code);
    JsonNode result=ok(HttpMethod.POST,"/api/shares",bearer,body,201,null);
    shares.add(result.get("token").asText()); assertEquals(code!=null,result.get("hasExtractionCode").asBoolean());
    assertNull(result.get("extractionCode")); assertNull(result.get("extractionCodeHash")); return result;
  }
  byte[] download(String url,String bearer,String code) {
    var response=http.exchange(url,HttpMethod.GET,new HttpEntity<>(headers(bearer,code)),byte[].class);
    assertEquals(200,response.getStatusCode().value()); return response.getBody();
  }
  void status(HttpMethod method,String url,String bearer,Object body,String code,int status) {
    assertEquals(status,call(method,url,bearer,body,code).getStatusCode().value(),url);
  }
  @AfterEach void removeOnlyGeneratedResources() {
    // Purge commits each physical deletion; retain metadata on failure instead of dropping its DB.
    for (Long id:ids) users.findById(id).ifPresent(user -> {
      user.markDeleted(LocalDateTime.now(ZoneOffset.UTC).minusDays(8)); users.saveAndFlush(user); cleanup.purge(id);
    });
    sessions.forEach(auth::logout);
    for (String token:shares) {
      redis.delete("share:access:"+token);
      for (String client:List.of("127.0.0.1","0:0:0:0:0:0:0:1","::1")) {
        String scope="share-code:"+token+":"+client;
        try { redis.delete("login:fail:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8)))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
      }
    }
    for (Long id:ids) assertFalse(users.existsById(id),"Generated account was not purged");
  }

  @Test void accountLifecycleAndOptionalSharesAcrossRealHttpMySqlRedisFastDfs() throws Exception {
    JsonNode alice=register(),bob=register(); String a=alice.get("token").asText(),b=bob.get("token").asText();
    Long aliceId=alice.get("user").get("id").asLong(); String username=alice.get("user").get("username").asText();
    String second=login(username,PASSWORD).get("token").asText();
    JsonNode folder=ok(HttpMethod.POST,"/api/files/folder",a,Map.of("name","联调目录"),201,null);
    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(CONTENT));
    JsonNode init=ok(HttpMethod.POST,"/api/upload/init",a,Map.of("fileName","真实联调.txt","fileSize",CONTENT.length,
        "fileHash",hash,"parentId",folder.get("id").asText(),"chunkSize",CONTENT.length,"totalChunks",1),201,null);
    String upload=init.get("uploadId").asText();
    status(HttpMethod.GET,"/api/upload/"+upload+"/status",b,null,null,404);
    var chunk=new LinkedMultiValueMap<String,Object>(); chunk.add("uploadId",upload); chunk.add("chunkNumber","0");
    chunk.add("totalChunks","1"); chunk.add("fileHash",hash); chunk.add("chunk",resource(CONTENT,"chunk.bin"));
    multipart(HttpMethod.POST,"/api/upload/chunk",a,chunk,200);
    assertEquals(0,ok(HttpMethod.GET,"/api/upload/"+upload+"/status",a,null,200,null).get("uploadedChunks").get(0).asInt());
    JsonNode file=ok(HttpMethod.POST,"/api/upload/merge",a,Map.of("uploadId",upload,"fileHash",hash),201,null);
    String id=file.get("id").asText(),privateUrl="/api/files/"+id+"/download";
    assertArrayEquals(CONTENT,download(privateUrl,a,null)); status(HttpMethod.GET,privateUrl,b,null,null,404);
    var original=files.findById(file.get("id").asLong()).orElseThrow(); assertEquals("fastdfs",original.getStorageBackend());
    assertEquals(CONTENT.length,ok(HttpMethod.GET,"/api/files/usage",a,null,200,null).get("usedBytes").asLong());
    assertEquals(0,ok(HttpMethod.GET,"/api/files/usage",a,null,200,null).get("reservedBytes").asLong());
    assertEquals("验收昵称",ok(HttpMethod.PUT,"/api/user/me/profile",a,Map.of("nickname","验收昵称"),200,null).get("nickname").asText());
    ByteArrayOutputStream image=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(8,4,BufferedImage.TYPE_INT_RGB),"png",image);
    var avatar=new LinkedMultiValueMap<String,Object>(); avatar.add("file",resource(image.toByteArray(),"avatar.png"));
    multipart(HttpMethod.PUT,"/api/user/me/avatar",a,avatar,204);
    BufferedImage normalized=ImageIO.read(new ByteArrayInputStream(download("/api/user/me/avatar",a,null)));
    assertEquals(256,normalized.getWidth()); assertEquals(256,normalized.getHeight());
    assertEquals(CONTENT.length,ok(HttpMethod.GET,"/api/files/usage",a,null,200,null).get("usedBytes").asLong());
    JsonNode open=share(a,id,604800,null),protectedShare=share(a,id,86400,"A1b2");
    String protectedUrl="/api/shares/"+protectedShare.get("token").asText();
    assertArrayEquals(CONTENT,download("/api/shares/"+open.get("token").asText()+"/download",null,null));
    assertNull(ok(HttpMethod.GET,"/api/shares/"+open.get("token").asText(),null,null,200,null).get("file").get("parentId").textValue());
    for (String path:List.of("","/download")) status(HttpMethod.GET,protectedUrl+path,null,null,null,403);
    status(HttpMethod.POST,protectedUrl+"/save",b,Map.of(),null,403);
    assertEquals("SHARE_CODE_INVALID",call(HttpMethod.GET,protectedUrl,null,null,"bad1").getBody().get("code").asText());
    ok(HttpMethod.GET,protectedUrl,null,null,200,"A1b2"); assertArrayEquals(CONTENT,download(protectedUrl+"/download",null,"A1b2"));
    JsonNode copy=ok(HttpMethod.POST,protectedUrl+"/save",b,Map.of(),201,"A1b2");
    assertNotEquals(original.getStorageKey(),files.findById(copy.get("id").asLong()).orElseThrow().getStorageKey());
    String copyUrl="/api/files/"+copy.get("id").asText()+"/download"; assertArrayEquals(CONTENT,download(copyUrl,b,null));
    status(HttpMethod.DELETE,"/api/shares/"+protectedShare.get("id").asText(),b,null,null,404);
    status(HttpMethod.DELETE,"/api/shares/"+protectedShare.get("id").asText(),a,null,null,204);
    for (String path:List.of("","/download")) status(HttpMethod.GET,protectedUrl+path,null,null,"A1b2",404);
    status(HttpMethod.POST,protectedUrl+"/save",b,Map.of(),"A1b2",404);
    JsonNode limited=share(a,id,60,"X9y8"); String limitUrl="/api/shares/"+limited.get("token").asText();
    for (int i=0;i<4;i++) status(HttpMethod.GET,limitUrl,null,null,"bad1",403);
    var rejected=call(HttpMethod.GET,limitUrl,null,null,"bad1"); assertEquals(429,rejected.getStatusCode().value());
    assertEquals("900",rejected.getHeaders().getFirst("Retry-After")); status(HttpMethod.GET,limitUrl,null,null,"X9y8",429);
    JsonNode expiring=share(a,id,1,"T1t2"); Thread.sleep(1100);
    status(HttpMethod.GET,"/api/shares/"+expiring.get("token").asText(),null,null,"T1t2",404);
    status(HttpMethod.POST,"/api/user/me/password",a,Map.of("currentPassword",PASSWORD,"newPassword",NEW_PASSWORD),null,204);
    for (String stale:List.of(a,second)) status(HttpMethod.GET,"/api/user/me",stale,null,null,401);
    a=login(username,NEW_PASSWORD).get("token").asText();
    status(HttpMethod.POST,"/api/user/me/deletion",a,Map.of("password",NEW_PASSWORD,"confirmation","确定"),null,400);
    JsonNode deletion=ok(HttpMethod.POST,"/api/user/me/deletion",a,Map.of("password",NEW_PASSWORD,"confirmation","注销账号"),200,null);
    assertEquals(Duration.ofDays(7),Duration.between(Instant.parse(deletion.get("deletedAt").asText()),Instant.parse(deletion.get("purgeAfter").asText())));
    status(HttpMethod.GET,"/api/user/me",a,null,null,401);
    status(HttpMethod.GET,"/api/shares/"+open.get("token").asText(),null,null,null,404);
    status(HttpMethod.POST,"/api/auth/restore",null,Map.of("username",username,"password",NEW_PASSWORD),null,204);
    a=login(username,NEW_PASSWORD).get("token").asText();
    JsonNode recovered=ok(HttpMethod.GET,"/api/user/me/profile",a,null,200,null);
    assertEquals("验收昵称",recovered.get("nickname").asText()); assertTrue(recovered.get("hasAvatar").asBoolean());
    assertArrayEquals(CONTENT,download(privateUrl,a,null));
    status(HttpMethod.GET,"/api/shares/"+open.get("token").asText(),null,null,null,404);
    status(HttpMethod.POST,"/api/user/me/deletion",a,Map.of("password",NEW_PASSWORD,"confirmation","注销账号"),null,200);
    // Age only the generated account in the disposable DB to exercise the actual seven-day purge.
    jdbc.update("UPDATE users SET deleted_at=? WHERE id=?",LocalDateTime.now(ZoneOffset.UTC).minusDays(8),aliceId);
    cleanup.purge(aliceId); assertFalse(users.existsById(aliceId));
    assertThrows(IOException.class,() -> storage.download(original.getStorageKey(),new ByteArrayOutputStream()));
    assertArrayEquals(CONTENT,download(copyUrl,b,null));
  }
}
