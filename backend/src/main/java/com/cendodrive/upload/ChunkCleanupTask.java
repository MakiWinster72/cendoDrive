package com.cendodrive.upload;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 清理孤儿分片：Redis 会话已过期（或从未写入）且目录超过宽限期仍未改动时删除。
 * 默认关闭，由 cendo.upload.cleanup-enabled 打开；失败只记录日志，不影响业务请求。
 */
@Component
@ConditionalOnProperty(prefix = "cendo.upload", name = "cleanup-enabled", havingValue = "true")
public class ChunkCleanupTask {
  private static final Logger log = LoggerFactory.getLogger(ChunkCleanupTask.class);
  private final ChunkStorage chunks;
  private final UploadStateStore state;
  private final Duration grace;

  public ChunkCleanupTask(ChunkStorage chunks, UploadStateStore state,
      @Value("${cendo.upload.cleanup-grace-minutes:1440}") long graceMinutes) {
    this.chunks = chunks;
    this.state = state;
    this.grace = Duration.ofMinutes(graceMinutes);
  }

  @Scheduled(fixedDelayString = "${cendo.upload.cleanup-interval-minutes:60}", initialDelayString = "${cendo.upload.cleanup-interval-minutes:60}", timeUnit = TimeUnit.MINUTES)
  public void evictOrphans() {
    Instant cutoff = Instant.now().minus(grace);
    for (ChunkStorage.Session session : chunks.listSessions()) {
      if (session.lastModified().isAfter(cutoff))
        continue;
      if (state.findMeta(session.uploadId()).isPresent())
        continue;
      chunks.cleanup(session.uploadId());
      log.info("Removed orphan chunk session {}", session.uploadId());
    }
  }
}
