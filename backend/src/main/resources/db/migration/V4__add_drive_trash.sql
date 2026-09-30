ALTER TABLE drive_files
    ADD COLUMN deleted_at DATETIME(6) NULL;

CREATE INDEX idx_drive_files_owner_deleted ON drive_files (owner_id, deleted_at);
