package com.cendodrive.index;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiIndexWorker {
  private final AiIndexTaskService tasks;
  private final AiIndexClient client;
  public AiIndexWorker(AiIndexTaskService tasks,AiIndexClient client) { this.tasks=tasks; this.client=client; }

  @Scheduled(fixedDelayString="${cendo.ai.worker-delay-ms:5000}",initialDelayString="${cendo.ai.worker-delay-ms:5000}")
  public void deliver() {
    if (!client.configured()) return;
    for (AiIndexTask task:tasks.due()) {
      try { client.deliver(task); tasks.delivered(task.getId()); }
      catch (AiIndexClient.PermanentDeliveryException ex) { tasks.failed(task.getId(),false,ex.getMessage()); }
      catch (RuntimeException ex) { tasks.failed(task.getId(),true,ex.getMessage()); }
    }
  }
}
