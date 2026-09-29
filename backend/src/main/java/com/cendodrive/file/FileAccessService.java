package com.cendodrive.file;

import com.cendodrive.common.ApiExceptionHandler.AuthFailure;
import com.cendodrive.user.User;
import com.cendodrive.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileAccessService {
    private final FileEntryRepository entries;
    private final UserRepository users;
    public FileAccessService(FileEntryRepository entries, UserRepository users) {
        this.entries = entries;
        this.users = users;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public User lockActiveUser(long userId) {
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthFailure(HttpStatus.UNAUTHORIZED, "Unauthorized"));
        if (!user.isActive()) throw new AuthFailure(HttpStatus.UNAUTHORIZED, "Unauthorized");
        return user;
    }
    public FileEntry requireOwnedEntry(long userId, long entryId) {
        if (entryId <= 0) throw FileBusinessException.notFound();
        return entries.findByIdAndUserId(entryId, userId).orElseThrow(FileBusinessException::notFound);
    }
    public void requireOwnedFolderOrRoot(long userId, long parentId) {
        if (parentId == 0) return;
        FileEntry parent = requireOwnedEntry(userId, parentId);
        if (parent.getEntryType() != FileEntry.EntryType.FOLDER) {
            throw FileBusinessException.invalid("NOT_A_FOLDER", "Target is not a folder");
        }
    }
    public void requireAvailableName(long userId, long parentId, String key, Long excludedId) {
        boolean exists = excludedId == null
                ? entries.existsByUserIdAndParentIdAndNameKey(userId, parentId, key)
                : entries.existsByUserIdAndParentIdAndNameKeyAndIdNot(userId, parentId, key, excludedId);
        if (exists) throw FileBusinessException.conflict("NAME_CONFLICT", "Name already exists in this folder");
    }
}
