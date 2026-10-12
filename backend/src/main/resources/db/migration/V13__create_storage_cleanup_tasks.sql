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
