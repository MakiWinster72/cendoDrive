package com.cendodrive.upload;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class UploadDtos {
    private UploadDtos() {}

    public record InitRequest(
            @Schema(example = "movie.mp4") @NotBlank @Size(max = 255) String name,
            @Schema(description = "父目录 ID；null 表示根目录", example = "10") Long parentId,
            @Schema(description = "文件总字节数", example = "52428800") @Min(1) long totalSize,
            @Schema(description = "整文件 SHA-256，64 位十六进制") @NotBlank String fileHash) {}

    public record InitResponse(
            String uploadId,
            long chunkSize,
            int totalChunks,
            @Schema(description = "已接收的分片下标，可用于断点续传") List<Integer> uploadedIndexes,
            @Schema(description = "UPLOADING：可继续上传；COMPLETED：文件已生成，直接调用 merge 取回结果") String status) {}

    public record ChunkResponse(
            String uploadId,
            List<Integer> receivedIndexes,
            int received,
            int totalChunks) {}

    public record StatusResponse(
            String uploadId,
            String status,
            long chunkSize,
            int totalChunks,
            List<Integer> uploadedIndexes,
            List<Integer> missingIndexes,
            int received,
            long totalSize) {}

    public record MergeRequest(
            @Schema(description = "init 返回的上传会话 ID") @NotBlank String uploadId) {}
}
