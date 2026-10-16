package com.cendodrive.ai.claw;

import com.cendodrive.ai.AiChatService;
import com.cendodrive.ai.claw.ClawDtos.*;
import com.cendodrive.user.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ClawSearchService {
  private final ClawSettings settings;
  private final ClawFileAccess access;
  private final ClawIndexQueue queue;
  private final ClawEmbeddingClient embeddings;
  private final ClawVectorStore vectors;
  private final AiChatService chat;
  private final ObjectMapper mapper;
  private final UserRepository users;
  private final java.util.concurrent.Semaphore inference = new java.util.concurrent.Semaphore(4);

  public ClawSearchService(ClawSettings settings, ClawFileAccess access, ClawIndexQueue queue,
      ClawEmbeddingClient embeddings, ClawVectorStore vectors, AiChatService chat, ObjectMapper mapper,
      UserRepository users) {
    this.settings = settings;
    this.access = access;
    this.queue = queue;
    this.embeddings = embeddings;
    this.vectors = vectors;
    this.chat = chat;
    this.mapper = mapper;
    this.users = users;
  }

  private Map<Long, ClawIndexQueue.Document> eligible(long owner, ClawFileAccess.Snapshot snapshot) {
    var result = new HashMap<Long, ClawIndexQueue.Document>();
    for (var doc : queue.documents(owner))
      if ("READY".equals(doc.status()) && doc.chunks() > 0 && settings.collection().equals(doc.collection())
          && snapshot.visible().containsKey(doc.fileId()))
        result.put(doc.fileId(), doc);
    if (result.size() > 5000)
      throw new ClawFailure("CLAW_TOO_MANY_FILES");
    return result;
  }

  private void requireConfigured() {
    settings.requireEnabled();
    if (!chat.status().configured())
      throw new ClawFailure("CLAW_NOT_CONFIGURED");
  }

  public Answer ask(long owner, Question request) {
    requireConfigured();
    if (!inference.tryAcquire())
      throw new ClawFailure("CLAW_BUSY");
    try {
      return answer(owner, request);
    } finally {
      inference.release();
    }
  }

  private record Evidence(List<Source> sources, Map<Long, Long> generations, String json) {
  }

  private Answer answer(long owner, Question request) {
    String query = queryContext(request);
    var documents = eligible(owner, access.snapshot(owner));
    if (documents.isEmpty())
      return noMatch();
    var vector = embeddings.embed(List.of(query)).get(0);
    var generations = new HashMap<Long, Long>();
    documents.forEach((id, doc) -> generations.put(id, doc.generation()));
    var passages = selectPassages(owner, documents, vectors.query(owner, generations, vector));
    if (passages.isEmpty())
      return noMatch();
    var evidence = prepareEvidence(owner, documents, passages);
    String content = chat.groundedAnswer(query, evidence.json()).content();
    if (content == null || content.isBlank() || content.length() > 16000)
      throw new ClawFailure("CLAW_ANSWER_INVALID");
    validateSources(owner, evidence);
    return new Answer(content, evidence.sources(), true);
  }

  private static String queryContext(Question request) {
    String question = request.question() == null ? "" : request.question().strip();
    if (question.isBlank() || question.length() > 2000
        || request.previousQuestion() != null && request.previousQuestion().length() > 300)
      throw new ClawFailure("CLAW_INVALID_QUESTION");
    return request.previousQuestion() == null || request.previousQuestion().isBlank() ? question
        : "前一个问题：" + request.previousQuestion().strip() + "\n当前问题：" + question;
  }

  private static Map<Long, List<String>> selectPassages(long owner, Map<Long, ClawIndexQueue.Document> documents,
      List<ClawVectorStore.Hit> hits) {
    var grouped = new LinkedHashMap<Long, List<String>>();
    var seen = new HashSet<String>();
    for (var hit : hits) {
      var doc = documents.get(hit.fileId());
      if (doc == null || hit.ownerId() != owner || doc.generation() != hit.generation() || hit.text().isBlank()
          || hit.chunk() < 0 || hit.chunk() >= doc.chunks() || hit.text().length() > 1800
          || !Double.isFinite(hit.score()) || hit.score() < 0.3 || !seen.add(hit.fileId() + ":" + hit.chunk()))
        continue;
      if (!grouped.containsKey(hit.fileId()) && grouped.size() >= 4)
        continue;
      var passages = grouped.computeIfAbsent(hit.fileId(), id -> new ArrayList<>());
      if (passages.size() < 2)
        passages.add(hit.text());
    }
    return grouped;
  }

  private Evidence prepareEvidence(long owner, Map<Long, ClawIndexQueue.Document> documents,
      Map<Long, List<String>> passages) {
    var snapshot = access.snapshot(owner);
    var refreshed = eligible(owner, snapshot);
    var sources = new ArrayList<Source>();
    var data = new ArrayList<Map<String, Object>>();
    var generations = new HashMap<Long, Long>();
    for (var item : passages.entrySet()) {
      long id = item.getKey();
      var current = refreshed.get(id);
      if (current == null || current.generation() != documents.get(id).generation())
        throw new ClawFailure("CLAW_FILE_UNAVAILABLE");
      var file = snapshot.visible().get(id);
      int citation = sources.size() + 1;
      generations.put(id, current.generation());
      var fitted = item.getValue().stream().map(text -> fitJsonString(text, 1600)).toList();
      sources
          .add(new Source(citation, Long.toString(id), file.name(), snapshot.path(id), String.join("\n…\n", fitted)));
      data.add(Map.of("citation", citation, "name", fitJsonString(file.name(), 512), "path",
          fitJsonString(snapshot.path(id), 2048), "passages", fitted));
    }
    try {
      String json = mapper.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
          .writeValueAsString(data);
      if (json.length() > 24000)
        throw new ClawFailure("CLAW_EXTRACTION_FAILED");
      return new Evidence(List.copyOf(sources), Map.copyOf(generations), json);
    } catch (JsonProcessingException ex) {
      throw new ClawFailure("CLAW_EXTRACTION_FAILED");
    }
  }

  private String fitJsonString(String value, int budget) {
    try {
      if (mapper.writeValueAsString(value).length() <= budget)
        return value;
      // Budget serialized characters (including JSON escapes), and only split at
      // code-point boundaries.
      int low = 0, high = value.codePointCount(0, value.length());
      while (low < high) {
        int middle = (low + high + 1) / 2;
        String candidate = value.substring(0, value.offsetByCodePoints(0, middle)) + "…";
        if (mapper.writeValueAsString(candidate).length() <= budget)
          low = middle;
        else
          high = middle - 1;
      }
      return value.substring(0, value.offsetByCodePoints(0, low)) + "…";
    } catch (JsonProcessingException ex) {
      throw new ClawFailure("CLAW_EXTRACTION_FAILED");
    }
  }

  private void validateSources(long owner, Evidence evidence) {
    // Discard the whole answer when inference outlives a source's visibility,
    // generation, name or path.
    var snapshot = access.snapshot(owner);
    var documents = eligible(owner, snapshot);
    for (var source : evidence.sources()) {
      long id = Long.parseLong(source.fileId());
      var latest = documents.get(id);
      var metadata = snapshot.visible().get(id);
      if (latest == null || latest.generation() != evidence.generations().get(id) || metadata == null
          || !source.name().equals(metadata.name()) || !source.path().equals(snapshot.path(id)))
        throw new ClawFailure("CLAW_FILE_UNAVAILABLE");
    }
  }

  public Status status(long owner) {
    var snapshot = access.snapshot(owner);
    var retrying = queue.retrying(owner);
    var recent = new ArrayList<IndexFile>();
    int ready = 0, pending = 0, failed = 0, unsupported = 0, needsReindex = 0;
    for (var doc : queue.documents(owner)) {
      var file = snapshot.visible().get(doc.fileId());
      if (file == null)
        continue;
      String state = !settings.collection().equals(doc.collection()) ? "NEEDS_REINDEX" : doc.status();
      if ("FAILED".equals(state) && retrying.contains(doc.fileId()))
        state = "RETRY";
      switch (state) {
        case "READY" -> ready++;
        case "PENDING", "PROCESSING", "RETRY" -> pending++;
        case "UNSUPPORTED" -> unsupported++;
        case "NEEDS_REINDEX" -> needsReindex++;
        default -> {
          state = "FAILED";
          failed++;
        }
      }
      if (recent.size() < 20)
        recent.add(new IndexFile(Long.toString(doc.fileId()), file.name(), snapshot.path(doc.fileId()), state,
            safeError(doc.errorCode()), doc.chunks()));
    }
    return new Status(settings.enabled() && chat.status().configured(), settings.enabled() ? settings.model() : null,
        ready, pending, failed, unsupported, needsReindex, List.copyOf(recent));
  }

  private static String safeError(String code) {
    if (code == null)
      return null;
    return switch (code) {
      case "CLAW_UNSUPPORTED", "CLAW_TOO_LARGE", "CLAW_NO_TEXT", "CLAW_EMBEDDING_INVALID", "CLAW_EMBEDDING_UNAVAILABLE",
          "CLAW_VECTOR_INVALID", "CLAW_VECTOR_UNAVAILABLE", "CLAW_FILE_UNAVAILABLE", "CLAW_EXTRACTION_FAILED" ->
        code;
      default -> "CLAW_EXTRACTION_FAILED";
    };
  }

  private void lockOwner(long owner) {
    // Share the metadata mutation fence before snapshots or per-file locks. Bulk
    // metadata APIs preserve caller order, so sorting reindex files alone is
    // insufficient.
    users.lock(owner).filter(user -> user.isActive()).orElseThrow(() -> new ClawFailure("CLAW_FILE_UNAVAILABLE"));
  }

  private void requireConsent(ReindexRequest request) {
    settings.requireEnabled();
    if (request == null || !request.confirmed())
      throw new ClawFailure("CLAW_CONSENT_REQUIRED");
  }

  @org.springframework.transaction.annotation.Transactional
  public ReindexResult reindexFile(long owner, long id, ReindexRequest request) {
    requireConsent(request);
    lockOwner(owner);
    if (!access.snapshot(owner).visible().containsKey(id))
      throw new ClawFailure("CLAW_FILE_UNAVAILABLE");
    var file = access.visible(owner, id);
    if (file == null)
      throw new ClawFailure("CLAW_FILE_UNAVAILABLE");
    if (!ClawDocumentReader.supports(file.getName()))
      throw new ClawFailure("CLAW_UNSUPPORTED");
    if (file.getSize() > ClawDocumentReader.MAX_BYTES)
      throw new ClawFailure("CLAW_TOO_LARGE");
    queue.enqueue(file);
    return new ReindexResult(1);
  }

  @org.springframework.transaction.annotation.Transactional
  public ReindexResult reindexAll(long owner, ReindexRequest request) {
    requireConsent(request);
    lockOwner(owner);
    var fresh = access.snapshot(owner).visible();
    var candidates = access.visibleFiles(owner).values().stream().filter(file -> fresh.containsKey(file.getId())
        && ClawDocumentReader.supports(file.getName()) && file.getSize() <= ClawDocumentReader.MAX_BYTES)
        .sorted(Comparator.comparingLong(file -> file.getId())).toList();
    if (candidates.size() > 200)
      throw new ClawFailure("CLAW_REINDEX_TOO_LARGE");
    candidates.forEach(queue::enqueue);
    return new ReindexResult(candidates.size());
  }

  private static Answer noMatch() {
    return new Answer("没有找到可用于回答的文档内容。请确认文件已完成索引，或换一种更具体的问法。", List.of(), false);
  }
}
