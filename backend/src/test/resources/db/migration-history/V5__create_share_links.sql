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
