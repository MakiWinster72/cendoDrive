package com.cendodrive.drive;

import java.util.List;
import com.cendodrive.drive.DriveDtos.FileResponse;

public final class FileSearchDtos {
  private FileSearchDtos() {
  }

  public record SearchHit(FileResponse file, List<FileResponse> ancestors, String path) {
  }

  public record SearchResponse(List<SearchHit> items, long total, int page, int size) {
  }
}
