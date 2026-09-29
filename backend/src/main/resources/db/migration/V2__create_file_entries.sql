CREATE TABLE file_entries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    parent_id BIGINT NOT NULL DEFAULT 0,
    name VARCHAR(255) NOT NULL,
    name_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    entry_type VARCHAR(16) NOT NULL,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    storage_provider VARCHAR(32),
    storage_key VARCHAR(512),
    content_type VARCHAR(255),
    content_hash VARCHAR(128),
    ingest_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    registration_fingerprint VARCHAR(64),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_file_entries_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_file_entries_name UNIQUE (user_id, parent_id, name_key),
    CONSTRAINT uk_file_entries_ingest UNIQUE (user_id, ingest_key),
    CONSTRAINT ck_file_entries_parent CHECK (parent_id >= 0),
    CONSTRAINT ck_file_entries_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_file_entries_type CHECK (
        (entry_type = 'FOLDER' AND size_bytes = 0 AND storage_provider IS NULL AND storage_key IS NULL
            AND ingest_key IS NULL AND registration_fingerprint IS NULL)
        OR (entry_type = 'FILE' AND storage_provider IS NOT NULL AND storage_key IS NOT NULL
            AND ingest_key IS NOT NULL AND registration_fingerprint IS NOT NULL)
    ),
    INDEX ix_file_entries_directory (user_id, parent_id, entry_type, id)
) DEFAULT CHARACTER SET utf8mb4;
