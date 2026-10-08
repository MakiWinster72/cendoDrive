package com.cendodrive.chat;

import com.cendodrive.auth.AuthService;
import com.cendodrive.drive.DriveFileRepository;
import com.cendodrive.storage.FileStorage;
import com.cendodrive.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:chat_file_workflow;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureMockMvc
class ChatFileWorkflowTest {
  @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate db;
  @Autowired UserRepository users; @Autowired DriveFileRepository files;
  @MockBean AuthService auth; @MockBean FileStorage storage;
  Map<String,byte[]> blobs=new HashMap<>();
  @BeforeEach void setup() throws Exception {
    for(int i=1;i<=3;i++) {
      db.update("INSERT INTO users(id,username,password_hash,nickname,created_at,updated_at) VALUES (?,?,'hash',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",i,"fileuser"+i,"用户"+i);
      when(auth.authenticate("token"+i)).thenReturn(users.findById((long)i).orElseThrow());
    }
    when(storage.upload(any(),anyLong(),anyString())).thenAnswer(call->{String key=UUID.randomUUID().toString(); blobs.put(key,((InputStream)call.getArgument(0)).readAllBytes()); return key;});
    doAnswer(call->{((OutputStream)call.getArgument(1)).write(blobs.get(call.getArgument(0)));return null;}).when(storage).download(anyString(),any());
  }
  @Test void uploadSendSaveAndIndependentCopySurvivesSourceDeletion() throws Exception {
    String id=mapper.readTree(mvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file","hello.txt","text/plain","hello".getBytes())).header("Authorization","Bearer token1")).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString()).get("id").asText();
    String room=mapper.readTree(mvc.perform(post("/api/chat/direct/2").header("Authorization","Bearer token1")).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString()).get("id").asText();
    String url="/api/chat/rooms/"+room+"/files";
    mvc.perform(post(url).header("Authorization","Bearer token2").contentType(MediaType.APPLICATION_JSON).content("{\"fileId\":"+id+"}")).andExpect(status().isNotFound());
    mvc.perform(post(url).header("Authorization","Bearer token1").contentType(MediaType.APPLICATION_JSON).content("{\"fileId\":"+id+"}")).andExpect(status().isOk());
    var message=mapper.readTree(mvc.perform(get("/api/chat/rooms/"+room+"/messages").header("Authorization","Bearer token2")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0);
    assertEquals("hello.txt",message.get("attachment").get("name").asText());
    String detail=url+"/"+message.get("id").asText();
    String save=detail+"/save";
    for(String endpoint:List.of(detail,detail+"/download")) {
      mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
      mvc.perform(get(endpoint).header("Authorization","Bearer token3")).andExpect(status().isForbidden());
    }
    mvc.perform(get(detail).header("Authorization","Bearer token2")).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("hello.txt")).andExpect(jsonPath("$.parentId").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));
    var originalDownload=mvc.perform(get(detail+"/download").header("Authorization","Bearer token2")).andExpect(request().asyncStarted()).andReturn();
    assertEquals("hello",mvc.perform(asyncDispatch(originalDownload)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    mvc.perform(get(url+"/999999").header("Authorization","Bearer token2")).andExpect(status().isNotFound());
    String otherRoom=mapper.readTree(mvc.perform(post("/api/chat/direct/3").header("Authorization","Bearer token1")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
    String wrongRoom="/api/chat/rooms/"+otherRoom+"/files/"+message.get("id").asText();
    mvc.perform(get(wrongRoom).header("Authorization","Bearer token1")).andExpect(status().isNotFound());
    mvc.perform(get(wrongRoom+"/download").header("Authorization","Bearer token1")).andExpect(status().isNotFound());
    mvc.perform(post(wrongRoom+"/save").header("Authorization","Bearer token1")).andExpect(status().isNotFound());
    mvc.perform(post(save)).andExpect(status().isUnauthorized());
    mvc.perform(post(save).header("Authorization","Bearer token3")).andExpect(status().isForbidden());
    String copy=mapper.readTree(mvc.perform(post(save).header("Authorization","Bearer token2").contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":3}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
    String folder=mapper.readTree(mvc.perform(post("/api/files/folder").header("Authorization","Bearer token2").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"聊天转存\",\"parentId\":null}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    String nestedCopy=mapper.readTree(mvc.perform(post(save).header("Authorization","Bearer token2").contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":"+folder+"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
    assertEquals(Long.parseLong(folder),files.findById(Long.parseLong(nestedCopy)).orElseThrow().getParentId());
    mvc.perform(post(save).header("Authorization","Bearer token1").contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":"+folder+"}")).andExpect(status().isNotFound());
    mvc.perform(post(save).header("Authorization","Bearer token2").contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":-1}")).andExpect(status().isBadRequest());
    var copied=files.findById(Long.parseLong(copy)).orElseThrow();
    assertEquals(2L,copied.getOwnerId()); assertNull(copied.getParentId());
    assertNotEquals(files.findById(Long.parseLong(id)).orElseThrow().getStorageKey(),copied.getStorageKey());
    assertEquals("hello",new String(blobs.get(copied.getStorageKey())));
    for(String action:List.of("/trash","/trash/delete")) mvc.perform(post("/api/files"+action).header("Authorization","Bearer token1").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":["+id+"]}")).andExpect(status().is2xxSuccessful());
    mvc.perform(post(save).header("Authorization","Bearer token2")).andExpect(status().isNotFound());
    mvc.perform(get(detail).header("Authorization","Bearer token2")).andExpect(status().isNotFound());
    mvc.perform(get(detail+"/download").header("Authorization","Bearer token2")).andExpect(status().isNotFound());
    var download=mvc.perform(get("/api/files/"+copy+"/download").header("Authorization","Bearer token2")).andExpect(request().asyncStarted()).andReturn();
    assertEquals("hello",mvc.perform(asyncDispatch(download)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }
}
