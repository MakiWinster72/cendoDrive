package com.cendodrive.storage;

import com.cendodrive.drive.DriveFile;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class StorageCleanupService {
  private final StorageCleanupTaskRepository tasks;
  private final FileStorage fastDfs;
  private final Path storageRoot;

  public StorageCleanupService(StorageCleanupTaskRepository tasks, FileStorage fastDfs,
      @Value("${cendo.storage.root:./storage}") String storageRoot) {
    this.tasks = tasks;
    this.fastDfs = fastDfs;
    this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(DriveFile file) {
    String backend = file.getStorageBackend(), key = file.getStorageKey();
    if (key == null || key.isBlank() || tasks.existsByStorageBackendAndStorageKey(backend, key))
      return;
    tasks.save(new StorageCleanupTask(backend, key));
  }

  @Transactional(readOnly = true)
  public List<StorageCleanupTask> due() {
    return tasks.findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByIdAsc(
        List.of(StorageCleanupTask.Status.PENDING, StorageCleanupTask.Status.RETRY), now());
  }

  @Transactional
  public void execute(Long id) {
    StorageCleanupTask task = tasks.findById(id).orElse(null);
    if (task == null || (task.getStatus() != StorageCleanupTask.Status.PENDING
        && task.getStatus() != StorageCleanupTask.Status.RETRY))
      return;
    try {
      delete(task.getStorageBackend(), task.getStorageKey());
      task.succeeded();
    } catch (IOException | RuntimeException ex) {
      task.failed(ex.getMessage());
    }
  }

  private void delete(String backend, String key) throws IOException {
    if ("fastdfs".equals(backend)) {
      fastDfs.delete(key);
      return;
    }
    if ("local".equals(backend)) {
      Path path = storageRoot.resolve(key).normalize();
      if (!path.startsWith(storageRoot))
        throw new IOException("Storage path escapes configured root");
      Files.deleteIfExists(path);
      return;
    }
    throw new IOException("Unknown storage backend: " + backend);
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(Clock.systemUTC());
  }
}
