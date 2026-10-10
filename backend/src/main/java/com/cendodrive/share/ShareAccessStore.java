package com.cendodrive.share;

import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ShareAccessStore {
  private final StringRedisTemplate redis;

  public ShareAccessStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  void enable(ShareLink link, Instant now) {
    if (link.isPermanent()) {
      redis.opsForValue().set(key(link.getToken()), link.getId().toString());
    } else {
      redis.opsForValue().set(key(link.getToken()), link.getId().toString(),
          Duration.between(now, link.getExpiresAt()));
    }
  }

  boolean allows(ShareLink link) {
    return link.getId().toString().equals(redis.opsForValue().get(key(link.getToken())));
  }

  void revoke(ShareLink link) {
    redis.delete(key(link.getToken()));
  }

  static String key(String token) {
    return "share:access:" + token;
  }
}
