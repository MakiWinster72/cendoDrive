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

@WebMvcTest(ShareController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class ShareSecurityTest {
  @Autowired MockMvc mvc;
  @MockBean AuthService auth;
  @MockBean ShareService shares;
  static final String TOKEN = "a".repeat(32);
  static final FileResponse FILE = new FileResponse("42", "你好.txt", "file", 5, null,
      "2026-10-01T12:00:00Z", null);

  @Test void anonymousCanReadAndDownloadButCannotManageOrSave() throws Exception {
    when(shares.get(TOKEN)).thenReturn(new ShareDtos.ShareAccessResponse(FILE, "2026-10-02T12:00:00Z"));
    mvc.perform(get("/api/shares/" + TOKEN)).andExpect(status().isOk())
        .andExpect(jsonPath("$.file.name").value("你好.txt"))
        .andExpect(header().string("Cache-Control", "no-store"));
    when(shares.download(TOKEN)).thenReturn(new DriveService.Download("你好.txt", out -> out.write("hello".getBytes()), 5));
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
    when(shares.save(recipient, TOKEN, new ShareDtos.SaveShareRequest(12L))).thenReturn(FILE);
    mvc.perform(post("/api/shares/" + TOKEN + "/save").header("Authorization", "Bearer recipient-token")
        .contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":12}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("42"));
    verify(shares).save(recipient, TOKEN, new ShareDtos.SaveShareRequest(12L));
  }

  @Test void authenticatedListIsBoundToBearerPrincipal() throws Exception {
    User owner = mock(User.class);
    when(auth.authenticate("owner-token")).thenReturn(owner);
    when(shares.list(owner)).thenReturn(List.of());
    mvc.perform(get("/api/shares").header("Authorization", "Bearer owner-token"))
        .andExpect(status().isOk()).andExpect(content().json("[]"));
    verify(shares).list(owner);
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
