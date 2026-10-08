package com.cendodrive.drive;

import jakarta.validation.constraints.*;
import java.util.List;

public final class FileFeatureDtos {
  private FileFeatureDtos() {}
  public record FlagRequest(@NotEmpty @Size(max=1000) List<@NotNull @Positive Long> ids,@NotNull Boolean value) {}
  public record TargetRequest(@NotEmpty @Size(max=1000) List<@NotNull @Positive Long> ids,@Positive Long parentId) {}
  public record OrganizeRequest(@NotEmpty @Size(max=1000) List<@NotNull @Positive Long> ids) {}
  public record Details(DriveDtos.FileResponse file,String createdAt,String path,long contentSize,long fileCount,long folderCount) {}
}
