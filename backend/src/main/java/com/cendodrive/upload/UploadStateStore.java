package com.cendodrive.upload;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 分片上传任务在 Redis 中的状态：一个 Hash 保存元数据，一个 Bitmap 保存已接收分片。
 * 两个键共用会话 TTL，每次收片后统一续期；进度由位图推导，不维护独立计数器。
 */
@Component
public class UploadStateStore {
    private static final DefaultRedisScript<Long> BIT_COUNT = new DefaultRedisScript<>(
            "return redis.call('BITCOUNT', KEYS[1])", Long.class);
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0", Long.class);
    private final StringRedisTemplate redis;
    private final Duration sessionTtl;
    private final Duration lockTtl;
    private final Duration doneTtl;

    public UploadStateStore(StringRedisTemplate redis,
                            @Value("${cendo.upload.session-ttl-minutes:1440}") long sessionTtlMinutes,
                            @Value("${cendo.upload.lock-timeout-minutes:30}") long lockTimeoutMinutes,
                            @Value("${cendo.upload.done-ttl-minutes:1440}") long doneTtlMinutes) {
        this.redis = redis;
        this.sessionTtl = Duration.ofMinutes(sessionTtlMinutes);
        this.lockTtl = Duration.ofMinutes(lockTimeoutMinutes);
        this.doneTtl = Duration.ofMinutes(doneTtlMinutes);
    }

    public void saveMeta(UploadMeta meta) {
        redis.opsForHash().putAll(metaKey(meta.uploadId()), meta.toMap());
        touch(meta.uploadId());
    }

    public Optional<UploadMeta> findMeta(String uploadId) {
        Map<Object, Object> raw = redis.opsForHash().entries(metaKey(uploadId));
        if (raw.isEmpty()) return Optional.empty();
        UploadMeta meta;
        try {
            meta = UploadMeta.from(uploadId, raw);
        } catch (RuntimeException ex) {
            // 结构损坏的记录按过期处理，避免脏数据永久阻塞同名会话。
            return Optional.empty();
        }
        repairTtl(uploadId);
        return Optional.of(meta);
    }

    /** 续期会话键；重复上传分片或重新 init 都会延长会话有效期。 */
    public void touch(String uploadId) {
        redis.expire(metaKey(uploadId), sessionTtl);
        redis.expire(chunksKey(uploadId), sessionTtl);
    }

    public void markChunk(String uploadId, int index) {
        redis.opsForValue().setBit(chunksKey(uploadId), index, true);
        touch(uploadId);
    }

    /** 磁盘分片丢失或损坏时纠正位图，让客户端能重新上传对应分片而不是永久卡住。 */
    public void clearChunk(String uploadId, int index) {
        redis.opsForValue().setBit(chunksKey(uploadId), index, false);
        touch(uploadId);
    }

    /** 整文件校验失败时丢弃全部进度，客户端重新 init 会得到空的已上传列表。 */
    public void clearChunks(String uploadId) {
        redis.delete(chunksKey(uploadId));
    }

    public boolean hasChunk(String uploadId, int index) {
        return Boolean.TRUE.equals(redis.opsForValue().getBit(chunksKey(uploadId), index));
    }

    public int receivedCount(String uploadId) {
        Long count = redis.execute(BIT_COUNT, List.of(chunksKey(uploadId)));
        return count == null ? 0 : count.intValue();
    }

    public List<Integer> uploadedIndexes(String uploadId, int totalChunks) {
        return indexesOf(uploadId, totalChunks, true);
    }

    public List<Integer> missingIndexes(String uploadId, int totalChunks) {
        return indexesOf(uploadId, totalChunks, false);
    }

    /** 锁值为随机令牌，释放时比对令牌，避免误删他人已重新获取的锁。 */
    public Optional<String> tryLock(String uploadId) {
        String token = UUID.randomUUID().toString();
        boolean acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey(uploadId), token, lockTtl));
        return acquired ? Optional.of(token) : Optional.empty();
    }

    public void releaseLock(String uploadId, String token) {
        if (token == null) return;
        redis.execute(RELEASE_LOCK, List.of(lockKey(uploadId)), token);
    }

    /** 合并成功后写入幂等标记，值为此前生成的文件 ID。 */
    public void markDone(String uploadId, long fileId) {
        redis.opsForValue().set(doneKey(uploadId), Long.toString(fileId), doneTtl);
    }

    public Optional<Long> findDone(String uploadId) {
        String value = redis.opsForValue().get(doneKey(uploadId));
        if (value == null) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /** 清理会话元数据与分片位图；合并锁由持有者在 finally 中释放，这里不动。 */
    public void clearSession(String uploadId) {
        redis.delete(List.of(metaKey(uploadId), chunksKey(uploadId)));
    }

    private List<Integer> indexesOf(String uploadId, int totalChunks, boolean present) {
        byte[] bitmap = bitmap(uploadId);
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < totalChunks; index++) {
            if (bitSet(bitmap, index) == present) indexes.add(index);
        }
        return indexes;
    }

    private byte[] bitmap(String uploadId) {
        byte[] raw = redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(bytes(chunksKey(uploadId))));
        return raw == null ? new byte[0] : raw;
    }

    /** Redis 位图按字节大端存放：第 index 位位于第 index/8 字节的最高位起第 index%8 位。 */
    private static boolean bitSet(byte[] bitmap, int index) {
        int byteIndex = index / 8;
        if (byteIndex >= bitmap.length) return false;
        return (bitmap[byteIndex] & (1 << (7 - (index % 8)))) != 0;
    }

    /** 写入与 EXPIRE 之间进程中断会留下永不过期的键，这里在读路径上自愈。 */
    private void repairTtl(String uploadId) {
        if (ttlMissing(metaKey(uploadId)) || ttlMissing(chunksKey(uploadId))) touch(uploadId);
    }

    private boolean ttlMissing(String key) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        return ttl != null && ttl == -1L;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String metaKey(String uploadId) { return "upload:" + uploadId + ":meta"; }
    private static String chunksKey(String uploadId) { return "upload:" + uploadId + ":chunks"; }
    private static String lockKey(String uploadId) { return "upload:" + uploadId + ":lock"; }
    private static String doneKey(String uploadId) { return "upload:" + uploadId + ":done"; }

    public record UploadMeta(String uploadId, Long ownerId, Long parentId, String name,
                             long totalSize, long chunkSize, int totalChunks, String fileHash) {
        Map<String, String> toMap() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("ownerId", ownerId.toString());
            values.put("parentId", parentId == null ? "" : parentId.toString());
            values.put("name", name);
            values.put("totalSize", Long.toString(totalSize));
            values.put("chunkSize", Long.toString(chunkSize));
            values.put("totalChunks", Integer.toString(totalChunks));
            values.put("fileHash", fileHash);
            return values;
        }

        static UploadMeta from(String uploadId, Map<Object, Object> raw) {
            String parentId = text(raw, "parentId");
            return new UploadMeta(uploadId,
                    Long.parseLong(text(raw, "ownerId")),
                    parentId == null || parentId.isBlank() ? null : Long.parseLong(parentId),
                    text(raw, "name"),
                    Long.parseLong(text(raw, "totalSize")),
                    Long.parseLong(text(raw, "chunkSize")),
                    Integer.parseInt(text(raw, "totalChunks")),
                    text(raw, "fileHash"));
        }

        private static String text(Map<Object, Object> raw, String key) {
            Object value = raw.get(key);
            return value == null ? null : value.toString();
        }
    }
}
