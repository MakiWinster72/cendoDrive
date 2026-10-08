package com.cendodrive.index;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiIndexTaskRepository extends JpaRepository<AiIndexTask,Long> {
  boolean existsByEventId(String eventId);
  List<AiIndexTask> findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByIdAsc(
      Collection<AiIndexTask.Status> statuses, LocalDateTime due);
}
