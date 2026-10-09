ALTER TABLE transfer_records ADD COLUMN file_id BIGINT NULL;

UPDATE transfer_records tr
SET file_id = (
    SELECT MIN(df.id)
    FROM drive_files df
    WHERE df.owner_id = tr.owner_id
      AND df.kind = 'file'
      AND df.deleted_at IS NULL
      AND df.name = tr.name
      AND df.size_bytes = tr.size_bytes
      AND df.created_at <= tr.created_at
)
WHERE tr.status = 'success'
  AND tr.direction IN ('upload', 'transfer')
  AND (
      SELECT COUNT(*)
      FROM drive_files df
      WHERE df.owner_id = tr.owner_id
        AND df.kind = 'file'
        AND df.deleted_at IS NULL
        AND df.name = tr.name
        AND df.size_bytes = tr.size_bytes
        AND df.created_at <= tr.created_at
  ) = 1;
