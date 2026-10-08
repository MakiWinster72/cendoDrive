package com.cendodrive.upload;

import java.io.IOException;

/** 分片内容与客户端声明的 SHA-256 不一致；此时分片未写入磁盘，已有分片不受影响。 */
public class ChunkChecksumException extends IOException {
    public ChunkChecksumException(String message) { super(message); }
}
