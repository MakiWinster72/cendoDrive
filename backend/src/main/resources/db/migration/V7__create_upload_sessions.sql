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
