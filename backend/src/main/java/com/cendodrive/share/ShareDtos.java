package com.cendodrive.share;

import com.cendodrive.drive.DriveDtos.FileResponse;
import jakarta.validation.constraints.*;

public final class ShareDtos {
  private ShareDtos() {}

  public record CreateShareRequest(@NotNull @Positive Long fileId,
      @NotNull @Min(1) @Max(2592000) Long expiresInSeconds) {}

  public record SaveShareRequest(@Positive Long parentId) {}

  public record ShareResponse(String id, String token, String fileId, String fileName,
      String kind, long size, String createdAt, String expiresAt, String status) {
    static ShareResponse from(ShareLink link) {
      return new ShareResponse(link.getId().toString(), link.getToken(), link.getFileId().toString(),
          link.getFileName(), "file", link.getSize(), link.getCreatedAt().toString(),
          link.getExpiresAt().toString(), link.isCancelled() ? "CANCELLED" : "ACTIVE");
    }
  }

  public record ShareAccessResponse(FileResponse file, String expiresAt) {}
}
