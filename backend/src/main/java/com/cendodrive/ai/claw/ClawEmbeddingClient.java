package com.cendodrive.ai.claw;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ClawEmbeddingClient {
  private final RestClient client;
  private final ClawSettings settings;
  @Autowired public ClawEmbeddingClient(RestClient.Builder builder,ClawSettings settings) { this(ClawHttp.client(builder,30),settings); }
  ClawEmbeddingClient(RestClient client,ClawSettings settings) { this.client=client; this.settings=settings; }
  public List<List<Double>> embed(List<String> input) {
    settings.requireEnabled();
    if(input.isEmpty() || input.size()>4 || input.stream().anyMatch(s->s==null || s.isBlank() || s.length()>2400))
      throw new IllegalArgumentException("Embedding batch requires 1..4 nonblank texts of at most 2400 characters");
    var response=ClawHttp.json(client.post().uri(settings.embeddingBaseUrl+"/embeddings")
        .header("Authorization","Bearer "+settings.embeddingKey).contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("model",settings.embeddingModel,"input",input,"encoding_format","float")),"CLAW_EMBEDDING_UNAVAILABLE",false);
    var data=response==null?null:response.path("data");
    if(data==null || !data.isArray() || data.size()!=input.size()) throw new ClawFailure("CLAW_EMBEDDING_INVALID");
    var result=new ArrayList<List<Double>>(Collections.nCopies(input.size(),null));
    for(var item:data) {
      if(!item.path("index").isIntegralNumber() || !item.path("index").canConvertToInt()) throw new ClawFailure("CLAW_EMBEDDING_INVALID");
      int index=item.path("index").asInt(); var vector=item.path("embedding");
      if(index<0 || index>=input.size() || result.get(index)!=null || !vector.isArray() || vector.size()!=settings.dimensions)
        throw new ClawFailure("CLAW_EMBEDDING_INVALID");
      var values=new ArrayList<Double>(settings.dimensions); double norm=0;
      for(var value:vector) {
        if(!value.isNumber() || !Double.isFinite(value.asDouble())) throw new ClawFailure("CLAW_EMBEDDING_INVALID");
        double number=value.asDouble(); norm+=number*number; values.add(number);
      }
      if(!Double.isFinite(norm) || norm<=0) throw new ClawFailure("CLAW_EMBEDDING_INVALID");
      result.set(index,List.copyOf(values));
    }
    return List.copyOf(result);
  }
}
