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
