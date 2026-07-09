-- Fix: replace the custom PG enum chat.message_type with VARCHAR + CHECK constraint.
-- R2DBC Postgres cannot bind Java String to a custom enum parameter — it sends varchar,
-- and PostgreSQL rejects the implicit cast. A CHECK provides the same safety.
--
-- For fresh installs, V3 already uses VARCHAR directly so this migration is a no-op
-- (the column is already VARCHAR, the type doesn't exist, IF NOT EXISTS handles it).

-- 1. Convert the column type (explicit cast preserves existing data).
ALTER TABLE chat.chat_message
    ALTER COLUMN message_type TYPE VARCHAR(16)
        USING message_type::text;

-- 2. Reset the default to a plain varchar literal.
ALTER TABLE chat.chat_message
    ALTER COLUMN message_type SET DEFAULT 'NORMAL';

-- 3. Drop the custom enum type (safe only because nothing else references it).
DROP TYPE IF EXISTS chat.message_type;

-- 4. Add a CHECK constraint to enforce valid values.
ALTER TABLE chat.chat_message
    DROP CONSTRAINT IF EXISTS ck_chat_message_type;

ALTER TABLE chat.chat_message
    ADD CONSTRAINT ck_chat_message_type
        CHECK (message_type IN ('NORMAL', 'SUPER_CHAT', 'SYSTEM'));
