ALTER TABLE chat_messages ADD COLUMN file_id BIGINT NULL;
ALTER TABLE chat_messages ADD COLUMN file_name VARCHAR(255) NULL;
ALTER TABLE chat_messages ADD COLUMN file_size BIGINT NULL;
