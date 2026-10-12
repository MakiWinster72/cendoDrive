package com.cendodrive.ai.claw;

import com.cendodrive.auth.AuthService;
import com.cendodrive.config.*;
import com.cendodrive.ai.claw.ClawDtos.*;
import com.cendodrive.user.User;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ClawController.class)
@Import({SecurityConfig.class,CorsConfig.class})
class ClawSecurityTest {
  @Autowired MockMvc mvc;@MockBean AuthService auth;@MockBean ClawSearchService service;
  @BeforeEach void authenticate() {var user=mock(User.class);when(user.getId()).thenReturn(7L);when(auth.authenticate("token")).thenReturn(user);}
  @Test void allClawEndpointsRequireAuthentication() throws Exception {
    mvc.perform(get("/api/ai/claw/status")).andExpect(status().isUnauthorized());
    for(String path:List.of("/api/ai/claw/ask","/api/ai/claw/reindex","/api/ai/claw/files/1/reindex"))
      mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());verifyNoInteractions(service);
  }
  @Test void statusUsesAuthenticatedOwnerAndContainsNoCredentials() throws Exception {
    when(service.status(7L)).thenReturn(new Status(true,"Qwen/Qwen3-VL-Embedding-8B",1,0,0,0,0,List.of()));
    mvc.perform(get("/api/ai/claw/status").header("Authorization","Bearer token").param("ownerId","99"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.key").doesNotExist()).andExpect(jsonPath("$.baseUrl").doesNotExist());verify(service).status(7L);
  }
  @Test void questionCannotOverrideOwnerAndResponseIncludesSources() throws Exception {
    when(service.ask(eq(7L),any())).thenReturn(new Answer("三万元[1]",List.of(new Source(1,"12","预算.txt","/项目/预算.txt","三万元")),true));
    mvc.perform(post("/api/ai/claw/ask").header("Authorization","Bearer token").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"预算？\",\"ownerId\":99}"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.sources[0].fileId").value("12"));verify(service).ask(eq(7L),any());
  }
  @Test void invalidQuestionsAreRejectedBeforeRetrieval() throws Exception {
    for(String body:List.of("{}","{\"question\":\" \"}","{\"question\":null}","{\"question\":\""+"x".repeat(2001)+"\"}","{\"question\":\"预算？\",\"previousQuestion\":\""+"x".repeat(301)+"\"}"))
      mvc.perform(post("/api/ai/claw/ask").header("Authorization","Bearer token").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());verifyNoInteractions(service);
  }
  @Test void reindexNeedsExplicitConfirmationAndUsesAuthenticatedOwner() throws Exception {
    for(String path:List.of("/api/ai/claw/reindex","/api/ai/claw/files/12/reindex"))
      mvc.perform(post(path).header("Authorization","Bearer token").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    when(service.reindexFile(eq(7L),eq(12L),any())).thenReturn(new ReindexResult(1));
    mvc.perform(post("/api/ai/claw/files/12/reindex").header("Authorization","Bearer token").contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.queued").value(1));verify(service).reindexFile(eq(7L),eq(12L),any());
  }
  @Test void busyAndUnavailableErrorsStaySafeAndDoNotBecomeAuthFailures() throws Exception {
    for(String code:List.of("CLAW_BUSY","CLAW_FILE_UNAVAILABLE","CLAW_VECTOR_UNAVAILABLE")) {
      when(service.ask(eq(7L),any())).thenThrow(new ClawFailure(code));
      mvc.perform(post("/api/ai/claw/ask").header("Authorization","Bearer token").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"预算？\"}"))
          .andExpect(status().is(code.equals("CLAW_BUSY")?429:code.equals("CLAW_FILE_UNAVAILABLE")?404:502))
          .andExpect(jsonPath("$.code").value(code)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
  }
}
