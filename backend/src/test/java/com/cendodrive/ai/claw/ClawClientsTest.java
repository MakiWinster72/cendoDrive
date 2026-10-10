package com.cendodrive.ai.claw;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ClawClientsTest {
  ClawSettings settings(int dimension) { return new ClawSettings(true,"https://model.test/v1/","Qwen/Qwen3-VL-Embedding-8B","secret",dimension,"http://vector.test:6333","vector-secret","cendo_claw"); }
  @Test void validatesConfigurationAndIsolatesModelCollections() {
    assertThrows(IllegalArgumentException.class,()->new ClawSettings(true,"https://model.test/v1","m","",2,"http://vector.test","","claw"));
    assertThrows(IllegalArgumentException.class,()->new ClawSettings(true,"https://user:secret@model.test","m","secret",2,"http://vector.test","","claw"));
    assertThrows(IllegalArgumentException.class,()->new ClawSettings(true,"https://model.test/v1","m","secret",2,"http://vector.test","","bad/name"));
    assertNotEquals(settings(2).collection(),settings(3).collection());
    assertFalse(settings(2).toString().contains("secret"));
  }
  @Test void sendsBatchAndRestoresProviderIndexOrder() {
    var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("https://model.test/v1/embeddings")).andExpect(header("Authorization","Bearer secret"))
        .andExpect(content().json("{\"model\":\"Qwen/Qwen3-VL-Embedding-8B\",\"input\":[\"文件\",\"问题\"],\"encoding_format\":\"float\"}"))
        .andRespond(withSuccess("{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}",MediaType.APPLICATION_JSON));
    var vectors=new ClawEmbeddingClient(builder.build(),settings(2)).embed(List.of("文件","问题"));
    assertEquals(List.of(1.0,0.0),vectors.get(0)); assertEquals(List.of(0.0,1.0),vectors.get(1)); server.verify();
  }
  @Test void rejectsWrongDimensionDuplicateIndexZeroAndOversizedBatch() {
    for(String data:List.of("[{\"index\":0,\"embedding\":[1]}]","[{\"index\":0,\"embedding\":[0,0]}]","[{\"index\":0,\"embedding\":[1,0]},{\"index\":0,\"embedding\":[1,0]}]")) {
      var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
      server.expect(requestTo("https://model.test/v1/embeddings")).andRespond(withSuccess("{\"data\":"+data+"}",MediaType.APPLICATION_JSON));
      var error=assertThrows(ClawFailure.class,()->new ClawEmbeddingClient(builder.build(),settings(2)).embed(List.of("文件")));
      assertEquals("CLAW_EMBEDDING_INVALID",error.code()); server.verify();
    }
    assertThrows(IllegalArgumentException.class,()->new ClawEmbeddingClient(RestClient.builder().build(),settings(2)).embed(Collections.nCopies(5,"x")));
  }
  @Test void sanitizesProviderAndVectorFailures() {
    var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("https://model.test/v1/embeddings")).andRespond(withUnauthorizedRequest().body("secret"));
    var error=assertThrows(ClawFailure.class,()->new ClawEmbeddingClient(builder.build(),settings(2)).embed(List.of("x")));
    assertFalse(error.getMessage().contains("secret")); server.verify();
  }
  @Test void createsOrValidatesCollectionBeforeWriting() {
    var settings=settings(2); var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://vector.test:6333/collections/"+settings.collection())).andExpect(header("api-key","vector-secret")).andRespond(withResourceNotFound());
    server.expect(requestTo("http://vector.test:6333/collections/"+settings.collection())).andExpect(content().json("{\"vectors\":{\"size\":2,\"distance\":\"Cosine\"}}"))
        .andRespond(withSuccess("{\"status\":\"ok\",\"result\":true}",MediaType.APPLICATION_JSON));
    server.expect(requestTo("http://vector.test:6333/collections/"+settings.collection()+"/points?wait=true"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"owner_id\":7")))
        .andRespond(withSuccess("{\"status\":\"ok\"}",MediaType.APPLICATION_JSON));
    new ClawVectorStore(builder.build(),settings).upsert(7L,12L,3L,List.of("合成内容"),List.of(List.of(1.0,0.0))); server.verify();
  }
  @Test void rejectsExistingCollectionDimensionMismatch() {
    var settings=settings(2);var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://vector.test:6333/collections/"+settings.collection()))
        .andRespond(withSuccess("{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}}}}",MediaType.APPLICATION_JSON));
    assertEquals("CLAW_VECTOR_INVALID",assertThrows(ClawFailure.class,()->new ClawVectorStore(builder.build(),settings)
        .upsert(7L,12L,1L,List.of("x"),List.of(List.of(1.0,0.0)))).code());server.verify();
  }
  @Test void generationDeleteKeepsNewerPointsAndMissingCollectionIsSafe() {
    var settings=settings(2);var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://vector.test:6333/collections/"+settings.collection()+"/points/delete?wait=true"))
        .andExpect(content().json("{\"filter\":{\"must\":[{\"key\":\"owner_id\",\"match\":{\"value\":7}},{\"key\":\"file_id\",\"match\":{\"value\":12}},{\"key\":\"generation\",\"match\":{\"value\":3}}]}}"))
        .andRespond(withResourceNotFound());
    new ClawVectorStore(builder.build(),settings).delete(7L,12L,3L);server.verify();
  }
  @Test void rejectsVectorServiceReturningForeignPayload() {
    var settings=settings(2);var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://vector.test:6333/collections/"+settings.collection()+"/points/query"))
        .andRespond(withSuccess("{\"result\":{\"points\":[{\"score\":0.8,\"payload\":{\"owner_id\":8,\"file_id\":12,\"generation\":3,\"chunk\":0,\"text\":\"不该泄露\"}}]}}",MediaType.APPLICATION_JSON));
    assertEquals("CLAW_VECTOR_INVALID",assertThrows(ClawFailure.class,()->new ClawVectorStore(builder.build(),settings).query(7L,List.of(12L),List.of(1.0,0.0))).code());server.verify();
  }
  @Test void scopesQueryToOwnerAndAllowedFileIds() {
    var settings=settings(2); var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://vector.test:6333/collections/"+settings.collection()+"/points/query"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("owner_id")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"any\":[12]")))
        .andRespond(withSuccess("{\"result\":{\"points\":[{\"score\":0.8,\"payload\":{\"owner_id\":7,\"file_id\":12,\"generation\":3,\"chunk\":0,\"text\":\"合成内容\"}}]}}",MediaType.APPLICATION_JSON));
    var hits=new ClawVectorStore(builder.build(),settings).query(7L,List.of(12L),List.of(1.0,0.0));
    assertEquals(1,hits.size()); assertEquals(12L,hits.get(0).fileId()); server.verify();
  }
}
