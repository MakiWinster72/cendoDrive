package com.cendodrive.user;

import com.cendodrive.auth.LoginRateLimiter;
import com.cendodrive.common.ApiExceptionHandler.*;
import com.cendodrive.share.ShareLinkRepository;
import com.cendodrive.user.UserDtos.*;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UserService {
  public static final int DELETION_DAYS = 7;
  private final UserRepository users;
  private final UserAvatarRepository avatars;
  private final ShareLinkRepository shares;
  private final PasswordEncoder encoder;
  private final LoginRateLimiter limiter;
  private final Clock clock;

  public UserService(UserRepository users, UserAvatarRepository avatars, ShareLinkRepository shares,
      PasswordEncoder encoder, LoginRateLimiter limiter, Clock clock) {
    this.users = users;
    this.avatars = avatars;
    this.shares = shares;
    this.encoder = encoder;
    this.limiter = limiter;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ProfileResponse profile(User user) {
    return summary(active(user.getId()));
  }

  @Transactional
  public ProfileResponse updateProfile(User user, ProfileRequest request) {
    User locked = locked(user);
    String name = request.nickname().trim();
    if (name.isEmpty() || name.codePoints().anyMatch(Character::isISOControl))
      throw error("INVALID_PROFILE", "Invalid nickname");
    locked.setNickname(name);
    return summary(locked);
  }

  @Transactional(readOnly = true)
  public ProfileResponse lookup(String username) {
    if (username == null || !username.trim().matches("[a-zA-Z0-9_]{3,64}"))
      throw error("INVALID_USERNAME", "Invalid username");
    User user = users.findByUsername(username.trim().toLowerCase(Locale.ROOT)).filter(User::isActive)
        .orElseThrow(UserService::notFound);
    return summary(user);
  }

  @Transactional(readOnly = true)
  public byte[] avatar(Long id) {
    active(id);
    return avatars.findById(id).orElseThrow(UserService::notFound).getImageData();
  }

  @Transactional(rollbackFor = IOException.class)
  public void updateAvatar(User user, MultipartFile image) throws IOException {
    // Bound input and inspect dimensions before allocating a decoded image.
    if (image.isEmpty() || image.getSize() > 2 * 1024 * 1024)
      throw error("INVALID_AVATAR", "Avatar must be at most 2 MiB");
    byte[] output = normalizeImage(image.getBytes());
    User locked = locked(user);
    avatars.save(new UserAvatar(locked.getId(), output));
  }

  @Transactional
  public void deleteAvatar(User user) {
    avatars.deleteById(locked(user).getId());
  }

  @Transactional
  public void changePassword(User user, PasswordRequest request) {
    User locked = locked(user);
    verifyPassword(locked, request.currentPassword());
    if (request.newPassword().getBytes(StandardCharsets.UTF_8).length > 72)
      throw error("INVALID_NEW_PASSWORD", "New password must fit within 72 UTF-8 bytes");
    if (encoder.matches(request.newPassword(), locked.getPasswordHash()))
      throw error("PASSWORD_UNCHANGED", "New password must differ from current password");
    locked.changePassword(encoder.encode(request.newPassword()));
  }

  @Transactional
  public DeletionResponse deleteAccount(User user, DeleteRequest request) {
    User locked = locked(user);
    if (!"注销账号".equals(request.confirmation()))
      throw error("INVALID_CONFIRMATION", "Confirmation required");
    verifyPassword(locked, request.password());
    LocalDateTime now = LocalDateTime.now(clock);
    locked.markDeleted(now);
    // DB cancellation is authoritative; old Redis grants alone never authorize a
    // share.
    shares.cancelAllByOwnerId(locked.getId());
    return new DeletionResponse(now.atOffset(ZoneOffset.UTC), now.plusDays(DELETION_DAYS).atOffset(ZoneOffset.UTC));
  }

  private User locked(User user) {
    User locked = users.lock(user.getId()).orElseThrow(UserService::notFound);
    if (!locked.isActive() || locked.getAuthVersion() != user.getAuthVersion())
      throw new AuthFailure(HttpStatus.UNAUTHORIZED, "Unauthorized");
    return locked;
  }

  private User active(Long id) {
    return users.findById(id).filter(User::isActive).orElseThrow(UserService::notFound);
  }

  private ProfileResponse summary(User u) {
    return new ProfileResponse(u.getId().toString(), u.getUsername(), u.getNickname(), avatars.existsById(u.getId()));
  }

  private void verifyPassword(User user, String password) {
    limiter.check(user.getUsername());
    if (!encoder.matches(password, user.getPasswordHash())) {
      limiter.failure(user.getUsername());
      throw error("INVALID_PASSWORD", "Current password is incorrect");
    }
    limiter.success(user.getUsername());
  }

  static byte[] normalizeImage(byte[] input) throws IOException {
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext())
        throw error("INVALID_AVATAR", "Only PNG and JPEG images are supported");
      var reader = readers.next();
      try {
        String format = reader.getFormatName();
        if (!format.equalsIgnoreCase("PNG") && !format.equalsIgnoreCase("JPEG"))
          throw error("INVALID_AVATAR", "Only PNG and JPEG images are supported");
        reader.setInput(stream, true, true);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 1 || height < 1 || width > 2048 || height > 2048)
          throw error("INVALID_AVATAR", "Avatar dimensions must not exceed 2048 x 2048");
        BufferedImage source = reader.read(0), target = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        var graphics = target.createGraphics();
        try {
          graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
          int side = Math.min(width, height), x = (width - side) / 2, y = (height - side) / 2;
          graphics.drawImage(source, 0, 0, 256, 256, x, y, x + side, y + side, null);
        } finally {
          graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        ImageIO.write(target, "png", output);
        return output.toByteArray();
      } finally {
        reader.dispose();
      }
    } catch (javax.imageio.IIOException ex) {
      throw error("INVALID_AVATAR", "Invalid image");
    }
  }

  private static DriveFailure error(String code, String message) {
    return new DriveFailure(HttpStatus.BAD_REQUEST, code, message);
  }

  private static DriveFailure notFound() {
    return new DriveFailure(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User or avatar not found");
  }
}
