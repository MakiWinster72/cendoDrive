package com.cendodrive.ai.claw;

import jakarta.validation.constraints.*;
import java.util.List;

public final class ClawDtos {
  private ClawDtos() {}
  public record Question(@NotBlank @Size(max=2000) String question,@Size(max=300) String previousQuestion) {}
  public record Source(int citation,String fileId,String name,String path,String excerpt) {}
  public record Answer(String content,List<Source> sources,boolean matched) {}
  public record IndexFile(String fileId,String name,String path,String status,String errorCode,int chunks) {}
  public record Status(boolean configured,String embeddingModel,int ready,int pending,int failed,int unsupported,int needsReindex,List<IndexFile> files) {}
  public record ReindexRequest(@AssertTrue boolean confirmed) {}
  public record ReindexResult(int queued) {}
}
