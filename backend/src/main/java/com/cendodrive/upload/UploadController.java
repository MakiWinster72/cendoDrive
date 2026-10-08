package com.cendodrive.upload;

import com.cendodrive.drive.DriveDtos.FileResponse;
import com.cendodrive.upload.UploadDtos.ChunkResponse;
import com.cendodrive.upload.UploadDtos.InitRequest;
import com.cendodrive.upload.UploadDtos.InitResponse;
import com.cendodrive.upload.UploadDtos.MergeRequest;
import com.cendodrive.upload.UploadDtos.StatusResponse;
import com.cendodrive.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
@Tag(name = "分片上传", description = "大文件分片上传：初始化、分片接收、状态查询与合并")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class UploadController {
    private final UploadService upload;

    public UploadController(UploadService upload) { this.upload = upload; }

    @Operation(summary = "初始化分片上传任务",
            description = "uploadId 由文件信息派生，重复调用会返回同一会话与已上传分片，用于断点续传。")
    @PostMapping("/init")
    InitResponse init(@AuthenticationPrincipal User user, @Valid @RequestBody InitRequest request) {
        return upload.init(user, request);
    }

    @Operation(summary = "上传一个分片",
            description = "multipart/form-data：file（分片内容）、uploadId、index（从 0 开始）、chunkHash（该分片 SHA-256）。")
    @PostMapping(value = "/chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ChunkResponse chunk(@AuthenticationPrincipal User user,
                        @RequestPart("file") MultipartFile file,
                        @RequestParam String uploadId,
                        @RequestParam Integer index,
                        @RequestParam String chunkHash) {
        return upload.chunk(user, file, uploadId, index, chunkHash);
    }

    @Operation(summary = "查询分片上传进度", description = "返回已上传与缺失的分片下标，供客户端断点续传。")
    @GetMapping("/{uploadId}/status")
    StatusResponse status(@AuthenticationPrincipal User user, @PathVariable String uploadId) {
        return upload.status(user, uploadId);
    }

    @Operation(summary = "合并分片并生成文件", description = "校验分片齐全与整文件 SHA-256 后写入存储并落库，返回文件对象。")
    @PostMapping("/merge")
    ResponseEntity<FileResponse> merge(@AuthenticationPrincipal User user, @Valid @RequestBody MergeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(upload.merge(user, request.uploadId()));
    }
}
