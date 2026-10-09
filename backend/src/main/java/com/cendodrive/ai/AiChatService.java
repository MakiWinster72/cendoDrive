package com.cendodrive.ai;

import com.cendodrive.ai.AiChatDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class AiChatService {
  private final RestClient client;
  private final String endpoint;
  private final String model;
  private final String key;

  @Autowired
  public AiChatService(RestClient.Builder builder,
      @Value("${cendo.ai.chat.base-url:${CENDO_AI_CHAT_BASE_URL:}}") String baseUrl,
      @Value("${cendo.ai.chat.model:${CENDO_AI_CHAT_MODEL:}}") String model,
      @Value("${cendo.ai.chat.key:${CENDO_AI_CHAT_KEY:}}") String key) {
    this(createClient(builder), baseUrl, model, key);
  }

  AiChatService(RestClient client, String baseUrl, String model, String key) {
    this.client = client;
    String base = baseUrl.trim().replaceAll("/+$", "");
    this.model = model.trim();
    this.key = key.trim();
    boolean any = !base.isEmpty() || !this.model.isEmpty() || !this.key.isEmpty();
    if (any) {
      if (base.isEmpty() || this.model.isEmpty() || this.key.isEmpty())
        throw new IllegalArgumentException("AI chat requires base-url, model and key together");
      URI uri;
      try { uri = URI.create(base); }
      catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Invalid AI chat base-url"); }
      if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
          || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
        throw new IllegalArgumentException("AI chat base-url must be an HTTP(S) API base without credentials, query or fragment");
      if (this.key.contains("\n") || this.key.contains("\r"))
        throw new IllegalArgumentException("Invalid AI chat key format");
    }
    endpoint = base.isEmpty() ? "" : base + "/chat/completions";
  }

  private static RestClient createClient(RestClient.Builder builder) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(60));
    return builder.requestFactory(factory).build();
  }

  public StatusResponse status() {
    return new StatusResponse(!endpoint.isEmpty(), endpoint.isEmpty() ? null : model);
  }

  public ChatResponse chat(ChatRequest request) {
    if (!status().configured())
      throw new AiFailure(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "智能对话尚未配置，请联系管理员。");
    var messages = request.messages();
    int total = 0;
    for (int i = 0; i < messages.size(); i++) {
      if (!(i % 2 == 0 ? "user" : "assistant").equals(messages.get(i).role()))
        throw new AiFailure(HttpStatus.BAD_REQUEST, "AI_INVALID_HISTORY", "对话必须由用户开始，按用户和 AI 交替排列。");
      total += messages.get(i).content().length();
    }
    if (messages.size() % 2 == 0 || total > 64000)
      throw new AiFailure(HttpStatus.BAD_REQUEST, "AI_INVALID_HISTORY", "请以用户问题结尾，对话总长度不能超过 64000 字符。");
    try {
      JsonNode response = client.post().uri(endpoint)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("model", model, "messages", messages, "stream", false))
          .retrieve()
          .onStatus(status -> status.value() == 429, (req, res) -> {
            throw new AiFailure(HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMITED", "模型服务繁忙，请稍后重试。");
          })
          .onStatus(status -> status.isError(), (req, res) -> {
            throw new AiFailure(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_ERROR", "模型服务暂不可用，请稍后重试或联系管理员检查配置。");
          }).body(JsonNode.class);
      JsonNode content = response == null ? null : response.path("choices").path(0).path("message").path("content");
      if (content == null || !content.isTextual() || content.asText().isBlank())
        throw new AiFailure(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE", "模型未返回有效回答，请重试。");
      return new ChatResponse(content.asText());
    } catch (RestClientException ex) {
      // Do not expose upstream bodies, URLs, keys or provider diagnostics.
      throw new AiFailure(HttpStatus.BAD_GATEWAY, "AI_UNAVAILABLE", "模型连接失败或响应超时，请稍后重试。");
    }
  }

  public static class AiFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public AiFailure(HttpStatus status, String code, String message) {
      super(message); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
  }
}
