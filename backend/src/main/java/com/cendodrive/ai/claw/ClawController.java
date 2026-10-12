package com.cendodrive.ai.claw;

import com.cendodrive.ai.claw.ClawDtos.*;
import com.cendodrive.user.User;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/claw")
public class ClawController {
  private final ClawSearchService service;
  public ClawController(ClawSearchService service) {this.service=service;}
  @GetMapping("/status")
  public ResponseEntity<Status> status(@AuthenticationPrincipal User user) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(user.getId()));
  }
  @PostMapping("/ask")
  public ResponseEntity<Answer> ask(@AuthenticationPrincipal User user,@Valid @RequestBody Question request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.ask(user.getId(),request));
  }
  @PostMapping("/files/{fileId}/reindex")
  public ResponseEntity<ReindexResult> reindexFile(@AuthenticationPrincipal User user,@PathVariable long fileId,@Valid @RequestBody ReindexRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reindexFile(user.getId(),fileId,request));
  }
  @PostMapping("/reindex")
  public ResponseEntity<ReindexResult> reindexAll(@AuthenticationPrincipal User user,@Valid @RequestBody ReindexRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reindexAll(user.getId(),request));
  }
}
