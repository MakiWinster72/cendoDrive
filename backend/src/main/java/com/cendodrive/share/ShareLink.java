package com.cendodrive.share;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "share_links")
public class ShareLink {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "owner_id", nullable = false) private Long ownerId;
  @Column(name = "file_id", nullable = false) private Long fileId;
  @Column(nullable = false, unique = true, length = 32) private String token;
  @Column(name = "file_name", nullable = false) private String fileName;
  @Column(name = "size_bytes", nullable = false) private long size;
  @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
  @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
  @Column(nullable = false) private boolean cancelled;

  protected ShareLink() {}

  static ShareLink create(Long ownerId, Long fileId, String token, String name, long size,
      Instant now, long seconds) {
    ShareLink link = new ShareLink();
    link.ownerId = ownerId;
    link.fileId = fileId;
    link.token = token;
    link.fileName = name;
    link.size = size;
    link.createdAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    link.expiresAt = LocalDateTime.ofInstant(now.plusSeconds(seconds), ZoneOffset.UTC);
    return link;
  }

  public Long getId() { return id; }
  public Long getOwnerId() { return ownerId; }
  public Long getFileId() { return fileId; }
  public String getToken() { return token; }
  public String getFileName() { return fileName; }
  public long getSize() { return size; }
  public Instant getCreatedAt() { return createdAt.toInstant(ZoneOffset.UTC); }
  public Instant getExpiresAt() { return expiresAt.toInstant(ZoneOffset.UTC); }
  public boolean isCancelled() { return cancelled; }
  public boolean isActiveAt(Instant now) { return !cancelled && now.isBefore(getExpiresAt()); }
  void cancel() { cancelled = true; }
}
