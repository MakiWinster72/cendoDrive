package com.cendodrive.drive;

import com.cendodrive.drive.DriveDtos.*;
import com.cendodrive.common.ApiError;
import com.cendodrive.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

@RestController
@RequestMapping("/api/files")
@Tag(name = "文件管理", description = "文件列表、文件夹创建、重命名、移动与下载")
@SecurityRequirement(name = "bearerAuth")
public class DriveController {
    private final DriveService drive;
    public DriveController(DriveService drive) { this.drive = drive; }
    @Operation(summary = "获取目录内容", description = "parentId 不传时返回根目录，结果按类型和名称排序。")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "目录内容"),
            @ApiResponse(responseCode = "401", description = "未认证", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "父目录不存在", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @GetMapping List<FileResponse> list(@AuthenticationPrincipal User user, @RequestParam(required = false) Long parentId) {
        return drive.list(user, parentId);
    }
    @Operation(summary = "获取回收站列表")
    @GetMapping("/trash") List<FileResponse> listTrash(@AuthenticationPrincipal User user) {
        return drive.listTrash(user);
    }
    @Operation(summary = "将文件或文件夹移入回收站")
    @PostMapping("/trash") List<FileResponse> trash(@AuthenticationPrincipal User user,
                                                     @RequestBody FileIdsRequest request) {
        return drive.trash(user, request);
    }
    @Operation(summary = "恢复回收站中的文件或文件夹")
    @PostMapping("/trash/restore") List<FileResponse> restore(@AuthenticationPrincipal User user,
                                                               @RequestBody FileIdsRequest request) {
        return drive.restore(user, request);
    }
    @Operation(summary = "彻底删除回收站中的文件或文件夹")
    @PostMapping("/trash/delete") ResponseEntity<Void> deleteForever(@AuthenticationPrincipal User user,
                                                                       @RequestBody FileIdsRequest request) {
        drive.deleteForever(user, request);
        return ResponseEntity.noContent().build();
    }
    @Operation(summary = "清空回收站")
    @DeleteMapping("/trash") ResponseEntity<Void> emptyTrash(@AuthenticationPrincipal User user) {
        drive.emptyTrash(user);
        return ResponseEntity.noContent().build();
    }
    @Operation(summary = "新建文件夹")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "创建成功"),
            @ApiResponse(responseCode = "400", description = "名称或父目录不合法", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "同目录存在同名项", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/folder") ResponseEntity<FileResponse> createFolder(@AuthenticationPrincipal User user,
                                                                       @Valid @RequestBody CreateFolderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(drive.createFolder(user, request));
    }
    @Operation(summary = "重命名文件或文件夹")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "重命名成功"),
            @ApiResponse(responseCode = "404", description = "目标不存在", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "同目录存在同名项", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PutMapping("/{id}/rename") FileResponse rename(@AuthenticationPrincipal User user, @PathVariable Long id,
                                                     @Valid @RequestBody RenameRequest request) {
        return drive.rename(user, id, request);
    }
    @Operation(summary = "移动文件或文件夹", description = "parentId 为 null 时移到根目录；文件夹不能移到自身或其子孙目录。")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "移动成功"),
            @ApiResponse(responseCode = "400", description = "目标不是文件夹或形成循环", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "文件或目标目录不存在", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "目标目录存在同名项", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PutMapping("/{id}/move") FileResponse move(@AuthenticationPrincipal User user, @PathVariable Long id,
                                                 @Valid @RequestBody MoveRequest request) {
        return drive.move(user, id, request);
    }
    @Operation(summary = "下载文件")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "文件二进制内容", content = @Content(mediaType = "application/octet-stream")),
            @ApiResponse(responseCode = "400", description = "目标是文件夹", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "文件记录或存储内容不存在", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @GetMapping("/{id}/download") ResponseEntity<org.springframework.core.io.Resource> download(
            @AuthenticationPrincipal User user, @PathVariable Long id) throws IOException {
        var result = drive.download(user, id);
        String encoded = UriUtils.encode(result.name(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(result.resource().contentLength())
                .body(result.resource());
    }
}
