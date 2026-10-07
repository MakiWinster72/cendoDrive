package com.cendodrive.upload;

import jakarta.validation.constraints.*;
import java.util.List;

public final class UploadDtos {
  private UploadDtos() {}
  public record InitRequest(@NotBlank @Size(max=255) String fileName, @Positive long fileSize,
      @NotBlank @Pattern(regexp="(?i)[a-f0-9]{32}") String fileHash, @Positive Long parentId,
      @Min(1) @Max(5242880) int chunkSize, @Min(1) @Max(2048) int totalChunks) {}
  public record MergeRequest(@NotBlank @Pattern(regexp="[a-f0-9]{32}") String uploadId,
      @NotBlank @Pattern(regexp="(?i)[a-f0-9]{32}") String fileHash) {}
  public record InitResponse(String uploadId, int chunkSize, int totalChunks) {}
  public record StatusResponse(String uploadId, int totalChunks, List<Integer> uploadedChunks) {}
}
