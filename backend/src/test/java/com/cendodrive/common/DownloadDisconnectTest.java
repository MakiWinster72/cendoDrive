package com.cendodrive.common;

import java.io.IOException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DownloadDisconnectTest {
  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DownloadController())
      .setControllerAdvice(new ApiExceptionHandler()).build();

  @Test void clientDisconnectDoesNotAttemptToWriteAnErrorBody() throws Exception {
    mvc.perform(get("/disconnect"))
        .andExpect(content().string(""))
        .andExpect(status().isOk());
  }

  @Test void streamingDisconnectDoesNotAppendJsonToTheDownload() throws Exception {
    var result = mvc.perform(get("/stream-disconnect"))
        .andExpect(request().asyncStarted()).andReturn();
    mvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().bytes(new byte[] { 42 }));
  }

  @Test void rawServletOutputResetIsHandledWithoutAnErrorBody() throws Exception {
    mvc.perform(get("/raw-reset"))
        .andExpect(content().string(""));
  }

  @Test void storageFailureStillReturnsServiceUnavailable() throws Exception {
    mvc.perform(get("/storage-failure"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
  }

  @RestController
  static class DownloadController {
    @GetMapping("/disconnect") void disconnect() throws IOException {
      throw new IOException("FastDFS download failed", new ClientAbortException("Connection reset by peer"));
    }
    @GetMapping(value = "/stream-disconnect", produces = "application/octet-stream")
    org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody stream() {
      return output -> {
        output.write(42);
        output.flush();
        throw new IOException("FastDFS download failed", new ClientAbortException("Connection reset by peer"));
      };
    }
    @GetMapping("/raw-reset") void rawReset() throws IOException {
      var ex = new IOException("Connection reset by peer");
      ex.setStackTrace(new StackTraceElement[] {
          new StackTraceElement("org.apache.catalina.connector.OutputBuffer", "realWriteBytes", "OutputBuffer.java", 329)
      });
      throw ex;
    }
    @GetMapping("/storage-failure") void storageFailure() throws IOException {
      throw new IOException("FastDFS download failed", new IOException("Connection reset by peer"));
    }
  }
}
