package com.cendodrive.drive;

import com.cendodrive.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.cendodrive.drive.FileSearchDtos.SearchResponse;

@RestController
@RequestMapping("/api/files/search")
@Tag(name = "文件管理")
@SecurityRequirement(name = "bearerAuth")
public class FileSearchController {
  private final FileSearchService search;
  public FileSearchController(FileSearchService search) { this.search = search; }

  @GetMapping
  @Operation(summary = "按文件名搜索", description = "仅搜索当前用户的可见文件名，不读取正文或 OCR。scope=all 为全网盘，folder 包括当前目录及子目录；排除隐藏、回收站及其后代。页码从 0 开始。")
  public SearchResponse search(@AuthenticationPrincipal User user, @RequestParam String q,
      @RequestParam(defaultValue = "all") String scope, @RequestParam(required = false) Long parentId,
      @RequestParam(defaultValue = "all") String type, @RequestParam(defaultValue = "time") String sort,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return search.search(user, q, scope, parentId, type, sort, page, size);
  }
}
