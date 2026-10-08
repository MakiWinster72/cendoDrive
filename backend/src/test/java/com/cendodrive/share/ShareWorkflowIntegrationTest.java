package com.cendodrive.share;

import com.cendodrive.auth.AuthService;
import com.cendodrive.drive.DriveFileRepository;
import com.cendodrive.storage.FileStorage;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:share_workflow;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CENDO_TEST_REDIS", matches = "true")
class ShareWorkflowIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired AuthService auth;
  @Autowired DriveFileRepository files;
  @Autowired StringRedisTemplate redis;
  @Autowired ShareLinkRepository links;
  @MockBean FileStorage storage;
  final List<String> sessions = new ArrayList<>();
  final List<String> tokens = new ArrayList<>();
  final Map<String, byte[]> blobs = new HashMap<>();

  @BeforeEach void storage() throws Exception {
    when(storage.upload(any(), anyLong(), anyString())).thenAnswer(call -> {
      String key = UUID.randomUUID().toString();
      blobs.put(key, ((InputStream) call.getArgument(0)).readAllBytes());
      return key;
    });
    doAnswer(call -> {
      ((OutputStream) call.getArgument(1)).write(blobs.get(call.getArgument(0)));
      return null;
    }).when(storage).download(anyString(), any());
  }

  @AfterEach void cleanup() {
    try { tokens.forEach(token -> redis.delete(ShareAccessStore.key(token))); }
    finally { sessions.forEach(auth::logout); }
  }

  JsonNode request(MockHttpServletRequestBuilder request) throws Exception {
    return mapper.readTree(mvc.perform(request).andExpect(status().is2xxSuccessful())
        .andReturn().getResponse().getContentAsString());
  }

  JsonNode login() throws Exception {
    String username = "share_test_" + UUID.randomUUID().toString().replace("-", "");
    request(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("username", username, "password", "test-password"))));
    JsonNode result = request(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("username", username, "password", "test-password"))));
    sessions.add(result.get("token").asText());
    return result;
  }

  JsonNode share(String bearer, String fileId, int seconds) throws Exception {
    JsonNode result = request(post("/api/shares").header("Authorization", "Bearer " + bearer)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"fileId\":\"" + fileId + "\",\"expiresInSeconds\":" + seconds + "}"));
    tokens.add(result.get("token").asText());
    return result;
  }

  String download(String url, String bearer) throws Exception {
    var request = get(url);
    if (bearer != null) request.header("Authorization", "Bearer " + bearer);
    MvcResult result = mvc.perform(request).andExpect(MockMvcResultMatchers.request().asyncStarted()).andReturn();
    return mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
  }

  @Test void twoUsersShareAndSaveWithoutLosingIsolationOrCopyAfterDeletion() throws Exception {
    JsonNode alice = login(), bob = login();
    String a = alice.get("token").asText(), b = bob.get("token").asText();
    String id = request(multipart("/api/files/upload")
        .file(new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes()))
        .header("Authorization", "Bearer " + a)).get("id").asText();
    assertTrue(request(get("/api/files").header("Authorization", "Bearer " + b)).isEmpty());
    mvc.perform(get("/api/files/" + id + "/download").header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/shares").header("Authorization", "Bearer " + b)
        .contentType(MediaType.APPLICATION_JSON).content("{\"fileId\":" + id + ",\"expiresInSeconds\":60}"))
        .andExpect(status().isNotFound());
    JsonNode share = share(a, id, 60);
    String token = share.get("token").asText(), url = "/api/shares/" + token;
    assertEquals("hello.txt", request(get(url)).get("file").get("name").asText());
    assertEquals("hello", download(url + "/download", null));
    assertTrue(request(get("/api/shares").header("Authorization", "Bearer " + b)).isEmpty());
    mvc.perform(delete("/api/shares/" + share.get("id").asText()).header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mvc.perform(post(url + "/save").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
    String copyId = request(post(url + "/save").header("Authorization", "Bearer " + b)
        .contentType(MediaType.APPLICATION_JSON).content("{}")).get("id").asText();
    var copy = files.findById(Long.parseLong(copyId)).orElseThrow();
    assertEquals(bob.get("user").get("id").asLong(), copy.getOwnerId());
    assertNotEquals(files.findById(Long.parseLong(id)).orElseThrow().getStorageKey(), copy.getStorageKey());
    mvc.perform(delete("/api/shares/" + share.get("id").asText()).header("Authorization", "Bearer " + a))
        .andExpect(status().isNoContent());
    mvc.perform(get(url)).andExpect(status().isNotFound());
    assertFalse(Boolean.TRUE.equals(redis.hasKey(ShareAccessStore.key(token))));
    for (String action : List.of("/trash", "/trash/delete"))
      mvc.perform(post("/api/files" + action).header("Authorization", "Bearer " + a)
          .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[" + id + "]}"))
          .andExpect(status().is2xxSuccessful());
    assertEquals("hello", download("/api/files/" + copyId + "/download", b));
    assertEquals(1, request(get("/api/files").header("Authorization", "Bearer " + b)).size());
    assertTrue(links.findById(share.get("id").asLong()).orElseThrow().isCancelled());
  }
}
