package com.cendodrive.share;

import com.cendodrive.drive.DriveDtos.FileResponse;
import com.cendodrive.share.ShareDtos.*;
import com.cendodrive.user.User;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriUtils;

@RestController
@RequestMapping("/api/shares")
public class ShareController {
  private final ShareService shares;
  public ShareController(ShareService shares) { this.shares = shares; }

  @Operation(summary = "创建文件分享（最长 30 天）")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  ShareResponse create(@AuthenticationPrincipal User user, @Valid @RequestBody CreateShareRequest request) {
    return shares.create(user, request);
  }

  @Operation(summary = "列出当前用户的分享")
  @GetMapping
  List<ShareResponse> list(@AuthenticationPrincipal User user) { return shares.list(user); }

  @Operation(summary = "取消当前用户的分享")
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void cancel(@AuthenticationPrincipal User user, @PathVariable Long id) { shares.cancel(user, id); }

  @Operation(summary = "匿名查看有效分享")
  @GetMapping("/{token}")
  ResponseEntity<ShareAccessResponse> get(@PathVariable String token,
      @RequestHeader(value="X-Share-Code",required=false) String code, HttpServletRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer").body(shares.get(token, code, request.getRemoteAddr()));
  }

  @Operation(summary = "匿名下载有效分享")
  @GetMapping("/{token}/download")
  ResponseEntity<StreamingResponseBody> download(@PathVariable String token,
      @RequestHeader(value="X-Share-Code",required=false) String code, HttpServletRequest request) {
    var content = shares.download(token, code, request.getRemoteAddr());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer")
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''"
            + UriUtils.encode(content.name(), StandardCharsets.UTF_8))
        .contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(content.size()).body(content.body());
  }

  @Operation(summary = "将有效分享独立转存到当前用户网盘")
  @PostMapping("/{token}/save")
  @ResponseStatus(HttpStatus.CREATED)
  FileResponse save(@AuthenticationPrincipal User user, @PathVariable String token,
      @Valid @RequestBody SaveShareRequest request,
      @RequestHeader(value="X-Share-Code",required=false) String code, HttpServletRequest servletRequest) throws IOException {
    return shares.save(user, token, request, code, servletRequest.getRemoteAddr());
  }
}
