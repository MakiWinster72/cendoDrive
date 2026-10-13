package com.cendodrive.config;

import com.cendodrive.auth.AuthService;
import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.upload.UploadController;
import com.cendodrive.upload.UploadService;
import com.cendodrive.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UploadController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class UploadSecurityTest {
  @Autowired MockMvc mvc;
  @MockBean AuthService auth;
  @MockBean UploadService uploads;

  @Test void duplicateNameReturnsBusinessConflictAndTokenRemainsUsable() throws Exception {
    User user = mock(User.class);
    when(auth.authenticate("valid")).thenReturn(user);
    when(uploads.init(eq(user), any())).thenThrow(new DriveFailure(HttpStatus.CONFLICT, "NAME_CONFLICT", "Already exists"));
    for (int attempt = 0; attempt < 2; attempt++) {
      mvc.perform(post("/api/upload/init").header("Authorization", "Bearer valid")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"fileName\":\"same.txt\",\"fileSize\":8,\"fileHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"chunkSize\":8,\"totalChunks\":1}"))
          .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_CONFLICT"));
    }
  }
}
