package com.cendodrive.ai.claw;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ClawVectorStore {
  public record Hit(long ownerId,long fileId,long generation,int chunk,String text,double score) {}
  private final RestClient client;
  private final ClawSettings settings;
  private volatile boolean initialized;
  @Autowired public ClawVectorStore(RestClient.Builder builder,ClawSettings settings) { this(ClawHttp.client(builder,15),settings); }
  ClawVectorStore(RestClient client,ClawSettings settings) { this.client=client; this.settings=settings; }
  private String path(String suffix) { return settings.vectorBaseUrl+"/collections/"+settings.collection()+suffix; }
  private RestClient.RequestBodySpec request(org.springframework.http.HttpMethod method,String suffix) {
    var request=client.method(method).uri(path(suffix)).contentType(MediaType.APPLICATION_JSON);
    if(!settings.vectorKey.isBlank()) request.header("api-key",settings.vectorKey);
    return request;
  }
  private JsonNode post(String suffix,Object body,boolean missingAllowed) {
    return ClawHttp.json(request(org.springframework.http.HttpMethod.POST,suffix).body(body),"CLAW_VECTOR_UNAVAILABLE",missingAllowed);
  }
  private synchronized void initialize() {
    if(initialized) return;
    settings.requireEnabled();
    var response=ClawHttp.json(request(org.springframework.http.HttpMethod.GET,""),"CLAW_VECTOR_UNAVAILABLE",true);
    if(response==null) {
      // Concurrent creators are harmless: if create loses the race, verify the winner.
      try { ClawHttp.json(request(org.springframework.http.HttpMethod.PUT,"").body(Map.of("vectors",Map.of("size",settings.dimensions,"distance","Cosine"))),"CLAW_VECTOR_UNAVAILABLE",false); }
      catch(ClawFailure ex) {
        response=ClawHttp.json(request(org.springframework.http.HttpMethod.GET,""),"CLAW_VECTOR_UNAVAILABLE",false);
        verifyCollection(response);
      }
    } else verifyCollection(response);
    initialized=true;
  }
  private void verifyCollection(JsonNode response) {
    var config=response.path("result").path("config").path("params").path("vectors");
    if(config.path("size").asInt()!=settings.dimensions || !"Cosine".equals(config.path("distance").asText()))
      throw new ClawFailure("CLAW_VECTOR_INVALID");
  }
  public void upsert(long owner,long file,long generation,List<String> chunks,List<List<Double>> vectors) {
    if(chunks.isEmpty() || chunks.size()!=vectors.size() || chunks.size()>128) throw new IllegalArgumentException("Invalid vector batch");
    initialize();
    for(int from=0;from<chunks.size();from+=4) {
      var points=new ArrayList<Map<String,Object>>();
      for(int i=from;i<Math.min(from+4,chunks.size());i++) {
        var id=UUID.nameUUIDFromBytes((owner+":"+file+":"+generation+":"+i).getBytes(StandardCharsets.UTF_8)).toString();
        points.add(Map.of("id",id,"vector",vectors.get(i),"payload",Map.of("owner_id",owner,"file_id",file,"generation",generation,"chunk",i,"text",chunks.get(i))));
      }
      ClawHttp.json(request(org.springframework.http.HttpMethod.PUT,"/points?wait=true").body(Map.of("points",points)),"CLAW_VECTOR_UNAVAILABLE",false);
    }
  }
  private static Map<String,Object> match(String key,Object value) { return Map.of("key",key,"match",Map.of("value",value)); }
  public void delete(long owner,long file,Long generation) {
    deleteCollection(settings.collection(),owner,file,generation);
  }
  public void deleteCollection(String collection,long owner,long file,Long generation) {
    settings.requireEnabled();
    if(!collection.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid vector collection");
    var conditions=new ArrayList<Map<String,Object>>(); conditions.add(match("owner_id",owner)); conditions.add(match("file_id",file));
    if(generation!=null) conditions.add(match("generation",generation));
    var request=client.post().uri(settings.vectorBaseUrl+"/collections/"+collection+"/points/delete?wait=true").contentType(MediaType.APPLICATION_JSON);
    if(!settings.vectorKey.isBlank()) request.header("api-key",settings.vectorKey);
    ClawHttp.json(request.body(Map.of("filter",Map.of("must",conditions))),"CLAW_VECTOR_UNAVAILABLE",true);
  }
  public List<Hit> query(long owner,Map<Long,Long> currentGenerations,List<Double> vector) {
    settings.requireEnabled(); if(currentGenerations.isEmpty()) return List.of();
    if(currentGenerations.size()>5000 || vector.size()!=settings.dimensions) throw new IllegalArgumentException("Invalid vector query scope");
    currentGenerations=Map.copyOf(currentGenerations);
    var groups=new TreeMap<Long,List<Long>>();
    currentGenerations.forEach((id,generation)->{
      if(id<1 || generation<1) throw new IllegalArgumentException("Invalid vector query scope");
      groups.computeIfAbsent(generation,key->new ArrayList<>()).add(id);
    });
    groups.values().forEach(Collections::sort);
    var scopes=groups.entrySet().stream().map(group->Map.of("must",List.of(
        Map.of("key","file_id","match",Map.of("any",group.getValue())),match("generation",group.getKey())))).toList();
    // File/generation pairs must be filtered remotely BEFORE score ranking and the top-24 limit.
    var filter=Map.of("must",List.of(match("owner_id",owner)),"should",scopes);
    var response=post("/points/query",Map.of("query",vector,"filter",filter,"limit",24,"with_payload",true,"score_threshold",0.3),false);
    var points=response==null?null:response.path("result").path("points");
    if(points==null || !points.isArray() || points.size()>24) throw new ClawFailure("CLAW_VECTOR_INVALID");
    var result=new ArrayList<Hit>();
    for(var point:points) {
      var p=point.path("payload");
      if(!p.path("owner_id").isIntegralNumber() || !p.path("file_id").isIntegralNumber() || !p.path("generation").isIntegralNumber()
          || !p.path("owner_id").canConvertToLong() || !p.path("file_id").canConvertToLong() || !p.path("generation").canConvertToLong()
          || !p.path("chunk").isIntegralNumber() || !p.path("chunk").canConvertToInt() || !p.path("text").isTextual() || !point.path("score").isNumber()) throw new ClawFailure("CLAW_VECTOR_INVALID");
      var hit=new Hit(p.path("owner_id").asLong(),p.path("file_id").asLong(),p.path("generation").asLong(),p.path("chunk").asInt(),p.path("text").asText(),point.path("score").asDouble());
      if(hit.ownerId()!=owner || !Objects.equals(currentGenerations.get(hit.fileId()),hit.generation()) || hit.generation()<1 || hit.chunk()<0 || hit.chunk()>=128
          || hit.text().isBlank() || hit.text().length()>1800 || !Double.isFinite(hit.score())) throw new ClawFailure("CLAW_VECTOR_INVALID");
      result.add(hit);
    }
    return List.copyOf(result);
  }
}
