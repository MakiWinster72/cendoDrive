package com.cendodrive.drive;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneOffset;
import java.util.List;

public final class DriveDtos {
    private DriveDtos() {}
    public record FileResponse(
            @Schema(example = "42") String id,
            @Schema(example = "项目文档") String name,
            @Schema(description = "类型：folder 或 file", example = "folder") String kind,
            @Schema(description = "字节数；文件夹为 0", example = "0") long size,
            @Schema(description = "根目录时为 null", example = "10") String parentId,
            @Schema(description = "UTC ISO-8601 时间", example = "2026-09-29T06:30:00Z") String updatedAt,
            @Schema(description = "移入回收站的 UTC 时间；未删除时为 null") String deletedAt) {
        static FileResponse from(DriveFile file) {
            return new FileResponse(file.getId().toString(), DriveFile.displayName(file.getName()), file.getKind(), file.getSize(),
                    file.getParentId() == null ? null : file.getParentId().toString(),
                    file.getUpdatedAt().atOffset(ZoneOffset.UTC).toInstant().toString(),
                    file.getDeletedAt() == null ? null : file.getDeletedAt().atOffset(ZoneOffset.UTC).toInstant().toString());
        }
    }
    public record FileIdsRequest(@Schema(example = "[1, 2]") List<Long> ids) {}
    public record CreateFolderRequest(
            @Schema(example = "项目文档") @NotBlank @Size(max = 255) String name,
            @Schema(description = "父目录 ID；null 表示根目录", example = "10") Long parentId) {}
    public record RenameRequest(
            @Schema(example = "已归档项目") @NotBlank @Size(max = 255) String name) {}
    public record MoveRequest(
            @Schema(description = "目标目录 ID；null 表示移到根目录", example = "10") Long parentId) {}
}
