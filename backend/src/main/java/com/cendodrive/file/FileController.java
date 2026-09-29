package com.cendodrive.file;

import com.cendodrive.file.FileDtos.*;
import com.cendodrive.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files")
@Tag(name = "文件管理")
@SecurityRequirement(name = "bearerAuth")
public class FileController {
    private final FileService files;
    public FileController(FileService files) { this.files = files; }

    @GetMapping
    @Operation(summary = "查询目录直接子项", description = "parentId=0 为根目录；文件夹优先，ID 升序；size 为 1-100。")
    public FileListResponse list(@AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") long parentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return files.list(user.getId(), parentId, page, size);
    }
    @PostMapping("/folder")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "新建文件夹")
    public FileEntryResponse create(@AuthenticationPrincipal User user, @Valid @RequestBody CreateFolderRequest request) {
        return files.createFolder(user.getId(), request.parentId(), request.name());
    }
    @PutMapping("/{id}/rename")
    @Operation(summary = "重命名文件或文件夹")
    public FileEntryResponse rename(@AuthenticationPrincipal User user, @PathVariable long id,
                                    @Valid @RequestBody RenameRequest request) {
        return files.rename(user.getId(), id, request.name());
    }
    @PutMapping("/{id}/move")
    @Operation(summary = "移动文件或文件夹", description = "禁止移动到自身或后代目录。")
    public FileEntryResponse move(@AuthenticationPrincipal User user, @PathVariable long id,
                                  @Valid @RequestBody MoveRequest request) {
        return files.move(user.getId(), id, request.targetParentId());
    }
}
