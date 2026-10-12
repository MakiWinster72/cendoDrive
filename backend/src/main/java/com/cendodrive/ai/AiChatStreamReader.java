package com.cendodrive.ai;

import com.cendodrive.ai.AiChatService.AiFailure;
import com.cendodrive.ai.AiChatService.StreamSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Bounded OpenAI SSE decoder; never forwards provider diagnostics or metadata. */
final class AiChatStreamReader {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_EVENT = 131072;
  private AiChatStreamReader() {}

  static void read(InputStream body, StreamSink sink) throws IOException {
    var reader = new PushbackReader(new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8)), 1);
    var data = new StringBuilder();
    var state = new State();
    String line;
    while ((line = line(reader)) != null) {
      if (line.isEmpty()) {
        if (!data.isEmpty() && event(data.toString(), state, sink)) return;
        data.setLength(0);
      } else if (line.startsWith("data:")) {
        String value = line.substring(5);
        if (value.startsWith(" ")) value = value.substring(1);
        if (data.length() + value.length() + 1 > MAX_EVENT) throw invalid();
        if (!data.isEmpty()) data.append('\n');
        data.append(value);
      }
    }
    if (!data.isEmpty() && event(data.toString(), state, sink)) return;
    throw new AiFailure(HttpStatus.BAD_GATEWAY, "AI_INCOMPLETE_RESPONSE", "模型回答中断，请重试。");
  }

  private static String line(PushbackReader reader) throws IOException {
    var line = new StringBuilder();
    int c;
    while ((c = reader.read()) != -1) {
      if (c == '\n') return line.toString();
      if (c == '\r') {
        int next = reader.read();
        if (next != -1 && next != '\n') reader.unread(next);
        return line.toString();
      }
      if (line.length() >= MAX_EVENT) throw invalid();
      line.append((char)c);
    }
    return line.isEmpty() ? null : line.toString();
  }

  private static boolean event(String data, State state, StreamSink sink) throws IOException {
    if ("[DONE]".equals(data)) return done(state, sink);
    JsonNode root;
    try { root = JSON.readTree(data); }
    catch (IOException ex) { throw invalid(); }
    if (root == null || !root.isObject()) throw invalid();
    if (root.has("error"))
      throw new AiFailure(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_ERROR", "模型服务暂不可用，请稍后重试。");
    JsonNode choices = root.path("choices");
    if (!choices.isArray()) throw invalid();
    if (choices.isEmpty()) return false; // Optional usage event.
    JsonNode choice = choices.path(0);
    JsonNode delta = choice.path("delta");
    if (!delta.isObject()) throw invalid();
    JsonNode content = delta.path("content");
    if (!content.isMissingNode() && !content.isNull()) {
      if (!content.isTextual()) throw invalid();
      String text = content.asText();
      if (state.characters + text.length() > 64000) throw invalid();
      state.characters += text.length();
      state.hasText |= !text.isBlank();
      if (!text.isEmpty()) sink.send("delta", Map.of("content", text));
    }
    JsonNode finish = choice.path("finish_reason");
    if (!finish.isMissingNode() && !finish.isNull()) {
      if ("stop".equals(finish.asText())) return done(state, sink);
      throw new AiFailure(HttpStatus.BAD_GATEWAY, "AI_INCOMPLETE_RESPONSE", "模型未完成回答，请重试或缩短问题。");
    }
    return false;
  }

  private static boolean done(State state, StreamSink sink) throws IOException {
    if (!state.hasText) throw invalid();
    sink.send("done", Map.of());
    return true;
  }
  private static AiFailure invalid() {
    return new AiFailure(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE", "模型未返回有效回答，请重试。");
  }
  private static final class State { int characters; boolean hasText; }
}
