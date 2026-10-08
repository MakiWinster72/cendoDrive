package com.cendodrive.index;

import com.cendodrive.drive.DriveFile;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AiIndexTaskService {
  private final AiIndexTaskRepository tasks;
  public AiIndexTaskService(AiIndexTaskRepository tasks) { this.tasks=tasks; }

  @Transactional(propagation=Propagation.MANDATORY)
  public void enqueueUpsert(DriveFile file) {
    String eventId=AiIndexTask.eventId(file.getId(),file.getIndexRevision(),AiIndexTask.Operation.UPSERT);
    if (!tasks.existsByEventId(eventId)) tasks.save(AiIndexTask.upsert(file));
  }

  @Transactional(readOnly=true)
  public List<AiIndexTask> due() {
    return tasks.findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByIdAsc(
        List.of(AiIndexTask.Status.PENDING,AiIndexTask.Status.RETRY),now());
  }

  @Transactional
  public void delivered(Long id) { tasks.findById(id).ifPresent(AiIndexTask::delivered); }

  @Transactional
  public void failed(Long id,boolean retryable,String message) {
    tasks.findById(id).ifPresent(task -> task.failed(retryable,message,delay(task.getAttempts()),10));
  }

  private static Duration delay(int attempts) {
    long[] minutes={1,5,15,30,60,120,240,480,720};
    return Duration.ofMinutes(minutes[Math.min(attempts,minutes.length-1)]);
  }
  private static LocalDateTime now() { return LocalDateTime.now(Clock.systemUTC()); }
}
