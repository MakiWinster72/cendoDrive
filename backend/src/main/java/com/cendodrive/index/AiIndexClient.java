package com.cendodrive.index;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;

@Component
public class AiIndexClient {
  private final RestClient client;
  private final String baseUrl;
  private final boolean enabled;
  public AiIndexClient(@Value("${cendo.ai.enabled:false}") boolean enabled,
      @Value("${cendo.ai.base-url:}") String baseUrl) {
    this.enabled=enabled;
    this.baseUrl=baseUrl==null?"":baseUrl.trim();
    SimpleClientHttpRequestFactory requests=new SimpleClientHttpRequestFactory();
    requests.setConnectTimeout(java.time.Duration.ofSeconds(3));
    requests.setReadTimeout(java.time.Duration.ofSeconds(10));
    this.client=RestClient.builder().requestFactory(requests).build();
  }
  public boolean configured() { return enabled && !baseUrl.isBlank(); }

  public void deliver(AiIndexTask task) {
    Map<String,Object> body=new java.util.LinkedHashMap<>();
    body.put("eventId",task.getEventId()); body.put("operation",task.getOperation().name());
    body.put("fileId",task.getFileId().toString()); body.put("ownerId",task.getOwnerId().toString());
    body.put("revision",task.getRevision());
    if (task.getOperation()==AiIndexTask.Operation.UPSERT) {
      body.put("fileName",task.getFileName()); body.put("storageBackend",task.getStorageBackend());
      body.put("storageKey",task.getStorageKey());
    }
    client.post().uri(baseUrl+"/internal/ai/index-events").body(body).retrieve()
        .onStatus(status -> status.value()==400 || status.value()==401 || status.value()==403,
            (request,response) -> { throw new PermanentDeliveryException("Anna rejected event: HTTP "+response.getStatusCode().value()); })
        .onStatus(HttpStatusCode::isError,
            (request,response) -> { throw new RetryableDeliveryException("Anna unavailable: HTTP "+response.getStatusCode().value()); })
        .toBodilessEntity();
  }

  public static class PermanentDeliveryException extends RuntimeException { public PermanentDeliveryException(String message) { super(message); } }
  public static class RetryableDeliveryException extends RuntimeException { public RetryableDeliveryException(String message) { super(message); } }
}
