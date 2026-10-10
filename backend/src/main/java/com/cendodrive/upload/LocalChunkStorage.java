package com.cendodrive.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地磁盘分片存储：{chunk-root}/{uploadId}/{index}.chunk。
 * 分片先写临时文件并同步计算 SHA-256，校验通过后才原子改名，避免半成品与并发覆盖。
 * uploadId 必须是 64 位小写十六进制（由服务端派生），据此阻断路径穿越。
 */
@Component
public class LocalChunkStorage implements ChunkStorage {
  private static final Logger log = LoggerFactory.getLogger(LocalChunkStorage.class);
  private static final String CHUNK_SUFFIX = ".chunk";
  private static final String PART_SUFFIX = ".part";
  private static final int MOVE_RETRIES = 4;
  private static final int STRIPES = 64;
  private final Path root;
  private final Object[] stripes = new Object[STRIPES];

  public LocalChunkStorage(@Value("${cendo.upload.chunk-root:./storage/chunks}") String chunkRoot) {
    this.root = Path.of(chunkRoot).toAbsolutePath().normalize();
    for (int index = 0; index < STRIPES; index++)
      stripes[index] = new Object();
  }

  @Override
  public void save(String uploadId, int index, InputStream content, String expectedSha256) throws IOException {
    Path directory = sessionDirectory(uploadId);
    Path target = chunkPath(directory, index);
    Files.createDirectories(directory);
    // 临时文件名必须唯一：同一分片的并发重传不能共用一个临时文件。
    Path partial = directory.resolve(index + "-" + UUID.randomUUID() + PART_SUFFIX);
    // 同一分片的写入串行化：并发改名同一目标在部分文件系统（如 Windows）上会失败。
    synchronized (stripeFor(uploadId, index)) {
      MessageDigest digest = sha256();
      try {
        try (OutputStream output = new DigestOutputStream(Files.newOutputStream(partial), digest)) {
          content.transferTo(output);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (expectedSha256 == null || !actual.equalsIgnoreCase(expectedSha256)) {
          throw new ChunkChecksumException("Chunk checksum mismatch for upload " + uploadId + " index " + index);
        }
        moveReplacing(partial, target);
      } catch (IOException | RuntimeException ex) {
        deleteQuietly(partial);
        throw ex;
      }
    }
  }

  private Object stripeFor(String uploadId, int index) {
    return stripes[Math.floorMod(Objects.hash(uploadId, index), STRIPES)];
  }

  @Override
  public InputStream openAll(String uploadId, int totalChunks) throws IOException {
    Path directory = sessionDirectory(uploadId);
    List<Path> chunks = new ArrayList<>(totalChunks);
    for (int index = 0; index < totalChunks; index++) {
      Path path = chunkPath(directory, index);
      if (!Files.isRegularFile(path)) {
        throw new MissingChunkException(index, "Missing chunk " + index + " of upload " + uploadId);
      }
      chunks.add(path);
    }
    return new ChunkSequenceInputStream(chunks);
  }

  @Override
  public void cleanup(String uploadId) {
    Path directory;
    try {
      directory = sessionDirectory(uploadId);
    } catch (IllegalArgumentException ex) {
      return;
    }
    if (!Files.isDirectory(directory))
      return;
    try (Stream<Path> entries = Files.walk(directory)) {
      entries.sorted(Comparator.reverseOrder()).forEach(LocalChunkStorage::deleteQuietly);
    } catch (IOException ex) {
      log.warn("Failed to clean chunk session {}: {}", uploadId, ex.getMessage());
    }
  }

  @Override
  public List<Session> listSessions() {
    if (!Files.isDirectory(root))
      return List.of();
    try (Stream<Path> entries = Files.list(root)) {
      return entries.filter(Files::isDirectory)
          .map(this::toSession)
          .filter(Objects::nonNull)
          .toList();
    } catch (IOException ex) {
      log.warn("Failed to list chunk sessions: {}", ex.getMessage());
      return List.of();
    }
  }

  private Session toSession(Path directory) {
    String uploadId = directory.getFileName().toString();
    if (!uploadId.matches(ChunkStorage.UPLOAD_ID_PATTERN))
      return null;
    try {
      return new Session(uploadId, Files.getLastModifiedTime(directory).toInstant());
    } catch (IOException ex) {
      return null;
    }
  }

  private Path sessionDirectory(String uploadId) {
    if (uploadId == null || !uploadId.matches(ChunkStorage.UPLOAD_ID_PATTERN)) {
      throw new IllegalArgumentException("Invalid upload id");
    }
    Path directory = root.resolve(uploadId).normalize();
    if (!directory.startsWith(root))
      throw new IllegalArgumentException("Invalid upload id");
    return directory;
  }

  private static Path chunkPath(Path directory, int index) {
    if (index < 0)
      throw new IllegalArgumentException("Invalid chunk index");
    Path path = directory.resolve(index + CHUNK_SUFFIX).normalize();
    if (!path.startsWith(directory))
      throw new IllegalArgumentException("Invalid chunk index");
    return path;
  }

  private static void moveReplacing(Path source, Path target) throws IOException {
    for (int attempt = 0;; attempt++) {
      try {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return;
      } catch (AtomicMoveNotSupportedException ex) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        return;
      } catch (IOException ex) {
        // 外部进程（杀毒、索引服务）短暂占用目标文件时重试，避免整片上传失败。
        if (attempt >= MOVE_RETRIES)
          throw ex;
        try {
          Thread.sleep(20L * (attempt + 1));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw ex;
        }
      }
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ex) {
      log.warn("Failed to delete {}: {}", path, ex.getMessage());
    }
  }

  /** 逐个打开分片文件，避免一次性占用过多文件描述符。 */
  private static final class ChunkSequenceInputStream extends InputStream {
    private final List<Path> chunks;
    private int next;
    private InputStream current;

    private ChunkSequenceInputStream(List<Path> chunks) {
      this.chunks = chunks;
    }

    @Override
    public int read() throws IOException {
      byte[] single = new byte[1];
      int read = read(single, 0, 1);
      return read < 0 ? -1 : single[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (length == 0)
        return 0;
      while (true) {
        if (current == null) {
          if (next >= chunks.size())
            return -1;
          current = Files.newInputStream(chunks.get(next++));
        }
        int read = current.read(buffer, offset, length);
        if (read >= 0)
          return read;
        current.close();
        current = null;
      }
    }

    @Override
    public void close() throws IOException {
      if (current == null)
        return;
      current.close();
      current = null;
    }
  }
}
