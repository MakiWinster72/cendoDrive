package com.cendodrive.drive;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.upload.UploadSessionRepository;
import com.cendodrive.user.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class FileQuotaService {
  private final UserRepository users;
  private final DriveFileRepository files;
  private final UploadSessionRepository uploads;

  public FileQuotaService(UserRepository users, DriveFileRepository files, UploadSessionRepository uploads) {
    this.users = users;
    this.files = files;
    this.uploads = uploads;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public User lock(User user) {
    User locked = users.lock(user.getId())
        .orElseThrow(() -> new DriveFailure(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
    if (!locked.isActive() || locked.getAuthVersion() != user.getAuthVersion())
      throw new com.cendodrive.common.ApiExceptionHandler.AuthFailure(HttpStatus.UNAUTHORIZED, "Unauthorized");
    return locked;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public User check(User user, long additionalBytes, String uploadId) {
    User locked = lock(user);
    long used = files.usedBytes(user.getId());
    long pending = uploads.reservedExcluding(user.getId(), now(), uploadId);
    long available = Math.max(0, locked.getStorageLimit() - used - pending);
    if (additionalBytes < 0 || additionalBytes > available)
      throw new DriveFailure(HttpStatus.INSUFFICIENT_STORAGE, "QUOTA_EXCEEDED", "Storage quota exceeded");
    return locked;
  }

  public void refresh(User locked) {
    locked.setStorageUsed(files.usedBytes(locked.getId()));
  }

  @Transactional(readOnly = true)
  public Usage usage(User user) {
    List<DriveFile> all = files.findAllByOwnerId(user.getId());
    Map<Long, DriveFile> byId = new HashMap<>();
    all.forEach(f -> byId.put(f.getId(), f));
    long used = 0, trash = 0;
    for (DriveFile f : all) {
      if (f.isFolder())
        continue;
      used += f.getSize();
      DriveFile cursor = f;
      Set<Long> seen = new HashSet<>();
      while (cursor != null && seen.add(cursor.getId())) {
        if (cursor.isDeleted()) {
          trash += f.getSize();
          break;
        }
        cursor = byId.get(cursor.getParentId());
      }
    }
    long reserved = uploads.reserved(user.getId(), now());
    return new Usage(used, user.getStorageLimit(), Math.max(0, user.getStorageLimit() - used - reserved), trash,
        reserved);
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(Clock.systemUTC());
  }

  public record Usage(long usedBytes, long limitBytes, long availableBytes, long trashBytes, long reservedBytes) {
  }
}
