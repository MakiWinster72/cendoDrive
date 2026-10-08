package com.cendodrive.user;

import com.cendodrive.drive.*;
import com.cendodrive.share.ShareLinkRepository;
import com.cendodrive.storage.FileStorage;
import com.cendodrive.upload.UploadSessionRepository;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.Comparator;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class UserDeletionCleanup {
  private static final Logger LOG=LoggerFactory.getLogger(UserDeletionCleanup.class);
  private final UserRepository users;
  private final DriveFileRepository files;
  private final ShareLinkRepository shares;
  private final UploadSessionRepository uploads;
  private final UserAvatarRepository avatars;
  private final FileStorage storage;
  private final Path uploadRoot, storageRoot;
  private final Clock clock;
  private final TransactionTemplate tx;
  public UserDeletionCleanup(UserRepository users, DriveFileRepository files, ShareLinkRepository shares,
      UploadSessionRepository uploads, UserAvatarRepository avatars, FileStorage storage,
      PlatformTransactionManager manager, Clock clock,
      @Value("${cendo.upload.root:./storage/uploads}") String uploadRoot,
      @Value("${cendo.storage.root:./storage}") String storageRoot) {
    this.users=users; this.files=files; this.shares=shares; this.uploads=uploads; this.avatars=avatars;
    this.storage=storage; this.tx=new TransactionTemplate(manager); this.clock=clock;
    this.uploadRoot=Path.of(uploadRoot).toAbsolutePath().normalize();
    this.storageRoot=Path.of(storageRoot).toAbsolutePath().normalize();
  }
  @Scheduled(fixedDelayString="${cendo.user.cleanup-delay-ms:3600000}",initialDelayString="${cendo.user.cleanup-delay-ms:3600000}")
  public void cleanup() {
    for (User user:users.findTop20ByDeletedAtLessThanEqualOrderByDeletedAtAsc(cutoff())) {
      try { purge(user.getId()); }
      catch (RuntimeException ex) { LOG.warn("Account purge failed; retained for retry, userId={}",user.getId(),ex); }
    }
  }
  // Each blob deletion commits independently. A failed blob leaves its metadata for retry.
  // Never delete a folder first: its FK cascade would erase child keys before physical cleanup.
  public void purge(Long id) {
    while (Boolean.TRUE.equals(tx.execute(status -> {
      User user=users.lock(id).orElse(null);
      if (!due(user)) return false;
      var candidates=files.findTop100ByOwnerIdAndKindOrderByIdAsc(id,"file");
      if (candidates.isEmpty()) return false;
      DriveFile file=candidates.get(0);
      try { deleteStorage(file); }
      catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
      files.delete(file); files.flush();
      return true;
    }))) { /* Persist progress before processing another key. */ }
    tx.executeWithoutResult(status -> {
      User user=users.lock(id).orElse(null);
      if (!due(user)) return;
      if (!files.findTop100ByOwnerIdAndKindOrderByIdAsc(id,"file").isEmpty()) return;
      try { removeStaging(uploadRoot.resolve(id.toString())); }
      catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
      shares.deleteAllByOwnerId(id);
      uploads.deleteAllByOwnerId(id);
      avatars.deleteById(id);
      files.deleteAllByOwnerId(id);
      users.delete(user); users.flush();
    });
  }
  private boolean due(User user) {
    return user!=null && !user.isActive() && user.getDeletedAt()!=null && !user.getDeletedAt().isAfter(cutoff());
  }
  private LocalDateTime cutoff() { return LocalDateTime.now(clock).minusDays(UserService.DELETION_DAYS); }
  private void deleteStorage(DriveFile file) throws IOException {
    String key=file.getStorageKey();
    if (key==null || key.isBlank()) return;
    if ("fastdfs".equals(file.getStorageBackend())) storage.delete(key);
    else if ("local".equals(file.getStorageBackend())) {
      Path path=storageRoot.resolve(key).normalize();
      if (!path.startsWith(storageRoot) || path.equals(storageRoot)) throw new IOException("Invalid legacy storage path");
      Files.deleteIfExists(path);
    } else throw new IOException("Unknown storage backend");
  }
  private static void removeStaging(Path directory) throws IOException {
    if (!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) return;
    // Files.walk does not follow symlinks, including a symlink used as the owner directory.
    try (var paths=Files.walk(directory)) {
      for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }
}
