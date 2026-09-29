package com.cendodrive.file;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class FileDtos {
    private FileDtos() {}
    public record CreateFolderRequest(@NotNull @PositiveOrZero Long parentId, String name) {}
    public record RenameRequest(String name) {}
    public record MoveRequest(@NotNull @PositiveOrZero Long targetParentId) {}
    public record RegisterStoredFileCommand(long parentId, String name, long sizeBytes,
            String storageProvider, String storageKey, String contentType, String contentHash, String ingestKey) {}
    public record FileEntryResponse(String id, String parentId, String name, FileEntry.EntryType entryType,
            long sizeBytes, String contentType, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public static FileEntryResponse from(FileEntry entry) {
            return new FileEntryResponse(entry.getId().toString(), Long.toString(entry.getParentId()),
                    entry.getName(), entry.getEntryType(), entry.getSizeBytes(), entry.getContentType(),
                    entry.getCreatedAt().atOffset(ZoneOffset.UTC), entry.getUpdatedAt().atOffset(ZoneOffset.UTC));
        }
    }
    public record FileListResponse(String parentId, List<FileEntryResponse> items, int page, int size, long totalElements) {}
}
