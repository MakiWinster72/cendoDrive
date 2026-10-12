CREATE TABLE chat_rooms (
 id VARCHAR(36) PRIMARY KEY,
 name VARCHAR(20) NOT NULL,
 description VARCHAR(50) NOT NULL DEFAULT '',
 direct_key VARCHAR(50) UNIQUE,
 searchable BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE chat_members (
 room_id VARCHAR(36) NOT NULL,
 user_id BIGINT NOT NULL,
 PRIMARY KEY(room_id, user_id),
 FOREIGN KEY(room_id) REFERENCES chat_rooms(id),
 FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE chat_messages (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 room_id VARCHAR(36) NOT NULL,
 sender_id BIGINT NOT NULL,
 content VARCHAR(2000) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(room_id) REFERENCES chat_rooms(id),
 FOREIGN KEY(sender_id) REFERENCES users(id)
);
CREATE INDEX idx_chat_messages_room ON chat_messages(room_id, id);
CREATE INDEX idx_chat_members_user ON chat_members(user_id);
