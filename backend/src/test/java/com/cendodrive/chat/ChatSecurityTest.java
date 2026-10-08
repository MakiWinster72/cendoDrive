package com.cendodrive.chat;

import com.cendodrive.auth.AuthService;
import com.cendodrive.config.*;
import com.cendodrive.user.User;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatController.class)
@Import({SecurityConfig.class,CorsConfig.class})
class ChatSecurityTest {
  @Autowired MockMvc mvc;
  @MockBean AuthService auth;
  @MockBean ChatService chat;
  @Test void anonymousCannotSearchReadOrSend() throws Exception {
    for(String path:List.of("/api/chat/users?q=bob","/api/chat/groups?q=room","/api/chat/rooms","/api/chat/rooms/room/messages")) mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/chat/groups").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/chat/rooms/room/messages").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"冒充\"}")).andExpect(status().isUnauthorized());
    verifyNoInteractions(chat);
  }
  @Test void creationAndMessagesUseAuthenticatedPrincipal() throws Exception {
    User owner=mock(User.class); when(auth.authenticate("token")).thenReturn(owner);
    when(chat.create(owner,"测试群","",true)).thenReturn(new ChatService.Room("room","测试群","",true,true));
    mvc.perform(post("/api/chat/groups").header("Authorization","Bearer token").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"测试群\",\"description\":\"\",\"searchable\":true,\"ownerId\":99}")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value("room"));
    mvc.perform(post("/api/chat/rooms/room/messages").header("Authorization","Bearer token").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"你好\",\"senderId\":99}")).andExpect(status().isOk());
    verify(chat).send(owner,"room","你好");verify(chat).create(owner,"测试群","",true);
  }
  @Test void blankAndOversizeInputsAreRejectedBeforeService() throws Exception {
    when(auth.authenticate("token")).thenReturn(mock(User.class));
    for(String name:List.of(" ","x".repeat(21))) mvc.perform(post("/api/chat/groups").header("Authorization","Bearer token").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\""+name+"\",\"description\":\"\"}")).andExpect(status().isBadRequest());
    for(String content:List.of(" ","x".repeat(2001))) mvc.perform(post("/api/chat/rooms/room/messages").header("Authorization","Bearer token").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\""+content+"\"}")).andExpect(status().isBadRequest());
    verifyNoInteractions(chat);
  }
}
