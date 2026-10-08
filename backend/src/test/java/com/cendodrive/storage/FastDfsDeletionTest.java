package com.cendodrive.storage;

import com.github.tobato.fastdfs.exception.FdfsServerException;
import com.github.tobato.fastdfs.service.FastFileStorageClient;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class FastDfsDeletionTest {
  @Test void deletionIsIdempotentOnlyForMissingFiles() throws Exception {
    var client=mock(FastFileStorageClient.class);
    var storage=new FastDfsFileStorage(client);
    doThrow(FdfsServerException.byCode(2)).when(client).deleteFile("gone");
    assertDoesNotThrow(()->storage.delete("gone"));
    doThrow(FdfsServerException.byCode(13)).when(client).deleteFile("denied");
    assertThrows(IOException.class,()->storage.delete("denied"));
    doThrow(new IllegalStateException("offline")).when(client).deleteFile("offline");
    assertThrows(IOException.class,()->storage.delete("offline"));
  }
}
