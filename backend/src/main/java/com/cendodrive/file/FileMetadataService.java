package com.cendodrive.file;

import com.cendodrive.file.FileDtos.*;
import com.cendodrive.user.User;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileMetadataService {
    private final FileEntryRepository entries;
    private final FileAccessService access;
    private final FileNamePolicy names;
    public FileMetadataService(FileEntryRepository entries, FileAccessService access, FileNamePolicy names) {
        this.entries = entries;
        this.access = access;
        this.names = names;
    }
    /** Called only with an authenticated owner and server-verified upload results; performs no storage I/O. */
    @Transactional
    public FileEntryResponse registerStoredFile(long userId, RegisterStoredFileCommand command) {
        User user = access.lockActiveUser(userId);
        validate(command);
        var name = names.normalize(command.name());
        String fingerprint = fingerprint(command, name.display());
        var previous = entries.findByUserIdAndIngestKey(userId, command.ingestKey());
        if (previous.isPresent()) {
            if (!fingerprint.equals(previous.get().getRegistrationFingerprint())) {
                throw FileBusinessException.conflict("IDEMPOTENCY_CONFLICT", "Upload task was registered with different metadata");
            }
            return FileEntryResponse.from(previous.get());
        }
        access.requireOwnedFolderOrRoot(userId, command.parentId());
        access.requireAvailableName(userId, command.parentId(), name.key(), null);
        if (user.getStorageUsed() < 0 || user.getStorageUsed() > user.getStorageLimit()
                || command.sizeBytes() > user.getStorageLimit() - user.getStorageUsed()) {
            throw FileBusinessException.conflict("STORAGE_QUOTA_EXCEEDED", "Insufficient storage quota");
        }
        user.addStorageUsed(command.sizeBytes());
        return FileEntryResponse.from(entries.saveAndFlush(FileEntry.storedFile(userId, command, name, fingerprint)));
    }
    private void validate(RegisterStoredFileCommand c) {
        if (c == null || c.parentId() < 0 || c.sizeBytes() < 0
                || !required(c.storageProvider(), 32) || !required(c.storageKey(), 512)
                || !optional(c.contentType(), 255) || !optional(c.contentHash(), 128)
                || c.ingestKey() == null || !c.ingestKey().matches("[A-Za-z0-9_-]{1,64}")) {
            throw FileBusinessException.invalid("INVALID_METADATA", "Invalid server-side upload metadata");
        }
    }
    private boolean required(String value, int max) { return value != null && !value.isBlank() && optional(value, max); }
    private boolean optional(String value, int max) {
        return value == null || (value.length() <= max && value.codePoints().noneMatch(Character::isISOControl));
    }
    private String fingerprint(RegisterStoredFileCommand c, String normalizedName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[]{Long.toString(c.parentId()), normalizedName, Long.toString(c.sizeBytes()),
                    c.storageProvider(), c.storageKey(), c.contentType(), c.contentHash()}) {
                byte[] bytes = field == null ? new byte[0] : field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(field == null ? -1 : bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
