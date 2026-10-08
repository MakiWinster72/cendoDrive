package com.cendodrive.chat;

import com.cendodrive.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
  private final ChatService chat;
  public ChatController(ChatService chat) { this.chat=chat; }
  public record GroupRequest(@NotBlank @Size(max=20) String name, @NotNull @Size(max=50) String description, boolean searchable) {}
  public record SendRequest(@NotBlank @Size(max=2000) String content) {}
  @GetMapping("/users") public List<ChatService.Person> users(@RequestParam String q) { return chat.search(q); }
  @GetMapping("/rooms") public List<ChatService.Room> rooms(@AuthenticationPrincipal User user) { return chat.rooms(user); }
  @PostMapping("/direct/{target}") public ChatService.Room direct(@AuthenticationPrincipal User user,@PathVariable long target) { return chat.direct(user,target); }
  @GetMapping("/groups") public List<ChatService.Room> groups(@RequestParam String q) { return chat.groups(q); }
  @PostMapping("/groups") public ChatService.Room create(@AuthenticationPrincipal User user,@Valid @RequestBody GroupRequest request) { return chat.create(user,request.name(),request.description(),request.searchable()); }
  @PostMapping("/groups/{id}/join") public void join(@AuthenticationPrincipal User user,@PathVariable String id) { chat.join(user,id); }
  @GetMapping("/rooms/{id}/messages") public List<ChatService.Message> messages(@AuthenticationPrincipal User user,@PathVariable String id,@RequestParam(defaultValue="0") @Min(0) long after) { return chat.messages(user,id,after); }
  @PostMapping("/rooms/{id}/messages") public void send(@AuthenticationPrincipal User user,@PathVariable String id,@Valid @RequestBody SendRequest request) { chat.send(user,id,request.content()); }
}
