package com.cendodrive.chat;

import com.cendodrive.drive.DriveService;
import com.cendodrive.drive.DriveDtos.FileResponse;
import com.cendodrive.user.User;
import com.cendodrive.user.UserRepository;
import java.io.IOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatFileService {
  private final JdbcTemplate db;
  private final DriveService drive;
  private final UserRepository users;
  public ChatFileService(JdbcTemplate db, DriveService drive, UserRepository users) {
    this.db=db; this.drive=drive; this.users=users;
  }
  private void member(User user, String room) {
    if (db.queryForObject("SELECT COUNT(*) FROM chat_members WHERE room_id=? AND user_id=?", Integer.class, room, user.getId())==0)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权访问该聊天");
  }
  @Transactional
  public void send(User user, String room, long fileId) {
    member(user,room);
    var file=drive.shareableFile(user,fileId);
    db.update("INSERT INTO chat_messages(room_id,sender_id,content,file_id,file_name,file_size) VALUES (?,?,'',?,?,?)",
        room,user.getId(),file.getId(),file.getName(),file.getSize());
  }
  @Transactional(rollbackFor=IOException.class)
  public FileResponse save(User user, String room, long messageId) throws IOException {
    member(user,room);
    var rows=db.queryForList("SELECT sender_id,file_id FROM chat_messages WHERE room_id=? AND id=? AND file_id IS NOT NULL",room,messageId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文件消息不存在");
    var row=rows.get(0);
    long ownerId=((Number)row.get("sender_id")).longValue();
    User owner=users.findById(ownerId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"文件发送者不存在"));
    return drive.saveSharedFile(user,owner,((Number)row.get("file_id")).longValue(),null);
  }
}
