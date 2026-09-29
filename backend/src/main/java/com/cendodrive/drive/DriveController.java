package com.cendodrive.drive;

import com.cendodrive.drive.DriveDtos.*;
import com.cendodrive.user.User;
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
public class DriveController {
    private final DriveService drive;
    public DriveController(DriveService drive) { this.drive = drive; }
    @GetMapping List<FileResponse> list(@AuthenticationPrincipal User user, @RequestParam(required = false) Long parentId) {
        return drive.list(user, parentId);
    }
    @PostMapping("/folder") ResponseEntity<FileResponse> createFolder(@AuthenticationPrincipal User user,
                                                                       @Valid @RequestBody CreateFolderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(drive.createFolder(user, request));
    }
    @PutMapping("/{id}/rename") FileResponse rename(@AuthenticationPrincipal User user, @PathVariable Long id,
                                                     @Valid @RequestBody RenameRequest request) {
        return drive.rename(user, id, request);
    }
    @PutMapping("/{id}/move") FileResponse move(@AuthenticationPrincipal User user, @PathVariable Long id,
                                                 @Valid @RequestBody MoveRequest request) {
        return drive.move(user, id, request);
    }
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
