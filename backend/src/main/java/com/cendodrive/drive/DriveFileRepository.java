package com.cendodrive.drive;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriveFileRepository extends JpaRepository<DriveFile, Long> {
    List<DriveFile> findAllByOwnerIdAndParentIdIsNullOrderByKindAscNameAsc(Long ownerId);
    List<DriveFile> findAllByOwnerIdAndParentIdOrderByKindAscNameAsc(Long ownerId, Long parentId);
    Optional<DriveFile> findByIdAndOwnerId(Long id, Long ownerId);
    boolean existsByOwnerIdAndParentIdIsNullAndNameIgnoreCase(Long ownerId, String name);
    boolean existsByOwnerIdAndParentIdAndNameIgnoreCase(Long ownerId, Long parentId, String name);
}
