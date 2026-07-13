-- Phase 3.5: store @mention usernames extracted from message bodies.
-- Uses PostgreSQL TEXT[] array so we can query "messages where user X was
-- mentioned" with ANY(): WHERE 'alice' = ANY(mentions)
-- DEFERRED (Phase 6 — Notification Service): this column is the data
-- foundation for mention notifications; the notification delivery
-- (SSE push / email digest) is deferred to Phase 6.

ALTER TABLE chat.chat_message
    ADD COLUMN IF NOT EXISTS mentions TEXT[] DEFAULT '{}';

CREATE INDEX IF NOT EXISTS idx_chat_message_mentions
    ON chat.chat_message USING GIN (mentions);

COMMENT ON COLUMN chat.chat_message.mentions IS
    'Usernames extracted via @mention parsing from the message body; empty array when none';
