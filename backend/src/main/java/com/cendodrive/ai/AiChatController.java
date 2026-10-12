package com.cendodrive.ai;

import com.cendodrive.ai.AiChatDtos.*;
import com.cendodrive.ai.AiChatService.AiFailure;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ai/chat")
public class AiChatController {
  private final AiChatService service;
  private final AiChatExecutor executor;
  public AiChatController(AiChatService service, AiChatExecutor executor) {
    this.service = service; this.executor = executor;
  }

  @GetMapping("/status")
  public ResponseEntity<StatusResponse> status() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status());
  }

  @PostMapping
  public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.chat(request));
  }

  @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> stream(@Valid @RequestBody ChatRequest request) {
    service.validate(request); // Preserve ordinary HTTP 400/503 before starting the stream.
    var emitter = new SseEmitter(75000L);
    var active = new AtomicBoolean(true);
    emitter.onCompletion(() -> active.set(false));
    emitter.onTimeout(() -> { active.set(false); emitter.complete(); });
    emitter.onError(error -> active.set(false));
    try {
      executor.execute(() -> {
        try {
          service.stream(request, (event, data) -> {
            if (!active.get()) throw new IOException("Stream closed");
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
          });
          emitter.complete();
        } catch (IOException ex) {
          // Disconnect or timeout: unwind the upstream exchange, no retry or raw diagnostics.
          emitter.complete();
        } catch (RuntimeException ex) {
          if (active.get()) {
            try { emitter.send(SseEmitter.event().name("error").data(Map.of("code", "AI_UNAVAILABLE",
                "message", "模型连接失败，请稍后重试。"), MediaType.APPLICATION_JSON)); }
            catch (IOException | IllegalStateException ignored) {}
          }
          emitter.complete();
        }
      });
    } catch (TaskRejectedException ex) {
      throw new AiFailure(HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMITED", "智能对话请求较多，请稍后重试。");
    }
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_EVENT_STREAM)
        .header("X-Accel-Buffering", "no").body(emitter);
  }
}
