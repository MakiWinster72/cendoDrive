package com.cendodrive.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface FileStorage {
  String upload(InputStream input, long size, String extension) throws IOException;

  void download(String key, OutputStream output) throws IOException;

  void delete(String key) throws IOException;
}
