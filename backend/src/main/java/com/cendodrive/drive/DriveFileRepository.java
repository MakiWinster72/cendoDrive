package com.cendodrive.drive;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriveFileRepository extends JpaRepository<DriveFile, Long> {
    List<DriveFile> findAllByOwnerIdAndParentIdIsNullAndDeletedAtIsNullOrderByKindAscNameAsc(Long ownerId);
    List<DriveFile> findAllByOwnerIdAndParentIdAndDeletedAtIsNullOrderByKindAscNameAsc(Long ownerId, Long parentId);
    List<DriveFile> findAllByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long ownerId);
    Optional<DriveFile> findByIdAndOwnerId(Long id, Long ownerId);
    boolean existsByOwnerIdAndParentIdIsNullAndDeletedAtIsNullAndNameIgnoreCase(Long ownerId, String name);
    boolean existsByOwnerIdAndParentIdAndDeletedAtIsNullAndNameIgnoreCase(Long ownerId, Long parentId, String name);
}
