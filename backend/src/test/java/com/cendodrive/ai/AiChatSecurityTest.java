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
@Import({SecurityConfig.class, CorsConfig.class, AiChatExecutor.class})
class AiChatSecurityTest {
  @Autowired MockMvc mvc;
  @MockBean AuthService auth;
  @MockBean AiChatService ai;

  @Test void anonymousCannotProbeConfigurationOrChat() throws Exception {
    mvc.perform(get("/api/ai/chat/status")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/ai/chat/stream").contentType(MediaType.APPLICATION_JSON).content("{}"))
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
      for (String path:java.util.List.of("/api/ai/chat","/api/ai/chat/stream"))
        mvc.perform(post(path).header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON)
            .content(body)).andExpect(status().isBadRequest());
    verifyNoInteractions(ai);
  }

  @Test void authenticatedStreamFlushesEventsAndCompletesAsyncWithoutCaching() throws Exception {
    when(auth.authenticate("token")).thenReturn(mock(User.class));
    doAnswer(call -> {
      AiChatService.StreamSink sink=call.getArgument(1);
      sink.send("delta",java.util.Map.of("content","你好\n🙂")); sink.send("done",java.util.Map.of()); return null;
    }).when(ai).stream(any(),any());
    var result=mvc.perform(post("/api/ai/chat/stream").header("Authorization","Bearer token")
        .contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
        .andExpect(request().asyncStarted()).andReturn();
    result.getAsyncResult(3000);
    mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .andExpect(header().string("Cache-Control","no-store"))
        .andExpect(header().string("X-Accel-Buffering","no"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("event:delta")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
  }

  @Test void unconfiguredStreamReturns503BeforeStartingAsync() throws Exception {
    when(auth.authenticate("token")).thenReturn(mock(User.class));
    doThrow(new AiFailure(HttpStatus.SERVICE_UNAVAILABLE,"AI_NOT_CONFIGURED","智能对话尚未配置")).when(ai).validate(any());
    mvc.perform(post("/api/ai/chat/stream").header("Authorization","Bearer token").contentType(MediaType.APPLICATION_JSON)
        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
        .andExpect(status().isServiceUnavailable()).andExpect(request().asyncNotStarted())
        .andExpect(jsonPath("$.code").value("AI_NOT_CONFIGURED"));
    verify(ai,never()).stream(any(),any());
  }

  @Test void streamReturnsOrdinaryJsonErrorsToSseClients() throws Exception {
    when(auth.authenticate("token")).thenReturn(mock(User.class));
    mvc.perform(post("/api/ai/chat/stream").header("Authorization","Bearer token")
        .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[]}"))
        .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    mvc.perform(post("/api/ai/chat/stream").header("Authorization","Bearer token")
        .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
    for (HttpStatus status : java.util.List.of(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.TOO_MANY_REQUESTS)) {
      String code = status == HttpStatus.SERVICE_UNAVAILABLE ? "AI_NOT_CONFIGURED" : "AI_RATE_LIMITED";
      doThrow(new AiFailure(status,code,"安全提示")).when(ai).validate(any());
      mvc.perform(post("/api/ai/chat/stream").header("Authorization","Bearer token")
          .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
          .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
          .andExpect(status().is(status.value())).andExpect(request().asyncNotStarted())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andExpect(jsonPath("$.code").value(code));
    }
  }

  @Test void unconfiguredReturnsActionable503() throws Exception {
    when(auth.authenticate("token")).thenReturn(mock(User.class));
    when(ai.chat(any())).thenThrow(new AiFailure(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "智能对话尚未配置"));
    mvc.perform(post("/api/ai/chat").header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON)
        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_NOT_CONFIGURED"));
  }
}
