ALTER TABLE drive_files ADD COLUMN favorite BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE drive_files ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_drive_files_owner_flags ON drive_files(owner_id, hidden, favorite);

-- Trash and hidden content still consume the owner's quota.
UPDATE users SET storage_used = (
  SELECT COALESCE(SUM(size_bytes), 0) FROM drive_files
  WHERE drive_files.owner_id = users.id AND drive_files.kind = 'file'
);
