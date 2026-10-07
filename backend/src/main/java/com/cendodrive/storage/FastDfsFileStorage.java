package com.cendodrive.storage;

import com.github.tobato.fastdfs.domain.fdfs.StorePath;
import com.github.tobato.fastdfs.service.FastFileStorageClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.springframework.stereotype.Component;

@Component
public class FastDfsFileStorage implements FileStorage {
  private final FastFileStorageClient client;

  public FastDfsFileStorage(FastFileStorageClient client) {
    this.client = client;
  }

  @Override
  public String upload(InputStream input, long size, String extension) throws IOException {
    try {
      return client.uploadFile(input, size, extension, null).getFullPath();
    } catch (RuntimeException ex) {
      throw new IOException("FastDFS upload failed", ex);
    }
  }

  @Override
  public void download(String key, OutputStream output) throws IOException {
    StorePath path = StorePath.parseFromUrl(key);
    try {
      client.downloadFile(path.getGroup(), path.getPath(), input -> {
        input.transferTo(output);
        return null;
      });
    } catch (RuntimeException ex) {
      throw new IOException("FastDFS download failed", ex);
    }
  }

  @Override
  public void delete(String key) throws IOException {
    try {
      client.deleteFile(key);
    } catch (RuntimeException ex) {
      throw new IOException("FastDFS delete failed", ex);
    }
  }
}
