package com.cendodrive.file;

import com.cendodrive.file.FileDtos.*;
import java.util.HashSet;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileService {
    private final FileEntryRepository entries;
    private final FileAccessService access;
    private final FileNamePolicy names;
    public FileService(FileEntryRepository entries, FileAccessService access, FileNamePolicy names) {
        this.entries = entries;
        this.access = access;
        this.names = names;
    }
    @Transactional(readOnly = true)
    public FileListResponse list(long userId, long parentId, int page, int size) {
        if (parentId < 0 || page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw FileBusinessException.invalid("INVALID_INPUT", "Invalid directory or pagination parameters");
        }
        access.requireOwnedFolderOrRoot(userId, parentId);
        var result = entries.findByUserIdAndParentId(userId, parentId,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("entryType"), Sort.Order.asc("id"))));
        return new FileListResponse(Long.toString(parentId), result.map(FileEntryResponse::from).getContent(),
                page, size, result.getTotalElements());
    }
    @Transactional
    public FileEntryResponse createFolder(long userId, long parentId, String name) {
        access.lockActiveUser(userId);
        access.requireOwnedFolderOrRoot(userId, parentId);
        var normalized = names.normalize(name);
        access.requireAvailableName(userId, parentId, normalized.key(), null);
        return FileEntryResponse.from(entries.saveAndFlush(FileEntry.folder(userId, parentId, normalized)));
    }
    @Transactional
    public FileEntryResponse rename(long userId, long id, String name) {
        access.lockActiveUser(userId);
        FileEntry entry = access.requireOwnedEntry(userId, id);
        var normalized = names.normalize(name);
        access.requireAvailableName(userId, entry.getParentId(), normalized.key(), id);
        entry.rename(normalized);
        entries.flush();
        return FileEntryResponse.from(entry);
    }
    @Transactional
    public FileEntryResponse move(long userId, long id, long targetParentId) {
        access.lockActiveUser(userId);
        FileEntry entry = access.requireOwnedEntry(userId, id);
        access.requireOwnedFolderOrRoot(userId, targetParentId);
        if (targetParentId == entry.getParentId()) return FileEntryResponse.from(entry);
        if (entry.getEntryType() == FileEntry.EntryType.FOLDER) {
            var visited = new HashSet<Long>();
            long ancestor = targetParentId;
            while (ancestor != 0) {
                if (ancestor == id || !visited.add(ancestor)) {
                    throw FileBusinessException.invalid("INVALID_MOVE", "Cannot create a directory cycle");
                }
                FileEntry parent = access.requireOwnedEntry(userId, ancestor);
                if (parent.getEntryType() != FileEntry.EntryType.FOLDER) {
                    throw FileBusinessException.invalid("NOT_A_FOLDER", "Invalid directory ancestry");
                }
                ancestor = parent.getParentId();
            }
        }
        access.requireAvailableName(userId, targetParentId, names.normalize(entry.getName()).key(), id);
        entry.moveTo(targetParentId);
        entries.flush();
        return FileEntryResponse.from(entry);
    }
}
