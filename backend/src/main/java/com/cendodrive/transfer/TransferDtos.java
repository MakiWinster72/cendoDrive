package com.cendodrive.transfer;

import jakarta.validation.constraints.*;

public class TransferDtos {
  public record SaveRequest(
      @NotBlank @Size(max = 512) String id,
      @NotBlank @Pattern(regexp = "upload|download|transfer") String direction,
      @NotBlank @Size(max = 255) String name,
      @PositiveOrZero long size,
      @NotBlank @Pattern(regexp = "success|failed|cancelled") String status,
      @Min(0) @Max(100) int progress,
      @Min(0) long createdAt,
      @Size(max = 1000) String error,
      @Positive Long fileId) {}
  public record TransferResponse(String id, String direction, String name, long size,
      String status, int progress, long createdAt, String error, String fileId) {}
}
