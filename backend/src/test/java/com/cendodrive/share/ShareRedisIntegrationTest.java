package com.cendodrive.share;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CENDO_TEST_REDIS", matches = "true")
class ShareRedisIntegrationTest {
  LettuceConnectionFactory factory;
  StringRedisTemplate redis;
  ShareAccessStore store;
  ShareLink link;

  @BeforeEach void setup() {
    factory = new LettuceConnectionFactory(System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1"),
        Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
    factory.afterPropertiesSet();
    factory.start();
    redis = new StringRedisTemplate(factory);
    store = new ShareAccessStore(redis);
    link = ShareLink.create(1L, 1L, UUID.randomUUID().toString().replace("-", ""), "test.txt", 5, Instant.now(), 2);
    ReflectionTestUtils.setField(link, "id", 1L);
  }

  @AfterEach void cleanup() {
    try { store.revoke(link); } finally { factory.destroy(); }
  }

  @Test void credentialsHaveRealTtlAndExpireAutomatically() throws Exception {
    store.enable(link, Instant.now());
    Long ttl = redis.getExpire(ShareAccessStore.key(link.getToken()), TimeUnit.MILLISECONDS);
    assertNotNull(ttl);
    assertTrue(ttl > 0 && ttl <= 2000, "Share key must never be permanent");
    assertTrue(store.allows(link));
    Thread.sleep(2100);
    assertFalse(store.allows(link));
    assertEquals(-2L, redis.getExpire(ShareAccessStore.key(link.getToken())));
  }

  @Test void mismatchedCredentialIsRejectedAndRevocationIsImmediate() {
    store.enable(link, Instant.now());
    redis.opsForValue().set(ShareAccessStore.key(link.getToken()), "different-record", java.time.Duration.ofSeconds(2));
    assertFalse(store.allows(link));
    store.enable(link, Instant.now());
    assertTrue(store.allows(link));
    store.revoke(link);
    assertFalse(store.allows(link));
  }
}
