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
