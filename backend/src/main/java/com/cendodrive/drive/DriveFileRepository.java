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

  Optional<DriveFile> findByIdAndOwnerId(Long id, Long ownerId);

  List<DriveFile> findAllByOwnerId(Long ownerId);

  List<DriveFile> findTop100ByOwnerIdAndKindOrderByIdAsc(Long ownerId, String kind);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query("delete from DriveFile f where f.ownerId=:owner")
  void deleteAllByOwnerId(@org.springframework.data.repository.query.Param("owner") Long ownerId);

  @org.springframework.data.jpa.repository.Query("select coalesce(sum(f.size),0) from DriveFile f where f.ownerId=:owner and f.kind='file'")
  long usedBytes(@org.springframework.data.repository.query.Param("owner") Long owner);

  boolean existsByOwnerIdAndParentIdIsNullAndDeletedAtIsNullAndNameIgnoreCase(Long ownerId, String name);

  boolean existsByOwnerIdAndParentIdAndDeletedAtIsNullAndNameIgnoreCase(Long ownerId, Long parentId, String name);
}
