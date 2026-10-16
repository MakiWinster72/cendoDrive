package com.cendodrive.upload;

import java.io.IOException;

public class ChunkChecksumException extends IOException {
  public ChunkChecksumException(String message) {
    super(message);
  }
}
