package com.cendodrive.ai;

import com.cendodrive.auth.AuthService;
import com.cendodrive.config.CorsConfig;
import com.cendodrive.config.SecurityConfig;
import com.cendodrive.ai.AiChatDtos.*;
import com.cendodrive.ai.AiChatService.AiFailure;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AiChatController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class AiChatSecurityTest {
  @Autowired MockMvc mvc;
  @MockBean AuthService auth;
  @MockBean AiChatService ai;

  @Test void anonymousCannotProbeConfigurationOrChat() throws Exception {
    mvc.perform(get("/api/ai/chat/status")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(ai);
  }

  @Test void statusContainsNoKeyAndChatIsNotCached() throws Exception {
    when(auth.authenticate("token")).thenReturn(mock(User.class));
    when(ai.status()).thenReturn(new StatusResponse(true, "test-model"));
    mvc.perform(get("/api/ai/chat/status").header("Authorization", "Bearer token"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.key").doesNotExist())
        .andExpect(jsonPath("$.baseUrl").doesNotExist());
    when(ai.chat(any())).thenReturn(new ChatResponse("你好"));
    mvc.perform(post("/api/ai/chat").header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON)
        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.content").value("你好"));
  }

  @Test void invalidPayloadsAreRejectedBeforeAnyProviderCall() throws Exception {
    when(auth.authenticate("token")).thenReturn(mock(User.class));
    for (String body : java.util.List.of("{}", "{\"messages\":[]}", "{\"messages\":[null]}",
        "{\"messages\":[{\"role\":\"system\",\"content\":\"hi\"}]}",
        "{\"messages\":[{\"role\":\"user\",\"content\":\" \"}]}",
        "{\"messages\":[{\"content\":\"hi\"}]}",
        "{\"messages\":[{\"role\":\"user\",\"content\":\"" + "a".repeat(8001) + "\"}]}"))
      mvc.perform(post("/api/ai/chat").header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON)
          .content(body)).andExpect(status().isBadRequest());
    verifyNoInteractions(ai);
  }

  @Test void unconfiguredReturnsActionable503() throws Exception {
    when(auth.authenticate("token")).thenReturn(mock(User.class));
    when(ai.chat(any())).thenThrow(new AiFailure(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "智能对话尚未配置"));
    mvc.perform(post("/api/ai/chat").header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON)
        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_NOT_CONFIGURED"));
  }
}
