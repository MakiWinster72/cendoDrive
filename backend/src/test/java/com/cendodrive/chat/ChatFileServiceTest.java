package com.cendodrive.chat;

import com.cendodrive.drive.*;
import com.cendodrive.user.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatFileServiceTest {
  JdbcTemplate db; ChatService chat; ChatFileService files;
  DriveService drive=mock(DriveService.class); UserRepository users=mock(UserRepository.class);
  User alice=mock(User.class),bob=mock(User.class),other=mock(User.class); String room;
  @BeforeEach void setup() {
    String url="jdbc:h2:mem:chatfiles-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url,"sa","").load().migrate();
    db=new JdbcTemplate(new DriverManagerDataSource(url,"sa","")); chat=new ChatService(db); files=new ChatFileService(db,drive,users);
    for(int i=1;i<=3;i++) db.update("INSERT INTO users(id,username,password_hash,nickname,created_at,updated_at) VALUES (?,?,'hash',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",i,"u"+i,"用户"+i);
    when(alice.getId()).thenReturn(1L); when(bob.getId()).thenReturn(2L); when(other.getId()).thenReturn(3L);
    room=chat.direct(alice,2).id();
  }
  @Test void fileMetadataAndSaveResolveOnlyFromAuthorizedMessage() throws Exception {
    var source=DriveFile.uploaded(1L,null,"附件.txt",12,"blob"); ReflectionTestUtils.setField(source,"id",42L);
    when(drive.shareableFile(alice,42L)).thenReturn(source);
    when(users.findById(1L)).thenReturn(Optional.of(alice));
    files.send(alice,room,42L);
    var message=chat.messages(bob,room,0).get(0);
    assertEquals("附件.txt",message.attachment().name()); assertEquals(12,message.attachment().size());
    files.save(bob,room,message.id()); verify(drive).saveSharedFile(bob,alice,42L,null);
    assertThrows(ResponseStatusException.class,()->files.save(other,room,message.id()));
    assertThrows(ResponseStatusException.class,()->files.save(bob,"foreign-room",message.id()));
    assertThrows(ResponseStatusException.class,()->files.save(bob,room,999));
    verify(drive,times(1)).saveSharedFile(any(),any(),anyLong(),any());
  }
  @Test void cannotSendForeignFilesOrSendWithoutMembership() {
    assertThrows(ResponseStatusException.class,()->files.send(other,room,42L)); verifyNoInteractions(drive);
    when(drive.shareableFile(alice,42L)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    assertThrows(ResponseStatusException.class,()->files.send(alice,room,42L));
    assertTrue(chat.messages(bob,room,0).isEmpty());
  }
  @Test void textMessageCannotBeSavedAsFile() {
    chat.send(alice,room,"hello");
    assertThrows(ResponseStatusException.class,()->files.save(bob,room,chat.messages(bob,room,0).get(0).id()));
    verifyNoInteractions(drive,users);
  }
}
