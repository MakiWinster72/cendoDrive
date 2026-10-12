package com.cendodrive.ai.claw;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only a random collection on an explicitly opted-in, loopback QA service. */
@EnabledIfEnvironmentVariable(named="CENDO_CLAW_QDRANT_QA_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
class ClawVectorWindowIntegrationTest {
  @Test void oldHighScoringPointsCannotHideCurrentEvidenceOrCrossFileGenerationPairs() {
    String base=System.getenv("CENDO_CLAW_QDRANT_QA_URL");
    var settings=new ClawSettings(true,"https://model.test/v1","qa-model","qa-key",2,base,"","claw_window_"+UUID.randomUUID().toString().substring(0,8));
    var client=RestClient.builder().build();var store=new ClawVectorStore(client,settings);
    try {
      store.upsert(7,12,1,Collections.nCopies(24,"旧资料"),Collections.nCopies(24,List.of(1.0,0.0)));
      store.upsert(7,12,2,List.of("当前预算"),List.of(List.of(0.8,0.6)));
      store.upsert(8,12,2,List.of("其他用户资料"),List.of(List.of(1.0,0.0)));
      store.upsert(7,13,2,List.of("其他文件旧代次"),List.of(List.of(1.0,0.0)));
      store.upsert(7,13,3,List.of("当前计划"),List.of(List.of(0.6,0.8)));
      // Reproduce the old algorithm against the actual engine: top-24 are all obsolete.
      var originalFilter=Map.of("must",List.of(Map.of("key","owner_id","match",Map.of("value",7)),Map.of("key","file_id","match",Map.of("value",12))));
      var baseline=client.post().uri(base+"/collections/"+settings.collection()+"/points/query").contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("query",List.of(1.0,0.0),"filter",originalFilter,"limit",24,"with_payload",true)).retrieve().body(JsonNode.class);
      assertNotNull(baseline);var points=baseline.path("result").path("points");assertEquals(24,points.size());
      points.forEach(point->assertEquals(1,point.path("payload").path("generation").asInt()));
      var hits=store.query(7,Map.of(12L,2L,13L,3L),List.of(1.0,0.0));
      assertEquals(2,hits.size());assertEquals(Set.of("当前预算","当前计划"),new HashSet<>(hits.stream().map(ClawVectorStore.Hit::text).toList()));
      assertTrue(hits.stream().allMatch(hit->hit.ownerId()==7));
      store.delete(7,12,1L);assertEquals(2,store.query(7,Map.of(12L,2L,13L,3L),List.of(1.0,0.0)).size());
    } finally {client.delete().uri(base+"/collections/"+settings.collection()).retrieve().toBodilessEntity();}
  }
}
