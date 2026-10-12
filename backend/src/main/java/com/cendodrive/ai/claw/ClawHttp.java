package com.cendodrive.ai.claw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class ClawHttp {
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final int MAX_RESPONSE=2*1024*1024;
  static RestClient client(RestClient.Builder builder,int readSeconds) {
    var factory=new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5)); factory.setReadTimeout(Duration.ofSeconds(readSeconds));
    return builder.requestFactory(factory).build();
  }
  static JsonNode json(RestClient.RequestHeadersSpec<?> request,String error,boolean allowMissing) {
    try {
      return request.exchange((req,res)->{
        if(allowMissing && res.getStatusCode().value()==404) return null;
        if(!res.getStatusCode().is2xxSuccessful()) throw new ClawFailure(error);
        byte[] bytes=res.getBody().readNBytes(MAX_RESPONSE+1);
        if(bytes.length>MAX_RESPONSE) throw new ClawFailure(error);
        try { return JSON.readTree(bytes); }
        catch(IOException ex) { throw new ClawFailure(error); }
      });
    } catch(RestClientException ex) { throw new ClawFailure(error); }
  }
  private ClawHttp() {}
}
