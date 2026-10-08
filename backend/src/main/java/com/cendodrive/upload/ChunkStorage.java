package com.cendodrive.upload;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * 临时分片存储抽象。当前只有本地磁盘实现；将来需要多实例部署时可替换为共享存储实现，
 * 业务层无需改动。实现必须保证 {@link #save} 的原子性：校验失败或写入失败时不得留下半成品，
 * 也不得破坏同下标已存在的合法分片。
 */
public interface ChunkStorage {
    /** uploadId 由服务端派生，恒为 64 位小写十六进制；实现必须据此校验以防路径穿越。 */
    String UPLOAD_ID_PATTERN = "[0-9a-f]{64}";

    /** 原子保存一个分片：校验失败或写入失败时不得改变同下标已存在的合法分片。 */
    void save(String uploadId, int index, InputStream content, String expectedSha256) throws IOException;

    /** 按 0..totalChunks-1 顺序串流读取全部分片；任一分片缺失时抛出 {@link MissingChunkException}。 */
    InputStream openAll(String uploadId, int totalChunks) throws IOException;

    /** 尽力删除整个会话的临时分片目录，失败只记录日志。 */
    void cleanup(String uploadId);

    /** 列出当前已落盘的会话目录，用于清理孤儿分片。 */
    List<Session> listSessions();

    record Session(String uploadId, Instant lastModified) {}
}
