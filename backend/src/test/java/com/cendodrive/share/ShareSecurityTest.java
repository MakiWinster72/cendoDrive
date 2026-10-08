package com.cendodrive.share;

import com.cendodrive.auth.AuthService;
import com.cendodrive.config.*;
import com.cendodrive.drive.DriveDtos.FileResponse;
import com.cendodrive.drive.DriveService;
import com.cendodrive.user.User;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ShareController.class, com.cendodrive.drive.DriveController.class})
@Import({SecurityConfig.class, CorsConfig.class})
class ShareSecurityTest {
  @Autowired MockMvc mvc;
  @MockBean AuthService auth;
  @MockBean ShareService shares;
  @MockBean DriveService drive;
  static final String TOKEN = "a".repeat(32);
  static final FileResponse FILE = new FileResponse("42", "你好.txt", "file", 5, null,
      "2026-10-01T12:00:00Z", null);

  @Test void anonymousCanReadAndDownloadButCannotManageOrSave() throws Exception {
    when(shares.get(TOKEN, null, "127.0.0.1")).thenReturn(new ShareDtos.ShareAccessResponse(FILE, "2026-10-02T12:00:00Z"));
    mvc.perform(get("/api/shares/" + TOKEN)).andExpect(status().isOk())
        .andExpect(jsonPath("$.file.name").value("你好.txt"))
        .andExpect(header().string("Cache-Control", "no-store"));
    when(shares.download(TOKEN, null, "127.0.0.1")).thenReturn(new DriveService.Download("你好.txt", out -> out.write("hello".getBytes()), 5));
    var download = mvc.perform(get("/api/shares/" + TOKEN + "/download"))
        .andExpect(request().asyncStarted()).andReturn();
    mvc.perform(asyncDispatch(download)).andExpect(status().isOk()).andExpect(content().string("hello"))
        .andExpect(header().string("Content-Disposition", "attachment; filename*=UTF-8''%E4%BD%A0%E5%A5%BD.txt"));
    mvc.perform(get("/api/shares")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/shares").contentType(MediaType.APPLICATION_JSON)
        .content("{\"fileId\":42,\"expiresInSeconds\":60}")).andExpect(status().isUnauthorized());
    mvc.perform(delete("/api/shares/51")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/shares/" + TOKEN + "/save").contentType(MediaType.APPLICATION_JSON)
        .content("{}")).andExpect(status().isUnauthorized());
  }

  @Test void authenticatedSaveUsesBearerRecipientAndDestination() throws Exception {
    User recipient = mock(User.class);
    when(auth.authenticate("recipient-token")).thenReturn(recipient);
    when(shares.save(recipient, TOKEN, new ShareDtos.SaveShareRequest(12L), null, "127.0.0.1")).thenReturn(FILE);
    mvc.perform(post("/api/shares/" + TOKEN + "/save").header("Authorization", "Bearer recipient-token")
        .contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":12}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("42"));
    verify(shares).save(recipient, TOKEN, new ShareDtos.SaveShareRequest(12L), null, "127.0.0.1");
  }

  @Test void extractionCodeIsForwardedOnMetadataDownloadAndSave() throws Exception {
    when(shares.get(TOKEN, "A1b2", "127.0.0.1")).thenReturn(new ShareDtos.ShareAccessResponse(FILE, "2026-10-02T12:00:00Z"));
    mvc.perform(get("/api/shares/" + TOKEN).header("X-Share-Code", "A1b2")).andExpect(status().isOk());
    when(shares.download(TOKEN, "A1b2", "127.0.0.1")).thenReturn(new DriveService.Download("hello.txt", out -> out.write("hello".getBytes()), 5));
    var stream = mvc.perform(get("/api/shares/" + TOKEN + "/download").header("X-Share-Code", "A1b2")).andReturn();
    mvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
    User user = mock(User.class); when(auth.authenticate("owner-token")).thenReturn(user);
    when(shares.save(user, TOKEN, new ShareDtos.SaveShareRequest(null), "A1b2", "127.0.0.1")).thenReturn(FILE);
    mvc.perform(post("/api/shares/" + TOKEN + "/save").header("Authorization", "Bearer owner-token").header("X-Share-Code", "A1b2")
        .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isCreated());
    verify(shares).save(user, TOKEN, new ShareDtos.SaveShareRequest(null), "A1b2", "127.0.0.1");
  }

  @Test void authenticatedListIsBoundToBearerPrincipal() throws Exception {
    User owner = mock(User.class);
    when(auth.authenticate("owner-token")).thenReturn(owner);
    when(shares.list(owner)).thenReturn(List.of());
    mvc.perform(get("/api/shares").header("Authorization", "Bearer owner-token"))
        .andExpect(status().isOk()).andExpect(content().json("[]"));
    verify(shares).list(owner);
  }

  @Test void authenticatedFileDownloadCompletesAsyncButAnonymousRequestIsDenied() throws Exception {
    mvc.perform(get("/api/files/42/download")).andExpect(status().isUnauthorized());
    User recipient = mock(User.class);
    when(auth.authenticate("recipient-token")).thenReturn(recipient);
    when(drive.download(recipient, 42L)).thenReturn(new DriveService.Download("hello.txt", out -> out.write("hello".getBytes()), 5));
    var result = mvc.perform(get("/api/files/42/download").header("Authorization", "Bearer recipient-token"))
        .andExpect(request().asyncStarted()).andReturn();
    mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().string("hello"));
  }

  @Test void rejectsInvalidExpiryAndDestinationBeforeService() throws Exception {
    when(auth.authenticate("owner-token")).thenReturn(mock(User.class));
    for (String input : List.of("{}", "{\"fileId\":42,\"expiresInSeconds\":0}",
        "{\"fileId\":42,\"expiresInSeconds\":2592001}"))
      mvc.perform(post("/api/shares").header("Authorization", "Bearer owner-token")
          .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isBadRequest());
    mvc.perform(post("/api/shares/" + TOKEN + "/save").header("Authorization", "Bearer owner-token")
        .contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":-1}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(shares);
  }
}
