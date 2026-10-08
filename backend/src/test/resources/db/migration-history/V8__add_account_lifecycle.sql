ALTER TABLE users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN deleted_at DATETIME(6) NULL;
CREATE INDEX idx_users_deletion ON users (deleted_at);
CREATE TABLE user_avatars (
  user_id BIGINT NOT NULL PRIMARY KEY,
  image_data LONGBLOB NOT NULL,
  CONSTRAINT fk_user_avatars_owner FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
