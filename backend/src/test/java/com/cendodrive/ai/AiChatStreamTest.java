package com.cendodrive.ai;

import com.cendodrive.ai.AiChatDtos.*;
import com.cendodrive.ai.AiChatService.AiFailure;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.*;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AiChatStreamTest {
  final RestClient.Builder builder=RestClient.builder();
  final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
  final AiChatService service=new AiChatService(builder.build(),"https://model.test/v1","model","private-key");
  final ChatRequest request=new ChatRequest(List.of(new Message("user","你好")));
  record Event(String name,Map<String,String> data) {}
  static String delta(String text) {
    try { return "data: "+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
        Map.of("choices",List.of(Map.of("delta",Map.of("content",text)))))+"\n\n"; }
    catch(IOException ex) { throw new UncheckedIOException(ex); }
  }
  List<Event> receive() throws IOException {
    var events=new ArrayList<Event>(); service.stream(request,(event,data)->events.add(new Event(event,data))); server.verify(); return events;
  }
  void upstream(String body) {
    server.expect(requestTo("https://model.test/v1/chat/completions")).andRespond(withSuccess(body,MediaType.TEXT_EVENT_STREAM));
  }

  @Test void sendsStreamingRequestAndNormalizesUnicodeNewlinesAndCompletion() throws Exception {
    server.expect(requestTo("https://model.test/v1/chat/completions"))
        .andExpect(header("Authorization","Bearer private-key"))
        .andExpect(header("Accept","text/event-stream"))
        .andExpect(content().json("{\"model\":\"model\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}"))
        .andRespond(withSuccess(": keep-alive\r\n\r\ndata: {\"choices\":\ndata: [{\"delta\":{\"role\":\"assistant\"}}]}\n\n"
            +delta("你好🙂\n")+delta("继续")+"data: [DONE]\n\n",MediaType.TEXT_EVENT_STREAM));
    assertEquals(List.of(new Event("delta",Map.of("content","你好🙂\n")),new Event("delta",Map.of("content","继续")),new Event("done",Map.of())),receive());
  }

  @Test void recognizesStopButRejectsTruncatedOrMissingCompletion() throws Exception {
    upstream(delta("answer")+"data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\r\r");
    assertEquals("done",receive().getLast().name());
    for(String ending:List.of("","data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n")) {
      server.reset(); upstream(delta("partial")+ending);
      var events=receive(); assertEquals("AI_INCOMPLETE_RESPONSE",events.getLast().data().get("code"));
      assertEquals("delta",events.getFirst().name()); assertTrue(events.stream().noneMatch(e->e.name().equals("done")));
    }
  }

  @Test void rejectsMalformedEmptyNonTextAndOversizedStreams() throws Exception {
    for(String body:List.of("data: invalid private-key\n\n","data: [DONE]\n\n",
        "data: {\"choices\":[{\"delta\":{\"content\":[]}}]}\n\n",
        "data: "+"x".repeat(131073)+"\n\n",delta("x".repeat(64001)))) {
      server.reset(); upstream(body);
      var events=receive(); assertEquals("error",events.getLast().name());
      assertEquals("AI_INVALID_RESPONSE",events.getLast().data().get("code"));
      assertFalse(events.toString().contains("private-key"));
    }
  }

  @Test void sanitizesProviderStatusErrorsAndJsonErrorEvents() throws Exception {
    for(HttpStatus status:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.TOO_MANY_REQUESTS)) {
      server.reset(); server.expect(requestTo("https://model.test/v1/chat/completions"))
          .andRespond(withStatus(status).body("private-key diagnostics"));
      var error=receive().getFirst(); assertEquals("error",error.name());
      assertEquals(status==HttpStatus.TOO_MANY_REQUESTS?"AI_RATE_LIMITED":"AI_UPSTREAM_ERROR",error.data().get("code"));
      assertFalse(error.toString().contains("private-key"));
    }
    server.reset(); upstream("data: {\"error\":{\"message\":\"private-key diagnostics\"}}\n\n");
    assertEquals("AI_UPSTREAM_ERROR",receive().getFirst().data().get("code"));
  }

  @Test void rejectsBufferedJsonAndSanitizesConnectionFailures() throws Exception {
    server.expect(requestTo("https://model.test/v1/chat/completions")).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
    assertEquals("AI_INVALID_RESPONSE",receive().getFirst().data().get("code"));
    server.reset(); server.expect(requestTo("https://model.test/v1/chat/completions"))
        .andRespond(withException(new java.net.SocketTimeoutException("private-key")));
    assertEquals("AI_UNAVAILABLE",receive().getFirst().data().get("code"));
  }

  @Test void downstreamDisconnectUnwindsAndClosesProviderWithoutSendingMoreEvents() {
    var closed=new AtomicBoolean();
    server.expect(requestTo("https://model.test/v1/chat/completions")).andRespond(req ->
        new MockClientHttpResponse((delta("first")+delta("later")).getBytes(StandardCharsets.UTF_8),HttpStatus.OK) {
          { getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM); }
          @Override public InputStream getBody() throws IOException {
            return new FilterInputStream(super.getBody()) {
              @Override public void close() throws IOException { closed.set(true); super.close(); }
            };
          }
        });
    var events=new ArrayList<String>();
    assertThrows(IOException.class,()->service.stream(request,(event,data)->{ events.add(event); throw new IOException("disconnected"); }));
    assertEquals(List.of("delta"),events); assertTrue(closed.get()); server.verify();
  }

  @Test void realHttpTransportDeliversFirstChunkBeforeProviderFinishes() throws Exception {
    var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var release=new CountDownLatch(1); var first=new CountDownLatch(1);
    http.createContext("/v1/chat/completions", exchange -> {
      exchange.getRequestBody().readAllBytes(); exchange.getResponseHeaders().set("Content-Type","text/event-stream");
      exchange.sendResponseHeaders(200,0);
      try(var output=exchange.getResponseBody()) {
        output.write(delta("第一段").getBytes(StandardCharsets.UTF_8)); output.flush();
        try { if(!release.await(5,TimeUnit.SECONDS)) return; } catch(InterruptedException ex) { Thread.currentThread().interrupt(); return; }
        output.write((delta("第二段")+"data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)); output.flush();
      }
    });
    http.start();
    var transport=new AiChatService(RestClient.builder(),"http://127.0.0.1:"+http.getAddress().getPort()+"/v1","model","private-key");
    var events=new CopyOnWriteArrayList<Event>();
    var worker=Executors.newSingleThreadExecutor();
    try {
      var result=worker.submit(()->{ transport.stream(request,(name,data)->{events.add(new Event(name,data));first.countDown();}); return null; });
      assertTrue(first.await(3,TimeUnit.SECONDS),"First chunk must arrive while the provider is still waiting");
      assertFalse(result.isDone()); assertEquals("第一段",events.getFirst().data().get("content"));
      release.countDown(); result.get(3,TimeUnit.SECONDS); assertEquals("done",events.getLast().name());
    } finally { release.countDown(); worker.shutdownNow(); http.stop(0); }
  }

  @Test void validatesBeforeStreamingAndRejectsExecutorSaturation() {
    var disabled=new AiChatService(RestClient.builder(),"","","");
    assertEquals("AI_NOT_CONFIGURED",assertThrows(AiFailure.class,()->disabled.stream(request,(e,d)->fail())).code());
    var executor=org.mockito.Mockito.mock(AiChatExecutor.class);
    org.mockito.Mockito.doThrow(new TaskRejectedException("busy")).when(executor).execute(org.mockito.ArgumentMatchers.any());
    var controller=new AiChatController(service,executor);
    assertEquals(HttpStatus.TOO_MANY_REQUESTS,assertThrows(AiFailure.class,()->controller.stream(request)).status());
  }
}
