package com.cendodrive.chat;

import com.cendodrive.drive.DriveDtos.FileResponse;
import com.cendodrive.user.User;
import java.io.IOException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat/rooms/{room}/files")
public class ChatFileController {
  private final ChatFileService files;
  public ChatFileController(ChatFileService files) { this.files=files; }
  public record FileRequest(@NotNull @Positive Long fileId) {}
  @PostMapping public void send(@AuthenticationPrincipal User user, @PathVariable String room,
      @Valid @RequestBody FileRequest request) { files.send(user,room,request.fileId()); }
  @PostMapping("/{messageId}/save") public FileResponse save(@AuthenticationPrincipal User user,
      @PathVariable String room, @PathVariable long messageId) throws IOException {
    return files.save(user,room,messageId);
  }
}
