package com.cendodrive.user;

import com.cendodrive.user.UserDtos.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Tag(name="账号管理",description="资料、头像、密码、精确用户名查询以及七天注销")
@SecurityRequirement(name="bearerAuth")
public class UserController {
  private final UserService service;
  public UserController(UserService service) { this.service=service; }
  @GetMapping("/api/user/me/profile")
  ProfileResponse profile(@AuthenticationPrincipal User user) { return service.profile(user); }
  @PutMapping("/api/user/me/profile")
  ProfileResponse update(@AuthenticationPrincipal User user,@Valid @RequestBody ProfileRequest request) {
    return service.updateProfile(user,request);
  }
  @GetMapping("/api/users/lookup")
  ProfileResponse lookup(@RequestParam String username) { return service.lookup(username); }
  @GetMapping("/api/user/me/avatar")
  ResponseEntity<byte[]> ownAvatar(@AuthenticationPrincipal User user) { return image(service.avatar(user.getId())); }
  @GetMapping("/api/users/{id}/avatar")
  ResponseEntity<byte[]> publicAvatar(@PathVariable Long id) { return image(service.avatar(id)); }
  @PutMapping(value="/api/user/me/avatar",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<Void> upload(@AuthenticationPrincipal User user,@RequestParam("file") MultipartFile file) throws IOException {
    service.updateAvatar(user,file); return ResponseEntity.noContent().build();
  }
  @DeleteMapping("/api/user/me/avatar")
  ResponseEntity<Void> remove(@AuthenticationPrincipal User user) {
    service.deleteAvatar(user); return ResponseEntity.noContent().build();
  }
  @PostMapping("/api/user/me/password")
  ResponseEntity<Void> password(@AuthenticationPrincipal User user,@Valid @RequestBody PasswordRequest request) {
    service.changePassword(user,request); return ResponseEntity.noContent().build();
  }
  @PostMapping("/api/user/me/deletion")
  DeletionResponse delete(@AuthenticationPrincipal User user,@Valid @RequestBody DeleteRequest request) {
    return service.deleteAccount(user,request);
  }
  private static ResponseEntity<byte[]> image(byte[] image) {
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options","nosniff").body(image);
  }
}
