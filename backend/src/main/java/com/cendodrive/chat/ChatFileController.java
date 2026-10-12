package com.cendodrive.chat;

import com.cendodrive.drive.DriveDtos.FileResponse;
import com.cendodrive.user.User;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriUtils;

@RestController
@RequestMapping("/api/chat/rooms/{room}/files")
public class ChatFileController {
  private final ChatFileService files;

  public ChatFileController(ChatFileService files) {
    this.files = files;
  }

  public record FileRequest(@NotNull @Positive Long fileId) {
  }

  public record SaveRequest(@Positive Long parentId) {
  }

  @PostMapping
  public void send(@AuthenticationPrincipal User user, @PathVariable String room,
      @Valid @RequestBody FileRequest request) {
    files.send(user, room, request.fileId());
  }

  @GetMapping("/{messageId}")
  public ResponseEntity<FileResponse> get(@AuthenticationPrincipal User user,
      @PathVariable String room, @PathVariable long messageId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(files.get(user, room, messageId));
  }

  @GetMapping("/{messageId}/download")
  public ResponseEntity<StreamingResponseBody> download(
      @AuthenticationPrincipal User user, @PathVariable String room, @PathVariable long messageId) {
    var content = files.download(user, room, messageId);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename*=UTF-8''" + UriUtils.encode(content.name(), StandardCharsets.UTF_8))
        .contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(content.size()).body(content.body());
  }

  @PostMapping("/{messageId}/save")
  public FileResponse save(@AuthenticationPrincipal User user,
      @PathVariable String room, @PathVariable long messageId,
      @Valid @RequestBody(required = false) SaveRequest request) throws IOException {
    return files.save(user, room, messageId, request == null ? null : request.parentId());
  }
}
