package com.cendodrive.ai;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/** Dedicated bounded workers, without replacing Boot's executor used by file downloads. */
@Component
public class AiChatExecutor {
  private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
  public AiChatExecutor() {
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(16);
    executor.setThreadNamePrefix("ai-chat-");
    executor.initialize();
  }
  public void execute(Runnable task) { executor.execute(task); }
  @PreDestroy public void shutdown() { executor.shutdown(); }
}
