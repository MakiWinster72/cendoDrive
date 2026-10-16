package com.cendodrive.ai.claw;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ClawSettings {
  final boolean enabled;
  final String embeddingBaseUrl, embeddingModel, embeddingKey, vectorBaseUrl, vectorKey;
  final int dimensions;
  private final String collection;

  @Autowired
  public ClawSettings(@Value("${cendo.ai.claw.enabled:${CENDO_AI_CLAW_ENABLED:false}}") boolean enabled,
      @Value("${cendo.ai.claw.embedding.base-url:${CENDO_AI_EMBEDDING_BASE_URL:}}") String embeddingBaseUrl,
      @Value("${cendo.ai.claw.embedding.model:${CENDO_AI_EMBEDDING_MODEL:Qwen/Qwen3-VL-Embedding-8B}}") String embeddingModel,
      @Value("${cendo.ai.claw.embedding.key:${CENDO_AI_EMBEDDING_KEY:}}") String embeddingKey,
      @Value("${cendo.ai.claw.embedding.dimensions:${CENDO_AI_EMBEDDING_DIMENSIONS:4096}}") int dimensions,
      @Value("${cendo.ai.claw.vector.base-url:${CENDO_AI_VECTOR_BASE_URL:http://localhost:6333}}") String vectorBaseUrl,
      @Value("${cendo.ai.claw.vector.key:${CENDO_AI_VECTOR_KEY:}}") String vectorKey,
      @Value("${cendo.ai.claw.vector.collection:${CENDO_AI_VECTOR_COLLECTION:cendo_claw}}") String prefix,
      @Value("${cendo.ai.chat.base-url:${CENDO_AI_CHAT_BASE_URL:}}") String chatBase,
      @Value("${cendo.ai.chat.key:${CENDO_AI_CHAT_KEY:}}") String chatKey) {
    this(enabled, embeddingBaseUrl.isBlank() ? chatBase : embeddingBaseUrl, embeddingModel,
        embeddingKey.isBlank() ? chatKey : embeddingKey, dimensions, vectorBaseUrl, vectorKey, prefix);
  }

  ClawSettings(boolean enabled, String embeddingBaseUrl, String embeddingModel, String embeddingKey,
      int dimensions, String vectorBaseUrl, String vectorKey, String prefix) {
    this.enabled = enabled;
    this.embeddingBaseUrl = base(embeddingBaseUrl);
    this.embeddingModel = embeddingModel.trim();
    this.embeddingKey = embeddingKey.trim();
    this.vectorBaseUrl = base(vectorBaseUrl);
    this.vectorKey = vectorKey.trim();
    this.dimensions = dimensions;
    if (dimensions < 1 || dimensions > 8192 || !prefix.matches("[a-zA-Z0-9_-]{1,48}"))
      throw new IllegalArgumentException("Invalid Claw dimensions or collection prefix");
    if (enabled && (this.embeddingBaseUrl.isBlank() || this.embeddingModel.isBlank() || this.embeddingKey.isBlank()
        || this.vectorBaseUrl.isBlank()))
      throw new IllegalArgumentException(
          "Claw requires embedding base-url, model, key and vector base-url when enabled");
    if (enabled) {
      validateBase(this.embeddingBaseUrl);
      validateBase(this.vectorBaseUrl);
    }
    if (this.embeddingKey.contains("\r") || this.embeddingKey.contains("\n") || this.vectorKey.contains("\r")
        || this.vectorKey.contains("\n"))
      throw new IllegalArgumentException("Invalid Claw key format");
    try {
      String identity = this.embeddingBaseUrl + "\n" + this.embeddingModel + "\n" + dimensions;
      collection = prefix + "_"
          + HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)))
              .substring(0, 12);
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private static String base(String value) {
    return value.trim().replaceAll("/+$", "");
  }

  private static void validateBase(String value) {
    try {
      URI uri = URI.create(value);
      if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
          || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Claw API base must be HTTP(S) without credentials, query or fragment");
    }
  }

  public boolean enabled() {
    return enabled;
  }

  public String model() {
    return embeddingModel;
  }

  public String collection() {
    return collection;
  }

  void requireEnabled() {
    if (!enabled)
      throw new ClawFailure("CLAW_NOT_CONFIGURED");
  }

  @Override
  public String toString() {
    return "ClawSettings[enabled=" + enabled + ", dimensions=" + dimensions + "]";
  }
}
