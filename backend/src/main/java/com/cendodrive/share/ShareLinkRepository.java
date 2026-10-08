package com.cendodrive.share;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {
  List<ShareLink> findAllByOwnerIdOrderByCreatedAtDesc(Long ownerId);
  Optional<ShareLink> findByIdAndOwnerId(Long id, Long ownerId);
  Optional<ShareLink> findByToken(String token);
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query("update ShareLink s set s.cancelled=true where s.ownerId=:owner")
  void cancelAllByOwnerId(@org.springframework.data.repository.query.Param("owner") Long ownerId);
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query("delete from ShareLink s where s.ownerId=:owner")
  void deleteAllByOwnerId(@org.springframework.data.repository.query.Param("owner") Long ownerId);
}
