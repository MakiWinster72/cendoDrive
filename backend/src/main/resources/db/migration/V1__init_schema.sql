-- Fresh databases only; replaces historical V1-V17 migrations.

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    vip_level VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    storage_used BIGINT NOT NULL DEFAULT 0,
    storage_limit BIGINT NOT NULL DEFAULT 1073741824,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE drive_files (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    name VARCHAR(255) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    storage_key VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_drive_files_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_drive_files_parent FOREIGN KEY (parent_id) REFERENCES drive_files(id) ON DELETE CASCADE,
    CONSTRAINT uk_drive_files_sibling_name UNIQUE (owner_id, parent_id, name),
    INDEX idx_drive_files_owner_parent (owner_id, parent_id)
);

ALTER TABLE drive_files ADD COLUMN storage_backend VARCHAR(32) NOT NULL DEFAULT 'local';

ALTER TABLE drive_files
    ADD COLUMN deleted_at DATETIME(6) NULL;

CREATE INDEX idx_drive_files_owner_deleted ON drive_files (owner_id, deleted_at);

CREATE TABLE share_links (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    token VARCHAR(32) NOT NULL UNIQUE,
    file_name VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_share_links_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_share_links_owner_created (owner_id, created_at)
);

ALTER TABLE drive_files ADD COLUMN favorite BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE drive_files ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_drive_files_owner_flags ON drive_files(owner_id, hidden, favorite);

-- Trash and hidden content still consume the owner's quota.
UPDATE users SET storage_used = (
  SELECT COALESCE(SUM(size_bytes), 0) FROM drive_files
  WHERE drive_files.owner_id = users.id AND drive_files.kind = 'file'
);

CREATE TABLE upload_sessions (
  id VARCHAR(32) NOT NULL PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_size BIGINT NOT NULL,
  file_hash VARCHAR(32) NOT NULL,
  chunk_size INT NOT NULL,
  total_chunks INT NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  result_file_id BIGINT NULL,
  CONSTRAINT fk_upload_sessions_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
  INDEX idx_upload_sessions_owner_hash(owner_id, file_hash),
  INDEX idx_upload_sessions_expiry(expires_at)
);

ALTER TABLE users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN deleted_at DATETIME(6) NULL;
CREATE INDEX idx_users_deletion ON users (deleted_at);
CREATE TABLE user_avatars (
  user_id BIGINT NOT NULL PRIMARY KEY,
  image_data LONGBLOB NOT NULL,
  CONSTRAINT fk_user_avatars_owner FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

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

ALTER TABLE chat_messages ADD COLUMN file_id BIGINT NULL;
ALTER TABLE chat_messages ADD COLUMN file_name VARCHAR(255) NULL;
ALTER TABLE chat_messages ADD COLUMN file_size BIGINT NULL;

ALTER TABLE share_links ADD COLUMN extraction_code_hash VARCHAR(100) NULL;

ALTER TABLE drive_files
    ADD COLUMN index_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE ai_index_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(128) NOT NULL,
    file_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    operation VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL,
    file_name VARCHAR(255) NULL,
    storage_backend VARCHAR(32) NULL,
    storage_key VARCHAR(512) NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_ai_index_tasks_event UNIQUE (event_id),
    CONSTRAINT uk_ai_index_tasks_version UNIQUE (file_id, revision, operation),
    INDEX idx_ai_index_tasks_poll (status, next_attempt_at)
);

CREATE TABLE storage_cleanup_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    storage_backend VARCHAR(32) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_storage_cleanup_target UNIQUE (storage_backend, storage_key),
    INDEX idx_storage_cleanup_poll (status, next_attempt_at)
);

CREATE TABLE transfer_records (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    client_id VARCHAR(512) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    name VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    progress INT NOT NULL,
    error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_transfer_owner_client UNIQUE (owner_id, client_id),
    CONSTRAINT fk_transfer_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    INDEX idx_transfer_owner_created (owner_id, created_at)
);

ALTER TABLE transfer_records ADD COLUMN file_id BIGINT NULL;

UPDATE transfer_records tr
SET file_id = (
    SELECT MIN(df.id)
    FROM drive_files df
    WHERE df.owner_id = tr.owner_id
      AND df.kind = 'file'
      AND df.deleted_at IS NULL
      AND df.name = tr.name
      AND df.size_bytes = tr.size_bytes
      AND df.created_at <= tr.created_at
)
WHERE tr.status = 'success'
  AND tr.direction IN ('upload', 'transfer')
  AND (
      SELECT COUNT(*)
      FROM drive_files df
      WHERE df.owner_id = tr.owner_id
        AND df.kind = 'file'
        AND df.deleted_at IS NULL
        AND df.name = tr.name
        AND df.size_bytes = tr.size_bytes
        AND df.created_at <= tr.created_at
  ) = 1;

-- Existing BCrypt-only codes cannot be recovered; leave those values null.
ALTER TABLE share_links ADD COLUMN extraction_code VARCHAR(16) NULL;

CREATE TABLE claw_documents (
    file_id BIGINT NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    collection_name VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    chunks INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_claw_document_file FOREIGN KEY (file_id) REFERENCES drive_files(id) ON DELETE CASCADE,
    INDEX idx_claw_document_owner (owner_id, status)
);
-- No user/file FK: deletion tombstones must outlive metadata and account cleanup.
CREATE TABLE claw_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    collection_name VARCHAR(80) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_token VARCHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_claw_task UNIQUE (file_id, generation, collection_name, operation),
    INDEX idx_claw_task_poll (status, next_attempt_at)
);

