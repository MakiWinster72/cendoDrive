package com.cendodrive.file;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileEntryRepository extends JpaRepository<FileEntry, Long> {
    Optional<FileEntry> findByIdAndUserId(long id, long userId);
    Optional<FileEntry> findByUserIdAndIngestKey(long userId, String ingestKey);
    boolean existsByUserIdAndParentIdAndNameKey(long userId, long parentId, String nameKey);
    boolean existsByUserIdAndParentIdAndNameKeyAndIdNot(long userId, long parentId, String nameKey, long id);
    Page<FileEntry> findByUserIdAndParentId(long userId, long parentId, Pageable pageable);
}
