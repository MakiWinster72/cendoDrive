package com.cendodrive.storage;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StorageCleanupWorker {
  private final StorageCleanupService cleanup;
  public StorageCleanupWorker(StorageCleanupService cleanup) { this.cleanup=cleanup; }
  @Scheduled(fixedDelayString="${cendo.storage.cleanup-delay-ms:30000}",
      initialDelayString="${cendo.storage.cleanup-delay-ms:30000}")
  public void cleanup() { cleanup.due().forEach(task -> cleanup.execute(task.getId())); }
}
