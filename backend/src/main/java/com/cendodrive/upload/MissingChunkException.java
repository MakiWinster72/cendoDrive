package com.cendodrive.upload;

import java.io.IOException;

/** 合并时某个分片文件在磁盘上缺失；携带下标，便于服务端纠正位图后让客户端重传。 */
public class MissingChunkException extends IOException {
  private final int index;

  public MissingChunkException(int index, String message) {
    super(message);
    this.index = index;
  }

  public int index() {
    return index;
  }
}
