package com.cendodrive.drive;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "drive_files")
public class DriveFile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;
    @Column(name = "parent_id")
    private Long parentId;
    @Column(nullable = false, length = 255)
    private String name;
    @Column(nullable = false, length = 32)
    private String kind;
    @Column(name = "size_bytes", nullable = false)
    private long size;
    @Column(name = "storage_key", length = 512)
    private String storageKey;
    @Column(name = "storage_backend", nullable = false, length = 32)
    private String storageBackend = "local";
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    static final String TRASH_MARKER = "~trash~";

    protected DriveFile() {}
    private DriveFile(Long ownerId, Long parentId, String name) {
        this.ownerId = ownerId;
        this.parentId = parentId;
        this.name = name;
        this.kind = "folder";
    }
    public static DriveFile folder(Long ownerId, Long parentId, String name) {
        return new DriveFile(ownerId, parentId, name);
    }
    public static DriveFile uploaded(Long ownerId, Long parentId, String name, long size, String key) {
        DriveFile file = new DriveFile(ownerId, parentId, name);
        file.kind = "file";
        file.size = size;
        file.storageKey = key;
        file.storageBackend = "fastdfs";
        return file;
    }
    @PrePersist void created() { createdAt = updatedAt = LocalDateTime.now(java.time.Clock.systemUTC()); }
    @PreUpdate void updated() { updatedAt = LocalDateTime.now(java.time.Clock.systemUTC()); }
    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public Long getParentId() { return parentId; }
    public String getName() { return name; }
    public String getKind() { return kind; }
    public long getSize() { return size; }
    public String getStorageKey() { return storageKey; }
    public String getStorageBackend() { return storageBackend; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public boolean isFolder() { return "folder".equals(kind); }
    public boolean isDeleted() { return deletedAt != null; }
    void rename(String value) { name = value; }
    void moveTo(Long value) { parentId = value; }
    void moveToTrash() {
        deletedAt = LocalDateTime.now(java.time.Clock.systemUTC());
        name = name + TRASH_MARKER + id;
    }
    void restore() {
        deletedAt = null;
        name = displayName(name);
    }
    static String displayName(String value) {
        int marker = value.lastIndexOf(TRASH_MARKER);
        return marker < 0 ? value : value.substring(0, marker);
    }
}
