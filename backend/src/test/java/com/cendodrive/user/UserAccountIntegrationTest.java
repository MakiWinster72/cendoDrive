package com.cendodrive.user;

import com.cendodrive.auth.*;
import com.cendodrive.auth.AuthDtos.*;
import com.cendodrive.common.ApiExceptionHandler.*;
import com.cendodrive.drive.*;
import com.cendodrive.share.ShareLinkRepository;
import com.cendodrive.storage.FileStorage;
import com.cendodrive.upload.*;
import com.cendodrive.user.UserDtos.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:accounts;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true", "cendo.user.cleanup-delay-ms=86400000", "cendo.upload.cleanup-delay-ms=86400000"})
@AutoConfigureMockMvc
class UserAccountIntegrationTest {
  @TempDir static Path staging;
  @DynamicPropertySource static void config(DynamicPropertyRegistry properties) {
    properties.add("cendo.upload.root",()->staging.toString());
  }
  @Autowired UserRepository users;
  @Autowired UserAvatarRepository avatars;
  @Autowired DriveFileRepository files;
  @Autowired UploadSessionRepository uploads;
  @Autowired ShareLinkRepository shares;
  @Autowired UserService service;
  @Autowired UserDeletionCleanup cleanup;
  @Autowired AuthService auth;
  @Autowired PasswordEncoder encoder;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @MockBean Clock clock;
  @MockBean StringRedisTemplate redis;
  @MockBean LoginRateLimiter limiter;
  @MockBean FileStorage storage;
  User owner,other;
  String token;
  static final Instant NOW=Instant.parse("2026-10-01T12:00:00Z");
  @BeforeEach void setup() {
    shares.deleteAllInBatch(); uploads.deleteAllInBatch(); avatars.deleteAllInBatch(); files.deleteAllInBatch(); users.deleteAllInBatch();
    when(clock.getZone()).thenReturn(ZoneOffset.UTC); at(NOW);
    ValueOperations<String,String> values=mock(ValueOperations.class);
    Map<String,String> sessions=new HashMap<>();
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString())).thenAnswer(call->sessions.get(call.getArgument(0)));
    doAnswer(call->{sessions.put(call.getArgument(0),call.getArgument(1));return null;})
        .when(values).set(anyString(),anyString(),any(Duration.class));
    owner=users.saveAndFlush(new User("maki",encoder.encode("password123"),"原昵称"));
    other=users.saveAndFlush(new User("other",encoder.encode("other-password"),"其他用户"));
    token=auth.login(new LoginRequest("maki","password123")).token();
  }
  void at(Instant time) { when(clock.instant()).thenReturn(time); }
  User fresh() { return users.findById(owner.getId()).orElseThrow(); }
  MockMultipartFile image(int width,int height,String format) throws Exception {
    var out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),format,out);
    return new MockMultipartFile("file","avatar."+format,"image/"+format,out.toByteArray());
  }
  void mark() { service.deleteAccount(fresh(),new DeleteRequest("password123","注销账号")); }
  @Test void profileAndExactLookupExposeOnlyMinimalIdentity() throws Exception {
    mvc.perform(put("/api/user/me/profile").header("Authorization","Bearer "+token).contentType("application/json")
        .content("{\"nickname\":\"  新昵称  \"}")).andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("新昵称"));
    assertEquals("新昵称",fresh().getNickname());
    mvc.perform(get("/api/users/lookup").header("Authorization","Bearer "+token).param("username"," OTHER "))
        .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(other.getId().toString()))
        .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.storageUsed").doesNotExist())
        .andExpect(jsonPath("$.createdAt").doesNotExist());
    mvc.perform(get("/api/users/lookup").header("Authorization","Bearer "+token).param("username","oth"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/users/lookup").param("username","maki")).andExpect(status().isUnauthorized());
    mvc.perform(put("/api/user/me/profile").header("Authorization","Bearer "+token).contentType("application/json")
        .content("{\"nickname\":\"   \"}")).andExpect(status().isBadRequest());
  }
  @Test void avatarIsNormalizedAuthenticatedAndRemoved() throws Exception {
    var request=multipart("/api/user/me/avatar").file(image(400,300,"png")); request.with(req->{req.setMethod("PUT");return req;});
    mvc.perform(request.header("Authorization","Bearer "+token)).andExpect(status().isNoContent());
    byte[] png=mvc.perform(get("/api/users/"+owner.getId()+"/avatar").header("Authorization","Bearer "+token))
        .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
        .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray();
    var decoded=ImageIO.read(new ByteArrayInputStream(png)); assertEquals(256,decoded.getWidth()); assertEquals(256,decoded.getHeight());
    assertTrue(service.profile(fresh()).hasAvatar());
    assertFalse(service.profile(other).hasAvatar());
    mvc.perform(get("/api/users/"+owner.getId()+"/avatar")).andExpect(status().isUnauthorized());
    service.updateAvatar(fresh(),image(300,200,"jpg"));
    service.deleteAvatar(fresh()); assertFalse(service.profile(fresh()).hasAvatar());
    assertThrows(DriveFailure.class,()->service.avatar(owner.getId()));
    verifyNoInteractions(storage);
  }
  @Test void rejectsOversizedMaliciousAndUnsupportedImages() throws Exception {
    assertThrows(DriveFailure.class,()->service.updateAvatar(fresh(),image(2049,1,"png")));
    assertThrows(DriveFailure.class,()->service.updateAvatar(fresh(),image(20,20,"gif")));
    assertThrows(DriveFailure.class,()->service.updateAvatar(fresh(),new MockMultipartFile("file","fake.png","image/png","<svg onload='alert(1)'/>".getBytes())));
    assertThrows(DriveFailure.class,()->service.updateAvatar(fresh(),new MockMultipartFile("file",new byte[2*1024*1024+1])));
    assertFalse(avatars.existsById(owner.getId()));
  }
  @Test void passwordChangeRevokesAllDevicesButNotOthers() throws Exception {
    String second=auth.login(new LoginRequest("maki","password123")).token();
    String otherToken=auth.login(new LoginRequest("other","other-password")).token();
    mvc.perform(post("/api/user/me/password").header("Authorization","Bearer "+token).contentType("application/json")
        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"new-password\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
    assertEquals(owner.getId(),auth.authenticate(token).getId());
    service.changePassword(fresh(),new PasswordRequest("password123","new-password"));
    assertThrows(AuthFailure.class,()->auth.authenticate(token)); assertThrows(AuthFailure.class,()->auth.authenticate(second));
    assertEquals(other.getId(),auth.authenticate(otherToken).getId());
    assertThrows(AuthFailure.class,()->auth.login(new LoginRequest("maki","password123")));
    assertEquals(owner.getId().toString(),auth.login(new LoginRequest("maki","new-password")).user().id());
  }
  @Test void deletionRequiresPasswordAndLiteralConfirmationWithoutDestroyingFiles() throws Exception {
    var f=files.saveAndFlush(DriveFile.uploaded(owner.getId(),null,"保留.txt",5,"keep"));
    mvc.perform(post("/api/user/me/deletion").header("Authorization","Bearer "+token).contentType("application/json")
        .content("{\"password\":\"password123\",\"confirmation\":\"确定\"}")).andExpect(status().isBadRequest());
    assertThrows(DriveFailure.class,()->service.deleteAccount(fresh(),new DeleteRequest("bad","注销账号")));
    var result=service.deleteAccount(fresh(),new DeleteRequest("password123","注销账号"));
    assertEquals(NOW.plus(Duration.ofDays(7)),result.purgeAfter().toInstant());
    assertFalse(fresh().isActive()); assertTrue(files.existsById(f.getId()));
    assertThrows(AuthFailure.class,()->auth.authenticate(token));
    assertThrows(DriveFailure.class,()->service.lookup("maki"));
    verifyNoInteractions(storage);
  }
  @Test void recoveryDoesNotReviveTokensOrSharesAndSevenDaysIsExclusive() {
    var f=files.saveAndFlush(DriveFile.uploaded(owner.getId(),null,"分享.txt",5,"share-key"));
    jdbc.update("INSERT INTO share_links(owner_id,file_id,token,file_name,size_bytes,created_at,expires_at,cancelled) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP,?,false)",
        owner.getId(),f.getId(),"a".repeat(32),"分享.txt",5,LocalDateTime.ofInstant(NOW.plus(Duration.ofDays(30)),ZoneOffset.UTC));
    mark();
    at(NOW.plus(Duration.ofDays(7)).minusNanos(1000));
    auth.restore(new LoginRequest("MAKI","password123"));
    assertTrue(fresh().isActive()); assertNull(fresh().getDeletedAt());
    assertThrows(AuthFailure.class,()->auth.authenticate(token));
    assertTrue(shares.findByToken("a".repeat(32)).orElseThrow().isCancelled());
    assertNotNull(auth.login(new LoginRequest("maki","password123")).token());
    at(NOW); mark(); at(NOW.plus(Duration.ofDays(7)));
    assertThrows(AuthFailure.class,()->auth.restore(new LoginRequest("maki","password123")));
  }
  @Test void sevenDayPurgeIncludesNestedHiddenTrashUploadsAvatarSharesAndKeepsOtherOwner() throws Exception {
    var parent=files.saveAndFlush(DriveFile.folder(owner.getId(),null,"目录"));
    var first=files.saveAndFlush(DriveFile.uploaded(owner.getId(),parent.getId(),"隐藏.txt",5,"first"));
    var second=files.saveAndFlush(DriveFile.uploaded(owner.getId(),parent.getId(),"回收.txt",5,"second"));
    jdbc.update("UPDATE drive_files SET hidden=true WHERE id=?",first.getId());
    jdbc.update("UPDATE drive_files SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",second.getId());
    var kept=files.saveAndFlush(DriveFile.uploaded(other.getId(),null,"转存副本.txt",5,"independent-copy"));
    uploads.saveAndFlush(new UploadSession("b".repeat(32),owner.getId(),null,"待上传.txt",10,"c".repeat(32),5,2,LocalDateTime.now().plusDays(30)));
    Path parts=staging.resolve(owner.getId().toString()).resolve("b".repeat(32)); Files.createDirectories(parts); Files.writeString(parts.resolve("0.part"),"part");
    service.updateAvatar(fresh(),image(200,200,"png"));
    mark(); at(NOW.plus(Duration.ofDays(7)).minusNanos(1000)); cleanup.cleanup();
    assertTrue(users.existsById(owner.getId())); verifyNoInteractions(storage);
    at(NOW.plus(Duration.ofDays(7))); cleanup.cleanup();
    assertFalse(users.existsById(owner.getId())); assertFalse(avatars.existsById(owner.getId()));
    assertTrue(files.findAllByOwnerId(owner.getId()).isEmpty()); assertEquals(0,uploads.count());
    assertFalse(Files.exists(parts)); assertTrue(files.existsById(kept.getId()));
    verify(storage).delete("first"); verify(storage).delete("second"); verify(storage,never()).delete("independent-copy");
    cleanup.cleanup(); verify(storage,times(1)).delete("first");
  }
  @Test void failedPhysicalCleanupRetainsPendingAccountAndRetriesWithoutLostKeys() throws Exception {
    var parent=files.saveAndFlush(DriveFile.folder(owner.getId(),null,"目录"));
    files.saveAndFlush(DriveFile.uploaded(owner.getId(),parent.getId(),"1.txt",5,"ok"));
    var failing=files.saveAndFlush(DriveFile.uploaded(owner.getId(),parent.getId(),"2.txt",5,"retry"));
    mark(); at(NOW.plus(Duration.ofDays(7)));
    doThrow(new IOException("storage offline")).doNothing().when(storage).delete("retry");
    cleanup.cleanup(); assertTrue(users.existsById(owner.getId())); assertTrue(files.existsById(parent.getId()));
    assertTrue(files.existsById(failing.getId())); assertEquals(2,files.findAllByOwnerId(owner.getId()).size());
    cleanup.cleanup(); assertFalse(users.existsById(owner.getId()));
    verify(storage,times(1)).delete("ok"); verify(storage,times(2)).delete("retry");
  }
  @Test void staleAndMissingSessionsFailClosed() {
    ValueOperations<String,String> values=redis.opsForValue();
    when(values.get(anyString())).thenReturn("999999:0"); assertThrows(AuthFailure.class,()->auth.authenticate("missing"));
    when(values.get(anyString())).thenReturn(owner.getId().toString()); assertNotNull(auth.authenticate("legacy"));
    service.changePassword(fresh(),new PasswordRequest("password123","new-password"));
    assertThrows(AuthFailure.class,()->auth.authenticate("legacy"));
    when(values.get(anyString())).thenReturn("1:0:extra"); assertThrows(AuthFailure.class,()->auth.authenticate("malformed"));
  }
}
