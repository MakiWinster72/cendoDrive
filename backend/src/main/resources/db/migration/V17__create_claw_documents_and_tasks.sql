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
