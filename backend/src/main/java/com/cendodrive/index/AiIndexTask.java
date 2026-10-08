package com.cendodrive.index;

import com.cendodrive.drive.DriveFile;
import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "ai_index_tasks")
public class AiIndexTask {
  public enum Operation { UPSERT, DEACTIVATE, DELETE }
  public enum Status { PENDING, RETRY, DELIVERED, DEAD }

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name="event_id",nullable=false,length=128,unique=true) private String eventId;
  @Column(name="file_id",nullable=false) private Long fileId;
  @Column(name="owner_id",nullable=false) private Long ownerId;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private Operation operation;
  @Column(nullable=false) private long revision;
  @Column(name="file_name",length=255) private String fileName;
  @Column(name="storage_backend",length=32) private String storageBackend;
  @Column(name="storage_key",length=512) private String storageKey;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private Status status;
  @Column(nullable=false) private int attempts;
  @Column(name="next_attempt_at",nullable=false) private LocalDateTime nextAttemptAt;
  @Column(name="last_error",length=1000) private String lastError;
  @Column(name="created_at",nullable=false) private LocalDateTime createdAt;
  @Column(name="updated_at",nullable=false) private LocalDateTime updatedAt;

  protected AiIndexTask() {}

  public static AiIndexTask upsert(DriveFile file) {
    return lifecycle(file,Operation.UPSERT);
  }

  public static AiIndexTask lifecycle(DriveFile file,Operation operation) {
    AiIndexTask task=new AiIndexTask();
    task.fileId=file.getId(); task.ownerId=file.getOwnerId(); task.operation=operation;
    task.revision=file.getIndexRevision();
    if (operation==Operation.UPSERT) {
      task.fileName=file.getName(); task.storageBackend=file.getStorageBackend(); task.storageKey=file.getStorageKey();
    }
    task.eventId=eventId(task.fileId,task.revision,task.operation);
    task.status=Status.PENDING; task.attempts=0; task.nextAttemptAt=now();
    return task;
  }

  public static String eventId(Long fileId,long revision,Operation operation) {
    return "file-"+fileId+"-"+revision+"-"+operation;
  }

  @PrePersist void created() { createdAt=updatedAt=now(); }
  @PreUpdate void updated() { updatedAt=now(); }
  private static LocalDateTime now() { return LocalDateTime.now(Clock.systemUTC()); }

  public Long getId() { return id; }
  public String getEventId() { return eventId; }
  public Long getFileId() { return fileId; }
  public Long getOwnerId() { return ownerId; }
  public Operation getOperation() { return operation; }
  public long getRevision() { return revision; }
  public String getFileName() { return fileName; }
  public String getStorageBackend() { return storageBackend; }
  public String getStorageKey() { return storageKey; }
  public Status getStatus() { return status; }
  public int getAttempts() { return attempts; }
  public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
  public String getLastError() { return lastError; }

  void delivered() { status=Status.DELIVERED; attempts++; lastError=null; }
  void failed(boolean retryable,String message,Duration delay,int maxAttempts) {
    attempts++;
    lastError=message==null?null:message.substring(0,Math.min(1000,message.length()));
    status=retryable && attempts<maxAttempts?Status.RETRY:Status.DEAD;
    nextAttemptAt=now().plus(delay);
  }
}
