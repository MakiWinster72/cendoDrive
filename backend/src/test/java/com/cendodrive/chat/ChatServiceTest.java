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
  @Test void groupIdsAreFiveHexDigitsIncludingLeadingZeros() {
    for(int i=0;i<100;i++) assertTrue(chat.nextGroupId().matches("[0-9a-f]{5}"));
    var deterministic=spy(chat);
    doReturn("0000a").when(deterministic).nextGroupId();
    var group=deterministic.create(alice,"短群号","",true);
    assertEquals("0000a",group.id());
    assertEquals(group.id(),chat.groups(" 0000A ").get(0).id());
    chat.join(bob," 0000A ");
    chat.send(bob,group.id(),"短群号消息");
    assertEquals("短群号消息",chat.messages(alice,group.id(),0).get(0).content());
    assertEquals(group.id(),chat.rooms(bob).get(0).id());
  }
  @Test void duplicateGroupIdRetriesWithoutChangingExistingRoom() {
    var deterministic=spy(chat);
    doReturn("abcde","abcde","01234").when(deterministic).nextGroupId();
    var first=deterministic.create(alice,"原群","原介绍",true);
    var second=deterministic.create(bob,"新群","新介绍",true);
    assertEquals("abcde",first.id()); assertEquals("01234",second.id());
    assertEquals("原群",chat.groups("ABCDE").get(0).name());
    assertEquals("原介绍",chat.groups("abcde").get(0).description());
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM chat_members WHERE room_id='abcde'",Integer.class));
    assertThrows(ResponseStatusException.class,()->chat.messages(bob,first.id(),0));
    verify(deterministic,times(3)).nextGroupId();
  }
  @Test void repeatedCollisionsFailWithRetryableErrorAndNoPartialRoom() {
    db.update("INSERT INTO chat_rooms(id,name) VALUES ('fffff','已有群')");
    var deterministic=spy(chat); doReturn("fffff").when(deterministic).nextGroupId();
    var error=assertThrows(ResponseStatusException.class,()->deterministic.create(alice,"新群","",true));
    assertEquals(503,error.getStatusCode().value());
    verify(deterministic,times(32)).nextGroupId();
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM chat_rooms",Integer.class));
    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM chat_members",Integer.class));
  }
  @Test void historicalUuidGroupsAndDirectChatsRemainCompatible() {
    String old=UUID.randomUUID().toString();
    db.update("INSERT INTO chat_rooms(id,name,searchable) VALUES (?,'历史群',TRUE)",old);
    db.update("INSERT INTO chat_members(room_id,user_id) VALUES (?,1)",old);
    chat.send(alice,old,"历史消息");
    assertEquals(old,chat.groups(old.toUpperCase(java.util.Locale.ROOT)).get(0).id());
    chat.join(bob,old);
    assertEquals("历史消息",chat.messages(bob,old,0).get(0).content());
    assertEquals(36,chat.direct(alice,2).id().length());
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
