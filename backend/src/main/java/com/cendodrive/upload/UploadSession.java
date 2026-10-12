package com.cendodrive.upload;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "upload_sessions")
public class UploadSession {
  @Id
  private String id;
  @Column(name = "owner_id", nullable = false)
  private Long ownerId;
  @Column(name = "parent_id")
  private Long parentId;
  @Column(name = "file_name", nullable = false, length = 255)
  private String fileName;
  @Column(name = "file_size", nullable = false)
  private long fileSize;
  @Column(name = "file_hash", nullable = false, length = 32)
  private String fileHash;
  @Column(name = "chunk_size", nullable = false)
  private int chunkSize;
  @Column(name = "total_chunks", nullable = false)
  private int totalChunks;
  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;
  @Column(name = "result_file_id")
  private Long resultFileId;

  protected UploadSession() {
  }

  public UploadSession(String id, Long ownerId, Long parentId, String name, long size, String hash,
      int chunkSize, int totalChunks, LocalDateTime expiresAt) {
    this.id = id;
    this.ownerId = ownerId;
    this.parentId = parentId;
    this.fileName = name;
    this.fileSize = size;
    this.fileHash = hash;
    this.chunkSize = chunkSize;
    this.totalChunks = totalChunks;
    this.expiresAt = expiresAt;
  }

  public String getId() {
    return id;
  }

  public Long getOwnerId() {
    return ownerId;
  }

  public Long getParentId() {
    return parentId;
  }

  public String getFileName() {
    return fileName;
  }

  public long getFileSize() {
    return fileSize;
  }

  public String getFileHash() {
    return fileHash;
  }

  public int getChunkSize() {
    return chunkSize;
  }

  public int getTotalChunks() {
    return totalChunks;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public Long getResultFileId() {
    return resultFileId;
  }

  public void complete(Long fileId) {
    resultFileId = fileId;
  }
}
