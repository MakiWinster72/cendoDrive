package com.cendodrive.drive;

import com.cendodrive.drive.DriveDtos.FileResponse;
import com.cendodrive.drive.FileFeatureDtos.*;
import com.cendodrive.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files")
@Tag(name = "文件扩展", description = "收藏、隐藏、目录选择、详情、复制与规则整理")
@SecurityRequirement(name = "bearerAuth")
public class FileFeatureController {
  private final FileFeatureService features;
  private final FileQuotaService quota;

  public FileFeatureController(FileFeatureService features, FileQuotaService quota) {
    this.features = features;
    this.quota = quota;
  }

  @GetMapping("/usage")
  @Operation(summary = "真实容量（含回收站、隐藏文件和上传预留）")
  FileQuotaService.Usage usage(@AuthenticationPrincipal User user) {
    return quota.usage(user);
  }

  @GetMapping("/favorites")
  @Operation(summary = "获取可见收藏列表")
  List<FileResponse> favorites(@AuthenticationPrincipal User user) {
    return features.favorites(user);
  }

  @GetMapping("/hidden")
  @Operation(summary = "获取隐藏内容入口（非加密保险箱）")
  List<FileResponse> hidden(@AuthenticationPrincipal User user) {
    return features.hidden(user);
  }

  @GetMapping("/folders")
  @Operation(summary = "可选目标目录列表，排除回收站子树")
  List<FileResponse> folders(@AuthenticationPrincipal User user) {
    return features.folders(user);
  }

  @PostMapping("/favorite")
  @Operation(summary = "批量设置收藏状态")
  List<FileResponse> favorite(@AuthenticationPrincipal User user, @Valid @RequestBody FlagRequest request) {
    return features.flag(user, request, false);
  }

  @PostMapping("/hidden")
  @Operation(summary = "批量隐藏或取消隐藏")
  List<FileResponse> hide(@AuthenticationPrincipal User user, @Valid @RequestBody FlagRequest request) {
    return features.flag(user, request, true);
  }

  @GetMapping("/{id}/details")
  @Operation(summary = "获取文件详情与目录统计，不暴露存储凭据")
  Details details(@AuthenticationPrincipal User user, @PathVariable Long id) {
    return features.details(user, id);
  }

  @PostMapping("/move")
  @Operation(summary = "批量移动，不覆盖同名内容")
  List<FileResponse> move(@AuthenticationPrincipal User user, @Valid @RequestBody TargetRequest request) {
    return features.move(user, request);
  }

  @PostMapping("/copy")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "独立复制文件或目录，自动生成副本名称")
  List<FileResponse> copy(@AuthenticationPrincipal User user, @Valid @RequestBody TargetRequest request)
      throws IOException {
    return features.copy(user, request);
  }

  @PostMapping("/organize")
  @Operation(summary = "按扩展名分类整理至图片、视频、音频、文档或其他目录")
  List<FileResponse> organize(@AuthenticationPrincipal User user, @Valid @RequestBody OrganizeRequest request) {
    return features.organize(user, request);
  }
}
