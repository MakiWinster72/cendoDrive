package com.cendodrive.ai;

import com.cendodrive.ai.AiChatDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/chat")
public class AiChatController {
  private final AiChatService service;

  public AiChatController(AiChatService service) {
    this.service = service;
  }

  @GetMapping("/status")
  public ResponseEntity<StatusResponse> status() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status());
  }

  @PostMapping
  public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.chat(request));
  }
}
