package com.cendodrive.share;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.drive.DriveDtos.FileResponse;
import com.cendodrive.drive.DriveFile;
import com.cendodrive.drive.DriveService;
import com.cendodrive.user.User;
import com.cendodrive.user.UserRepository;
import com.cendodrive.share.ShareDtos.*;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShareService {
  private final ShareLinkRepository links;
  private final ShareAccessStore access;
  private final DriveService drive;
  private final UserRepository users;
  private final Clock clock;
  private final ShareCodeGuard codes;

  @Autowired
  public ShareService(ShareLinkRepository links, ShareAccessStore access, DriveService drive, UserRepository users, ShareCodeGuard codes) {
    this(links, access, drive, users, Clock.systemUTC(), codes);
  }

  ShareService(ShareLinkRepository links, ShareAccessStore access, DriveService drive,
      UserRepository users, Clock clock, ShareCodeGuard codes) {
    this.links = links;
    this.access = access;
    this.drive = drive;
    this.users = users;
    this.clock = clock;
    this.codes = codes;
  }

  @Transactional
  public ShareResponse create(User user, CreateShareRequest request) {
    if (request.fileId() == null || request.fileId() <= 0 || request.expiresInSeconds() == null
        || request.expiresInSeconds() < 1 || request.expiresInSeconds() > 2592000)
      throw new DriveFailure(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Invalid share settings");
    String codeHash = codes.hash(request.extractionCode());
    DriveFile file = drive.shareableFile(user, request.fileId());
    ShareLink link = ShareLink.create(user.getId(), file.getId(),
        UUID.randomUUID().toString().replace("-", ""), file.getName(), file.getSize(),
        clock.instant(), request.expiresInSeconds());
    link.protect(codeHash);
    link = links.saveAndFlush(link);
    access.enable(link, clock.instant());
    return ShareResponse.from(link);
  }

  @Transactional(readOnly = true)
  public List<ShareResponse> list(User user) {
    return links.findAllByOwnerIdOrderByCreatedAtDesc(user.getId()).stream().map(ShareResponse::from).toList();
  }

  @Transactional
  public void cancel(User user, Long id) {
    ShareLink link = links.findByIdAndOwnerId(id, user.getId()).orElseThrow(ShareService::notFound);
    access.revoke(link);
    link.cancel();
    links.saveAndFlush(link);
  }

  public ShareAccessResponse get(String token) { return get(token, null, "internal"); }

  public ShareAccessResponse get(String token, String code, String client) {
    Resolved resolved = resolve(token, code, client);
    FileResponse source = FileResponse.from(resolved.file());
    // The recipient does not need the owner's private folder hierarchy.
    FileResponse file = new FileResponse(source.id(), source.name(), source.kind(), source.size(),
        null, source.updatedAt(), null);
    return new ShareAccessResponse(file, resolved.link().getExpiresAt().toString());
  }

  public DriveService.Download download(String token) { return download(token, null, "internal"); }

  public DriveService.Download download(String token, String code, String client) {
    Resolved resolved = resolve(token, code, client);
    return drive.download(resolved.owner(), resolved.file().getId());
  }

  public FileResponse save(User recipient, String token, SaveShareRequest request) throws IOException {
    return save(recipient, token, request, null, "internal");
  }

  public FileResponse save(User recipient, String token, SaveShareRequest request, String code, String client) throws IOException {
    Resolved resolved = resolve(token, code, client);
    return drive.saveSharedFile(recipient, resolved.owner(), resolved.file().getId(), request.parentId());
  }

  private Resolved resolve(String token, String code, String client) {
    if (token == null || !token.matches("[a-f0-9]{32}")) throw notFound();
    ShareLink link = links.findByToken(token).orElseThrow(ShareService::notFound);
    if (!link.isActiveAt(clock.instant()) || !access.allows(link)) throw notFound();
    User owner = users.findById(link.getOwnerId()).filter(User::isActive).orElseThrow(ShareService::notFound);
    try {
      DriveFile file = drive.shareableFile(owner, link.getFileId());
      codes.verify(link, code, client);
      return new Resolved(link, owner, file);
    } catch (DriveFailure ex) {
      if (ex.code().startsWith("SHARE_CODE_")) throw ex;
      throw notFound();
    }
  }

  private record Resolved(ShareLink link, User owner, DriveFile file) {}

  private static DriveFailure notFound() {
    return new DriveFailure(HttpStatus.NOT_FOUND, "SHARE_NOT_FOUND", "Share not found, expired or cancelled");
  }
}
