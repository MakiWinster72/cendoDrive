package com.cendodrive.transfer;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRecordRepository extends JpaRepository<TransferRecord, Long> {
  List<TransferRecord> findAllByOwnerIdOrderByCreatedAtAscIdAsc(Long ownerId);
  Optional<TransferRecord> findByOwnerIdAndClientId(Long ownerId, String clientId);
  void deleteByOwnerIdAndClientId(Long ownerId, String clientId);
  void deleteAllByOwnerIdAndDirection(Long ownerId, String direction);
  void deleteAllByOwnerId(Long ownerId);
}
