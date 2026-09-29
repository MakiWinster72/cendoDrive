package com.cendodrive.drive;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.ZoneOffset;

public final class DriveDtos {
    private DriveDtos() {}
    public record FileResponse(String id, String name, String kind, long size, String parentId, String updatedAt) {
        static FileResponse from(DriveFile file) {
            return new FileResponse(file.getId().toString(), file.getName(), file.getKind(), file.getSize(),
                    file.getParentId() == null ? null : file.getParentId().toString(),
                    file.getUpdatedAt().atOffset(ZoneOffset.UTC).toInstant().toString());
        }
    }
    public record CreateFolderRequest(@NotBlank @Size(max = 255) String name, Long parentId) {}
    public record RenameRequest(@NotBlank @Size(max = 255) String name) {}
    public record MoveRequest(Long parentId) {}
}
