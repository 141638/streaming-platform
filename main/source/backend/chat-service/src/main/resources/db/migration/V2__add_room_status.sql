-- Add room lifecycle columns: status + archived_at for active/archived tracking.
ALTER TABLE chat.chat_room
    ADD COLUMN IF NOT EXISTS status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_chat_room_external_key
    ON chat.chat_room(external_key);
