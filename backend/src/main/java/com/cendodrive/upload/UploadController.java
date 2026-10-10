package com.cendodrive.upload;

import com.cendodrive.drive.DriveDtos.FileResponse;
import com.cendodrive.user.User;
import com.cendodrive.upload.UploadDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
@Tag(name = "分片上传", description = "持久化会话、断点续传和全文件 MD5 校验")
@SecurityRequirement(name = "bearerAuth")
public class UploadController {
  private final UploadService uploads;

  public UploadController(UploadService uploads) {
    this.uploads = uploads;
  }

  @PostMapping("/init")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "初始化或恢复上传，预留用户容量")
  InitResponse init(@AuthenticationPrincipal User user, @Valid @RequestBody InitRequest request) {
    return uploads.init(user, request);
  }

  @GetMapping("/{id}/status")
  @Operation(summary = "查询已接收分片（序号从 0 开始）")
  StatusResponse status(@AuthenticationPrincipal User user, @PathVariable String id) throws IOException {
    return uploads.status(user, id);
  }

  @PostMapping("/chunk")
  @Operation(summary = "接收分片，重复序号安全覆盖")
  void chunk(@AuthenticationPrincipal User user, @RequestParam String uploadId, @RequestParam int chunkNumber,
      @RequestParam int totalChunks, @RequestParam String fileHash, @RequestPart("chunk") MultipartFile chunk)
      throws IOException {
    uploads.chunk(user, uploadId, chunkNumber, totalChunks, fileHash, chunk);
  }

  @PostMapping("/merge")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "完整性校验后流式入库，重复合并返回同一文件")
  FileResponse merge(@AuthenticationPrincipal User user, @Valid @RequestBody MergeRequest request) throws IOException {
    return uploads.merge(user, request);
  }
}
