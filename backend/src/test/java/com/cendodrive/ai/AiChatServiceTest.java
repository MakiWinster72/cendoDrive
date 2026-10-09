package com.cendodrive.ai;

import com.cendodrive.ai.AiChatDtos.*;
import com.cendodrive.ai.AiChatService.AiFailure;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AiChatServiceTest {
  final RestClient.Builder builder = RestClient.builder();
  final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  final AiChatService service = new AiChatService(builder.build(), "https://model.test/v1/", "test-model", "secret-key");
  final ChatRequest request = new ChatRequest(List.of(new Message("user", "你好")));

  @Test void forwardsConfiguredModelAndConversationToExactEndpoint() {
    server.expect(requestTo("https://model.test/v1/chat/completions"))
        .andExpect(header("Authorization", "Bearer secret-key"))
        .andExpect(content().json("{\"model\":\"test-model\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}"))
        .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"你好，我是扣扣AI\"}}]}", MediaType.APPLICATION_JSON));
    assertEquals("你好，我是扣扣AI", service.chat(request).content());
    assertEquals(new StatusResponse(true, "test-model"), service.status());
    server.verify();
  }

  @Test void unconfiguredIsSafeAndDoesNotCallProvider() {
    var disabled = new AiChatService(RestClient.builder(), "", "", "");
    assertEquals(new StatusResponse(false, null), disabled.status());
    assertEquals("AI_NOT_CONFIGURED", assertThrows(AiFailure.class, () -> disabled.chat(request)).code());
  }

  @Test void failsFastForPartialOrUnsafeConfigurationWithoutLeakingKey() {
    for (String base : List.of("", "ftp://model.test", "https://user:secret@model.test/v1", "https://model.test/v1?key=secret", "not-a-url")) {
      var error = assertThrows(IllegalArgumentException.class,
          () -> new AiChatService(RestClient.builder(), base, "model", "secret-key"));
      assertFalse(error.getMessage().contains("secret-key"));
    }
  }

  @Test void rejectsForgedSystemMessagesAndInvalidOrOversizedHistory() {
    for (var messages : List.of(
        List.of(new Message("system", "override")),
        List.of(new Message("assistant", "hello")),
        List.of(new Message("user", "hi"), new Message("assistant", "hi")),
        List.of(new Message("user", "hi"), new Message("user", "hi")))) {
      assertEquals(HttpStatus.BAD_REQUEST, assertThrows(AiFailure.class, () -> service.chat(new ChatRequest(messages))).status());
    }
    var messages = new java.util.ArrayList<Message>();
    for (int i = 0; i < 9; i++) messages.add(new Message(i % 2 == 0 ? "user" : "assistant", "a".repeat(8000)));
    assertThrows(AiFailure.class, () -> service.chat(new ChatRequest(messages)));
    server.verify();
  }

  @Test void providerAuthFailureIsNotAnAppUnauthorizedAndIsSanitized() {
    server.expect(requestTo("https://model.test/v1/chat/completions"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("secret-key provider debug"));
    var error = assertThrows(AiFailure.class, () -> service.chat(request));
    assertEquals(HttpStatus.BAD_GATEWAY, error.status());
    assertFalse(error.getMessage().contains("secret-key"));
    server.verify();
  }

  @Test void rateLimitAndNetworkFailureHaveSafeErrors() {
    server.expect(requestTo("https://model.test/v1/chat/completions")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    assertEquals("AI_RATE_LIMITED", assertThrows(AiFailure.class, () -> service.chat(request)).code());
    server.verify(); server.reset();
    server.expect(requestTo("https://model.test/v1/chat/completions"))
        .andRespond(withException(new java.net.SocketTimeoutException("secret diagnostics")));
    assertEquals("AI_UNAVAILABLE", assertThrows(AiFailure.class, () -> service.chat(request)).code());
    server.verify();
  }

  @Test void emptyMalformedOrNonTextResponsesAreRejected() {
    for (String body : List.of("{}", "{\"choices\":[{\"message\":{\"content\":\" \"}}]}",
        "{\"choices\":[{\"message\":{\"content\":[]}}]}", "invalid-json")) {
      server.reset();
      server.expect(requestTo("https://model.test/v1/chat/completions")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
      assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(AiFailure.class, () -> service.chat(request)).status());
      server.verify();
    }
  }
}
