package com.cendodrive.share;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {
  List<ShareLink> findAllByOwnerIdOrderByCreatedAtDesc(Long ownerId);
  Optional<ShareLink> findByIdAndOwnerId(Long id, Long ownerId);
  Optional<ShareLink> findByToken(String token);
}
