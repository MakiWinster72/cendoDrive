package com.cendodrive.storage;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "storage_cleanup_tasks")
public class StorageCleanupTask {
  public enum Status {
    PENDING, RETRY, SUCCEEDED, DEAD
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "storage_backend", nullable = false, length = 32)
  private String storageBackend;
  @Column(name = "storage_key", nullable = false, length = 512)
  private String storageKey;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private Status status;
  @Column(nullable = false)
  private int attempts;
  @Column(name = "next_attempt_at", nullable = false)
  private LocalDateTime nextAttemptAt;
  @Column(name = "last_error", length = 1000)
  private String lastError;
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  protected StorageCleanupTask() {
  }

  public StorageCleanupTask(String backend, String key) {
    storageBackend = backend;
    storageKey = key;
    status = Status.PENDING;
    nextAttemptAt = now();
  }

  @PrePersist
  void created() {
    createdAt = updatedAt = now();
  }

  @PreUpdate
  void updated() {
    updatedAt = now();
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(Clock.systemUTC());
  }

  public Long getId() {
    return id;
  }

  public String getStorageBackend() {
    return storageBackend;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public Status getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public LocalDateTime getNextAttemptAt() {
    return nextAttemptAt;
  }

  public String getLastError() {
    return lastError;
  }

  void succeeded() {
    status = Status.SUCCEEDED;
    attempts++;
    lastError = null;
  }

  void failed(String message) {
    attempts++;
    lastError = message == null ? null : message.substring(0, Math.min(1000, message.length()));
    status = attempts < 10 ? Status.RETRY : Status.DEAD;
    nextAttemptAt = now().plus(delay(attempts));
  }

  private static Duration delay(int attempts) {
    long[] minutes = { 1, 5, 15, 30, 60, 120, 240, 480, 720 };
    return Duration.ofMinutes(minutes[Math.min(Math.max(0, attempts - 1), minutes.length - 1)]);
  }
}
