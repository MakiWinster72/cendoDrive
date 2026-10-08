package com.cendodrive.storage;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageCleanupTaskRepository extends JpaRepository<StorageCleanupTask,Long> {
  boolean existsByStorageBackendAndStorageKey(String backend,String key);
  List<StorageCleanupTask> findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByIdAsc(
      Collection<StorageCleanupTask.Status> statuses,LocalDateTime due);
}
