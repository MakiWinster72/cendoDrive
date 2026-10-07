package com.cendodrive.upload;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.drive.*;
import com.cendodrive.drive.DriveDtos.FileResponse;
import com.cendodrive.user.User;
import com.cendodrive.upload.UploadDtos.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UploadService {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UploadService.class);
  private final UploadSessionRepository sessions;
  private final FileQuotaService quota;
  private final DriveService drive;
  private final Path root;
  private final long ttlHours;
  public UploadService(UploadSessionRepository sessions, FileQuotaService quota, DriveService drive,
      @Value("${cendo.upload.root:./storage/uploads}") String root,
      @Value("${cendo.upload.ttl-hours:24}") long ttlHours) {
    if (ttlHours < 1 || ttlHours > 168) throw new IllegalArgumentException("Upload TTL must be 1–168 hours");
    this.sessions=sessions; this.quota=quota; this.drive=drive;
    this.root=Path.of(root).toAbsolutePath().normalize(); this.ttlHours=ttlHours;
  }

  @Transactional
  public InitResponse init(User user, InitRequest request) {
    quota.lock(user);
    if (request.fileSize()<=0 || request.chunkSize()<1 || request.chunkSize()>5242880
        || request.totalChunks()<1 || request.totalChunks()>2048
        || request.totalChunks()!=(request.fileSize()-1)/request.chunkSize()+1)
      throw error(HttpStatus.BAD_REQUEST,"INVALID_UPLOAD","Invalid chunk layout");
    String name=request.fileName().trim();
    String hash=normalizeHash(request.fileHash());
    LocalDateTime now=now();
    for (UploadSession s:sessions.findAllByOwnerIdAndFileHashAndExpiresAtAfter(user.getId(),hash,now)) {
      if (Objects.equals(s.getParentId(),request.parentId()) && s.getFileName().equals(name)
          && s.getFileSize()==request.fileSize() && s.getChunkSize()==request.chunkSize()
          && s.getTotalChunks()==request.totalChunks()) {
        if (s.getResultFileId()!=null) {
          try { drive.metadata(user,s.getResultFileId()); }
          catch (DriveFailure e) { if ("FILE_NOT_FOUND".equals(e.code())) continue; throw e; }
        }
        return new InitResponse(s.getId(),s.getChunkSize(),s.getTotalChunks());
      }
    }
    name=drive.validateUploadTarget(user,name,request.parentId());
    quota.check(user,request.fileSize(),null);
    if (sessions.countByOwnerIdAndResultFileIdIsNullAndExpiresAtAfter(user.getId(),now)>=20)
      throw error(HttpStatus.TOO_MANY_REQUESTS,"TOO_MANY_UPLOADS","Too many unfinished uploads");
    UploadSession s=new UploadSession(UUID.randomUUID().toString().replace("-",""),user.getId(),
        request.parentId(),name,request.fileSize(),hash,request.chunkSize(),request.totalChunks(),now.plusHours(ttlHours));
    sessions.saveAndFlush(s);
    return new InitResponse(s.getId(),s.getChunkSize(),s.getTotalChunks());
  }

  @Transactional
  public StatusResponse status(User user,String id) throws IOException {
    UploadSession s=required(user,id);
    return status(s);
  }

  @Transactional(rollbackFor=IOException.class)
  public void chunk(User user,String id,int number,int total,String hash,MultipartFile content) throws IOException {
    UploadSession s=required(user,id);
    verifyHash(s,hash);
    if (total!=s.getTotalChunks() || number<0 || number>=total || content==null
        || content.getSize()!=expectedSize(s,number))
      throw error(HttpStatus.BAD_REQUEST,"INVALID_CHUNK","Invalid chunk index or size");
    if (s.getResultFileId()!=null) return;
    Path dir=directory(s);
    Files.createDirectories(dir);
    Path temp=Files.createTempFile(dir,"chunk-",".tmp");
    try {
      try (InputStream input=content.getInputStream(); OutputStream out=Files.newOutputStream(temp)) {
        copyExpected(input,out,expectedSize(s,number));
      }
      // Replacement is atomic: retries never expose half-written chunks to status or merge.
      Files.move(temp,part(s,number),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    } finally { Files.deleteIfExists(temp); }
  }

  @Transactional(rollbackFor=IOException.class)
  public FileResponse merge(User user,MergeRequest request) throws IOException {
    // All quota-changing operations acquire user before session to avoid inverted lock order.
    quota.lock(user);
    UploadSession s=required(user,request.uploadId());
    verifyHash(s,request.fileHash());
    if (s.getResultFileId()!=null) return drive.metadata(user,s.getResultFileId());
    quota.check(user,s.getFileSize(),s.getId());
    drive.validateUploadTarget(user,s.getFileName(),s.getParentId());
    if (status(s).uploadedChunks().size()!=s.getTotalChunks())
      throw error(HttpStatus.CONFLICT,"INCOMPLETE_UPLOAD","Some chunks are missing");
    Path merged=Files.createTempFile(directory(s),"merged-",".tmp");
    try {
      MessageDigest digest=md5();
      try (OutputStream output=new DigestOutputStream(Files.newOutputStream(merged),digest)) {
        for (int i=0;i<s.getTotalChunks();i++) Files.copy(part(s,i),output);
      }
      if (Files.size(merged)!=s.getFileSize() || !HexFormat.of().formatHex(digest.digest()).equals(s.getFileHash())) {
        for (int i=0;i<s.getTotalChunks();i++) Files.deleteIfExists(part(s,i));
        throw error(HttpStatus.BAD_REQUEST,"HASH_MISMATCH","Whole-file MD5 does not match; retry all chunks");
      }
      FileResponse result;
      try (InputStream input=Files.newInputStream(merged)) {
        result=drive.uploadStream(user,input,s.getFileSize(),s.getFileName(),s.getParentId(),s.getId());
      }
      s.complete(Long.valueOf(result.id()));
      sessions.saveAndFlush(s);
      afterCommit(() -> removeDirectory(directory(s)));
      return result;
    } finally { Files.deleteIfExists(merged); }
  }

  @Transactional
  public int cleanupExpired() {
    List<UploadSession> expired=sessions.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(now());
    int removed=0;
    for (UploadSession candidate:expired) {
      UploadSession s=sessions.lock(candidate.getId(),candidate.getOwnerId()).orElse(null);
      if (s==null || s.getExpiresAt().isAfter(now())) continue;
      sessions.delete(s);
      afterCommit(() -> removeDirectory(directory(s)));
      removed++;
    }
    return removed;
  }

  private UploadSession required(User user,String id) {
    if (id==null || !id.matches("[a-f0-9]{32}"))
      throw error(HttpStatus.NOT_FOUND,"UPLOAD_NOT_FOUND","Upload session not found");
    UploadSession s=sessions.lock(id,user.getId()).orElseThrow(() ->
        error(HttpStatus.NOT_FOUND,"UPLOAD_NOT_FOUND","Upload session not found"));
    if (!s.getExpiresAt().isAfter(now())) throw error(HttpStatus.GONE,"UPLOAD_EXPIRED","Upload session expired");
    return s;
  }
  private StatusResponse status(UploadSession s) throws IOException {
    List<Integer> uploaded=new ArrayList<>();
    for (int i=0;i<s.getTotalChunks();i++)
      if (s.getResultFileId()!=null || (Files.isRegularFile(part(s,i),LinkOption.NOFOLLOW_LINKS)
          && Files.size(part(s,i))==expectedSize(s,i))) uploaded.add(i);
    return new StatusResponse(s.getId(),s.getTotalChunks(),uploaded);
  }
  private static void verifyHash(UploadSession s,String hash) {
    if (!s.getFileHash().equals(normalizeHash(hash)))
      throw error(HttpStatus.BAD_REQUEST,"HASH_MISMATCH","File hash differs from session");
  }
  private static String normalizeHash(String hash) {
    if (hash==null || !hash.matches("(?i)[a-f0-9]{32}"))
      throw error(HttpStatus.BAD_REQUEST,"INVALID_UPLOAD","Invalid MD5");
    return hash.toLowerCase(Locale.ROOT);
  }
  private static long expectedSize(UploadSession s,int number) {
    return Math.min(s.getChunkSize(),s.getFileSize()-(long)number*s.getChunkSize());
  }
  private Path directory(UploadSession s) { return root.resolve(s.getOwnerId().toString()).resolve(s.getId()); }
  private Path part(UploadSession s,int number) { return directory(s).resolve(number+".part"); }
  private static MessageDigest md5() {
    try { return MessageDigest.getInstance("MD5"); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  private static void copyExpected(InputStream input,OutputStream output,long expected) throws IOException {
    byte[] buffer=new byte[8192]; long written=0; int count;
    while ((count=input.read(buffer))!=-1) {
      written+=count;
      if (written>expected) throw error(HttpStatus.BAD_REQUEST,"INVALID_CHUNK","Chunk exceeds declared size");
      output.write(buffer,0,count);
    }
    if (written!=expected) throw error(HttpStatus.BAD_REQUEST,"INVALID_CHUNK","Chunk is truncated");
  }
  private static void afterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) { action.run(); return; }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() { action.run(); }
    });
  }
  private static void removeDirectory(Path dir) {
    if (!Files.exists(dir,LinkOption.NOFOLLOW_LINKS)) return;
    try (var paths=Files.walk(dir)) {
      for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    } catch (IOException e) { LOG.warn("Upload staging cleanup failed: {}",dir,e); }
  }
  private static LocalDateTime now() { return LocalDateTime.now(Clock.systemUTC()); }
  private static DriveFailure error(HttpStatus status,String code,String message) { return new DriveFailure(status,code,message); }
}
