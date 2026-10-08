package com.cendodrive.config;

import com.cendodrive.auth.AuthService;
import com.cendodrive.common.ApiExceptionHandler.AuthFailure;
import com.cendodrive.drive.DriveController;
import com.cendodrive.drive.DriveService;
import com.cendodrive.user.User;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DriveController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class DownloadSecurityTest {
  @Autowired MockMvc mvc;
  @MockBean AuthService auth;
  @MockBean DriveService drive;

  @Test void anonymousDownloadIsRejectedBeforeStorageAccess() throws Exception {
    mvc.perform(get("/api/files/1/download")).andExpect(status().isUnauthorized());
    verifyNoInteractions(drive);
  }

  @Test void invalidTokenIsRejectedBeforeStorageAccess() throws Exception {
    when(auth.authenticate("invalid")).thenThrow(new AuthFailure(HttpStatus.UNAUTHORIZED, "Invalid token"));
    mvc.perform(get("/api/files/1/download").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(drive);
  }

  @Test void authorizedDownloadCompletesAsyncDispatchWithoutCorruptingBytes() throws Exception {
    User user = mock(User.class);
    byte[] bytes = "预览内容\n".getBytes(StandardCharsets.UTF_8);
    when(auth.authenticate("valid")).thenReturn(user);
    when(drive.download(user, 1L)).thenReturn(new DriveService.Download("preview.txt", output -> output.write(bytes), bytes.length));
    var result = mvc.perform(get("/api/files/1/download").header("Authorization", "Bearer valid"))
        .andExpect(request().asyncStarted()).andReturn();
    mvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().bytes(bytes))
        .andExpect(header().longValue("Content-Length", bytes.length));
    verify(drive).download(user, 1L);
  }
}
