package com.cendodrive.file;

import jakarta.persistence.*;
import java.time.Clock;
import java.time.LocalDateTime;

@Entity
@Table(name = "file_entries")
public class FileEntry {
    public enum EntryType { FILE, FOLDER }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(name = "parent_id", nullable = false) private long parentId;
    @Column(nullable = false, length = 255) private String name;
    @Column(name = "name_key", nullable = false, length = 255) private String nameKey;
    @Enumerated(EnumType.STRING) @Column(name = "entry_type", nullable = false, length = 16)
    private EntryType entryType;
    @Column(name = "size_bytes", nullable = false) private long sizeBytes;
    @Column(name = "storage_provider", length = 32) private String storageProvider;
    @Column(name = "storage_key", length = 512) private String storageKey;
    @Column(name = "content_type", length = 255) private String contentType;
    @Column(name = "content_hash", length = 128) private String contentHash;
    @Column(name = "ingest_key", length = 64) private String ingestKey;
    // Original registration identity must survive later rename/move operations.
    @Column(name = "registration_fingerprint", length = 64) private String registrationFingerprint;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected FileEntry() {}
    static FileEntry folder(long userId, long parentId, FileNamePolicy.Name name) {
        FileEntry entry = new FileEntry();
        entry.userId = userId;
        entry.parentId = parentId;
        entry.rename(name);
        entry.entryType = EntryType.FOLDER;
        return entry;
    }
    static FileEntry storedFile(long userId, FileDtos.RegisterStoredFileCommand command,
                                FileNamePolicy.Name name, String fingerprint) {
        FileEntry entry = folder(userId, command.parentId(), name);
        entry.entryType = EntryType.FILE;
        entry.sizeBytes = command.sizeBytes();
        entry.storageProvider = command.storageProvider();
        entry.storageKey = command.storageKey();
        entry.contentType = command.contentType();
        entry.contentHash = command.contentHash();
        entry.ingestKey = command.ingestKey();
        entry.registrationFingerprint = fingerprint;
        return entry;
    }
    void rename(FileNamePolicy.Name name) { this.name = name.display(); this.nameKey = name.key(); }
    void moveTo(long parentId) { this.parentId = parentId; }
    @PrePersist void created() { createdAt = updatedAt = LocalDateTime.now(Clock.systemUTC()); }
    @PreUpdate void updated() { updatedAt = LocalDateTime.now(Clock.systemUTC()); }
    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public long getParentId() { return parentId; }
    public String getName() { return name; }
    public EntryType getEntryType() { return entryType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getContentType() { return contentType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public String getRegistrationFingerprint() { return registrationFingerprint; }
}
