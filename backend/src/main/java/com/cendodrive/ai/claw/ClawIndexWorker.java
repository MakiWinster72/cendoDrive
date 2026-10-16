package com.cendodrive.ai.claw;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ClawIndexWorker {
  private final ClawSettings settings;
  private final ClawIndexQueue queue;
  private final ClawFileAccess access;
  private final ClawDocumentReader reader;
  private final ClawEmbeddingClient embeddings;
  private final ClawVectorStore vectors;
  private final boolean workerEnabled;
  // Wrapper, not an Executor bean: preserve Boot's file-download async executor.
  private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
      new SynchronousQueue<>(), r -> {
        var thread = new Thread(r, "claw-index");
        thread.setDaemon(true);
        return thread;
      });

  public ClawIndexWorker(ClawSettings settings, ClawIndexQueue queue, ClawFileAccess access, ClawDocumentReader reader,
      ClawEmbeddingClient embeddings, ClawVectorStore vectors,
      @Value("${cendo.ai.claw.worker-enabled:true}") boolean workerEnabled) {
    this.settings = settings;
    this.queue = queue;
    this.access = access;
    this.reader = reader;
    this.embeddings = embeddings;
    this.vectors = vectors;
    this.workerEnabled = workerEnabled;
  }

  @Scheduled(fixedDelayString = "${cendo.ai.claw.poll-delay-ms:5000}", initialDelayString = "${cendo.ai.claw.poll-delay-ms:5000}")
  public void scheduled() {
    if (!settings.enabled() || !workerEnabled)
      return;
    try {
      executor.execute(this::processOne);
    } catch (RejectedExecutionException ignored) {
      /* A claimed task is already processing. */ }
  }

  public boolean processOne() {
    var task = queue.claim();
    if (task == null)
      return false;
    try {
      if (task.operation().equals("DELETE")) {
        vectors.deleteCollection(task.collection(), task.ownerId(), task.fileId(),
            task.generation() == 0 ? null : task.generation());
        queue.finish(task);
        return true;
      }
      var file = access.visible(task.ownerId(), task.fileId());
      if (file == null || !queue.current(task)) {
        discard(task);
        return true;
      }
      var chunks = reader.chunks(file);
      var values = new ArrayList<List<Double>>();
      for (int start = 0; start < chunks.size(); start += 4) {
        if (!queue.current(task) || access.visible(task.ownerId(), task.fileId()) == null) {
          discard(task);
          return true;
        }
        values.addAll(embeddings.embed(chunks.subList(start, Math.min(start + 4, chunks.size()))));
      }
      if (!queue.current(task) || access.visible(task.ownerId(), task.fileId()) == null) {
        discard(task);
        return true;
      }
      vectors.upsert(task.ownerId(), task.fileId(), task.generation(), chunks, values);
      if (queue.publish(task, chunks.size()))
        queue.finish(task);
      else
        discard(task);
    } catch (ClawFailure ex) {
      boolean permanent = Set.of("CLAW_UNSUPPORTED", "CLAW_TOO_LARGE", "CLAW_NO_TEXT").contains(ex.code());
      fail(task, ex.code(), permanent);
    } catch (RuntimeException ex) {
      fail(task, "CLAW_EXTRACTION_FAILED", false);
    }
    return true;
  }

  private void discard(ClawIndexQueue.Task task) {
    // A reclaimed lease can publish the SAME generation; stale workers must not
    // delete it.
    if (queue.current(task) && access.visible(task.ownerId(), task.fileId()) != null)
      return;
    queue.cleanupIfObsolete(task);
    queue.failed(task, "CLAW_FILE_UNAVAILABLE", true);
  }

  private void fail(ClawIndexQueue.Task task, String code, boolean permanent) {
    queue.cleanupIfObsolete(task); // Independent of the original lease, even after partial remote writes.
    queue.failed(task, code, permanent);
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
