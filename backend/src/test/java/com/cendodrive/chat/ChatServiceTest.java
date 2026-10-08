package com.cendodrive.chat;

import com.cendodrive.user.User;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatServiceTest {
  JdbcTemplate db; ChatService chat; User alice,bob,other;
  @BeforeEach void setup() {
    String url="jdbc:h2:mem:chat-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url,"sa","").load().migrate();
    db=new JdbcTemplate(new DriverManagerDataSource(url,"sa","")); chat=new ChatService(db);
    for(int i=1;i<=3;i++) db.update("INSERT INTO users(id,username,password_hash,nickname,created_at,updated_at) VALUES (?,?, 'hash',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",i,"user"+i,"用户"+i);
    alice=user(1); bob=user(2); other=user(3);
  }
  User user(long id) { User u=mock(User.class); when(u.getId()).thenReturn(id); return u; }
  @Test void searchByIdAndUsernameDoesNotExposeCredentials() {
    assertEquals(2,chat.search("2").get(0).id());
    assertEquals(3,chat.search("USER").size());
    assertTrue(chat.search("%").isEmpty());
    db.update("UPDATE users SET is_active=FALSE WHERE id=3");
    assertTrue(chat.search("user3").isEmpty());
    assertTrue(chat.search(" ").isEmpty());
  }
  @Test void directChatIsReusedAndMessagesArePrivate() {
    var room=chat.direct(alice,2);
    assertEquals(room.id(),chat.direct(bob,1).id());
    assertEquals("用户2",chat.rooms(alice).get(0).name());
    assertEquals("用户1",chat.rooms(bob).get(0).name());
    chat.send(alice,room.id(),"你好");
    var message=chat.messages(bob,room.id(),0).get(0);
    assertEquals("你好",message.content());
    assertTrue(chat.messages(bob,room.id(),message.id()).isEmpty());
    assertThrows(ResponseStatusException.class,()->chat.messages(other,room.id(),0));
    assertThrows(ResponseStatusException.class,()->chat.send(other,room.id(),"冒充"));
    assertThrows(ResponseStatusException.class,()->chat.direct(alice,1));
  }
  @Test void groupsCanOnlyBeJoinedWhenSearchable() {
    var group=chat.create(alice,"测试群","介绍",true);
    assertEquals(group.id(),chat.groups(group.id()).get(0).id());
    assertThrows(ResponseStatusException.class,()->chat.messages(bob,group.id(),0));
    chat.join(bob,group.id()); chat.join(bob,group.id());
    chat.send(bob,group.id(),"群消息");
    assertEquals("群消息",chat.messages(alice,group.id(),0).get(0).content());
    var privateGroup=chat.create(alice,"私密群","",false);
    assertTrue(chat.groups(privateGroup.id()).isEmpty());
    assertThrows(ResponseStatusException.class,()->chat.join(bob,privateGroup.id()));
  }
}
