package com.cendodrive.upload;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface UploadSessionRepository extends JpaRepository<UploadSession, String> {
  Optional<UploadSession> findByIdAndOwnerId(String id, Long ownerId);
  @Modifying @Query("delete from UploadSession s where s.ownerId=:owner")
  void deleteAllByOwnerId(@Param("owner") Long ownerId);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from UploadSession s where s.id=:id and s.ownerId=:owner")
  Optional<UploadSession> lock(@Param("id") String id, @Param("owner") Long owner);
  List<UploadSession> findAllByOwnerIdAndFileHashAndExpiresAtAfter(
      Long ownerId, String fileHash, LocalDateTime now);
  long countByOwnerIdAndResultFileIdIsNullAndExpiresAtAfter(Long ownerId, LocalDateTime now);
  List<UploadSession> findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(LocalDateTime now);
  @Query("select coalesce(sum(s.fileSize),0) from UploadSession s where s.ownerId=:owner "
      + "and s.resultFileId is null and s.expiresAt>:now")
  long reserved(@Param("owner") Long owner, @Param("now") LocalDateTime now);
  @Query("select coalesce(sum(s.fileSize),0) from UploadSession s where s.ownerId=:owner "
      + "and s.resultFileId is null and s.expiresAt>:now and (:excluded is null or s.id<>:excluded)")
  long reservedExcluding(@Param("owner") Long owner, @Param("now") LocalDateTime now, @Param("excluded") String excluded);
}
