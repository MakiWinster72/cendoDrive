package com.cendodrive.chat;

import com.cendodrive.user.User;
import java.util.*;
import java.security.SecureRandom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class ChatService {
  private static final SecureRandom GROUP_RANDOM = new SecureRandom();
  private static final int GROUP_ID_ATTEMPTS = 32;
  private final JdbcTemplate db;

  public ChatService(JdbcTemplate db) {
    this.db = db;
  }

  public record Person(long id, String username, String nickname) {
  }

  public record Room(String id, String name, String description, boolean group, boolean searchable) {
  }

  public record Message(long id, long senderId, String senderName, String content, String createdAt,
      Attachment attachment) {
  }

  public record Attachment(String name, long size) {
  }

  public List<Person> search(String query) {
    String q = query.trim();
    if (q.isEmpty())
      return List.of();
    if (q.length() > 64)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索内容过长");
    String pattern = "%" + q.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    return db.query(
        "SELECT id, username, nickname FROM users WHERE is_active=TRUE AND (CAST(id AS CHAR)=? OR LOWER(username) LIKE ? ESCAPE '!') ORDER BY id LIMIT 30",
        (r, n) -> new Person(r.getLong(1), r.getString(2), r.getString(3)), q, pattern);
  }

  public List<Room> rooms(User user) {
    return db.query(
        "SELECT r.id, CASE WHEN r.direct_key IS NULL THEN r.name ELSE u.nickname END AS title, r.description, r.direct_key, r.searchable FROM chat_rooms r JOIN chat_members m ON m.room_id=r.id LEFT JOIN chat_members peer ON peer.room_id=r.id AND peer.user_id<>? AND r.direct_key IS NOT NULL LEFT JOIN users u ON u.id=peer.user_id WHERE m.user_id=? ORDER BY r.created_at DESC",
        (r, n) -> new Room(r.getString(1), r.getString(2), r.getString(3), r.getString(4) == null, r.getBoolean(5)),
        user.getId(), user.getId());
  }

  @Transactional
  public Room direct(User user, long target) {
    // Serializes creation for either direction of the same pair.
    long first = Math.min(user.getId(), target);
    db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", first);
    if (target == user.getId()
        || db.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND is_active=TRUE", Integer.class, target) == 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择其他有效用户");
    String key = first + ":" + Math.max(user.getId(), target);
    var existing = db.queryForList("SELECT id FROM chat_rooms WHERE direct_key=?", String.class, key);
    String id;
    if (!existing.isEmpty())
      id = existing.get(0);
    else {
      id = UUID.randomUUID().toString();
      db.update("INSERT INTO chat_rooms(id,name,direct_key) VALUES (?,'',?)", id, key);
      db.update("INSERT INTO chat_members(room_id,user_id) VALUES (?,?),(?,?)", id, user.getId(), id, target);
    }
    final String roomId = id;
    return rooms(user).stream().filter(r -> r.id().equals(roomId)).findFirst().orElseThrow();
  }

  @Transactional
  public Room create(User user, String name, String description, boolean searchable) {
    for (int attempt = 0; attempt < GROUP_ID_ATTEMPTS; attempt++) {
      String id = nextGroupId();
      try {
        // The primary key resolves concurrent collisions; never overwrite an existing
        // room.
        db.update("INSERT INTO chat_rooms(id,name,description,searchable) VALUES (?,?,?,?)", id, name.trim(),
            description.trim(), searchable);
      } catch (DuplicateKeyException collision) {
        continue;
      }
      db.update("INSERT INTO chat_members(room_id,user_id) VALUES (?,?)", id, user.getId());
      return new Room(id, name.trim(), description.trim(), true, searchable);
    }
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "群号分配繁忙，请稍后重试");
  }

  String nextGroupId() {
    return String.format(Locale.ROOT, "%05x", GROUP_RANDOM.nextInt(1 << 20));
  }

  public List<Room> groups(String query) {
    if (query.isBlank())
      return List.of();
    return db.query(
        "SELECT id,name,description,searchable FROM chat_rooms WHERE searchable=TRUE AND direct_key IS NULL AND id=?",
        (r, n) -> new Room(r.getString(1), r.getString(2), r.getString(3), true, r.getBoolean(4)),
        query.trim().toLowerCase(Locale.ROOT));
  }

  @Transactional
  public void join(User user, String id) {
    id = id.trim().toLowerCase(Locale.ROOT);
    var rows = db
        .queryForList("SELECT id FROM chat_rooms WHERE id=? AND searchable=TRUE AND direct_key IS NULL FOR UPDATE", id);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "群不存在或不允许搜索加入");
    if (db.queryForObject("SELECT COUNT(*) FROM chat_members WHERE room_id=? AND user_id=?", Integer.class, id,
        user.getId()) == 0)
      db.update("INSERT INTO chat_members(room_id,user_id) VALUES (?,?)", id, user.getId());
  }

  private void member(User user, String id) {
    if (db.queryForObject("SELECT COUNT(*) FROM chat_members WHERE room_id=? AND user_id=?", Integer.class, id,
        user.getId()) == 0)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该聊天");
  }

  public List<Message> messages(User user, String id, long after) {
    member(user, id);
    return db.query(
        "SELECT m.id,m.sender_id,u.nickname,m.content,m.created_at,m.file_id,m.file_name,m.file_size FROM chat_messages m JOIN users u ON u.id=m.sender_id WHERE m.room_id=? AND m.id>? ORDER BY m.id LIMIT 100",
        (r, n) -> new Message(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4),
            r.getTimestamp(5).toInstant().toString(),
            r.getObject(6) == null ? null : new Attachment(r.getString(7), r.getLong(8))),
        id, after);
  }

  @Transactional
  public void send(User user, String id, String content) {
    member(user, id);
    db.update("INSERT INTO chat_messages(room_id,sender_id,content) VALUES (?,?,?)", id, user.getId(), content.trim());
  }
}
