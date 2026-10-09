-- Existing BCrypt-only codes cannot be recovered; leave those values null.
ALTER TABLE share_links ADD COLUMN extraction_code VARCHAR(16) NULL;
