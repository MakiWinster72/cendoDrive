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
