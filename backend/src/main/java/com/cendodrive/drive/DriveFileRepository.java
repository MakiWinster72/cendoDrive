package com.cendodrive.drive;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DriveFileRepository extends JpaRepository<DriveFile, Long> {
    List<DriveFile> findAllByOwnerIdAndParentIdIsNullAndDeletedAtIsNullOrderByKindAscNameAsc(Long ownerId);
    List<DriveFile> findAllByOwnerIdAndParentIdAndDeletedAtIsNullOrderByKindAscNameAsc(Long ownerId, Long parentId);
    List<DriveFile> findAllByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long ownerId);
    List<DriveFile> findAllByOwnerId(Long ownerId);
    Optional<DriveFile> findByIdAndOwnerId(Long id, Long ownerId);
    boolean existsByOwnerIdAndParentIdIsNullAndDeletedAtIsNullAndNameIgnoreCase(Long ownerId, String name);
    boolean existsByOwnerIdAndParentIdAndDeletedAtIsNullAndNameIgnoreCase(Long ownerId, Long parentId, String name);
    @Query("select coalesce(sum(f.size), 0) from DriveFile f where f.ownerId = :ownerId and f.kind = 'file'")
    long sumFileSizeByOwnerId(@Param("ownerId") Long ownerId);
}
