package com.cendodrive.user;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(nullable = false, length = 64, unique = true)
  private String username;
  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;
  @Column(nullable = false, length = 64)
  private String nickname;
  @Column(nullable = false, length = 32)
  private String role = "USER";
  @Column(name = "vip_level", nullable = false, length = 32)
  private String vipLevel = "NORMAL";
  @Column(name = "storage_used", nullable = false)
  private long storageUsed = 0;
  @Column(name = "storage_limit", nullable = false)
  private long storageLimit = 1073741824L;
  @Column(name = "is_active", nullable = false)
  private boolean active = true;
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  protected User() {
  }

  public User(String username, String passwordHash, String nickname) {
    this.username = username;
    this.passwordHash = passwordHash;
    this.nickname = nickname;
  }

  @PrePersist
  void created() {
    createdAt = updatedAt = LocalDateTime.now(java.time.Clock.systemUTC());
  }

  @PreUpdate
  void updated() {
    updatedAt = LocalDateTime.now(java.time.Clock.systemUTC());
  }

  public Long getId() {
    return id;
  }

  public String getUsername() {
    return username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getNickname() {
    return nickname;
  }

  public String getRole() {
    return role;
  }

  public String getVipLevel() {
    return vipLevel;
  }

  public long getStorageUsed() {
    return storageUsed;
  }

  public long getStorageLimit() {
    return storageLimit;
  }

  public boolean isActive() {
    return active;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
