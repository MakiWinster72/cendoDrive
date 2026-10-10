package com.cendodrive.upload;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
public class UploadCleanup {
  private final UploadService uploads;

  public UploadCleanup(UploadService uploads) {
    this.uploads = uploads;
  }

  @Scheduled(fixedDelayString = "${cendo.upload.cleanup-delay-ms:3600000}", initialDelayString = "${cendo.upload.cleanup-delay-ms:3600000}")
  public void cleanup() {
    uploads.cleanupExpired();
  }
}
