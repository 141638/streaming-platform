-- Phase 3.3 schema: message types, identity enrichment, user banning, superchat foundation.
-- These columns and tables are designed so the Kafka consumer + write path can
-- populate them starting from the same migration, with no ALTER needed later.

-- 1. chat_room: broadcaster ownership + future indexing.
ALTER TABLE chat.chat_room
    ADD COLUMN IF NOT EXISTS broadcaster_subject VARCHAR(128);

COMMENT ON COLUMN chat.chat_room.broadcaster_subject IS
    'JWT sub of the room owner (streamer). Set from STREAM_CREATED event in Phase 3.3.';

-- 2. chat_message: identity enrichment (denormalized from JWT at write time).
-- Message type uses VARCHAR (not a custom PG enum) because R2DBC Postgres cannot bind
-- Java String to a custom enum parameter — it sends varchar, and PostgreSQL rejects
-- the implicit cast. A CHECK constraint provides equivalent safety without the driver gap.
ALTER TABLE chat.chat_message
    ADD COLUMN IF NOT EXISTS message_type     VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN IF NOT EXISTS author_username  VARCHAR(128),       -- from JWT attr.username
    ADD COLUMN IF NOT EXISTS author_avatar_url TEXT,              -- future: user-uploaded avatar
    -- Superchat gift columns (NULL for NORMAL/SYSTEM).
    ADD COLUMN IF NOT EXISTS gift_amount      DECIMAL(10, 2),
    ADD COLUMN IF NOT EXISTS gift_currency    VARCHAR(3),         -- ISO 4217, e.g. 'USD'
    ADD CONSTRAINT ck_chat_message_type
        CHECK (message_type IN ('NORMAL', 'SUPER_CHAT', 'SYSTEM'));

COMMENT ON COLUMN chat.chat_message.message_type IS
    'NORMAL = user chat; SUPER_CHAT = paid highlighted message; SYSTEM = stream/bot events';
COMMENT ON COLUMN chat.chat_message.author_username IS
    'Denormalized from JWT attr.username at write time; NULL for messages written before 3.3';
COMMENT ON COLUMN chat.chat_message.gift_amount IS
    'Only populated for SUPER_CHAT messages; NULL otherwise';

-- 3. chat_ban: room-level user bans (enforced in sendMessage).
CREATE TABLE IF NOT EXISTS chat.chat_ban (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id           UUID NOT NULL,
    banned_subject    VARCHAR(128) NOT NULL,
    banned_by_subject VARCHAR(128) NOT NULL,
    reason            TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ,            -- NULL = permanent
    CONSTRAINT fk_chat_ban_room
        FOREIGN KEY (room_id) REFERENCES chat.chat_room (id) ON DELETE CASCADE,
    CONSTRAINT uq_chat_ban_room_subject UNIQUE (room_id, banned_subject)
);

CREATE INDEX IF NOT EXISTS ix_chat_ban_room      ON chat.chat_ban (room_id);
CREATE INDEX IF NOT EXISTS ix_chat_ban_subject   ON chat.chat_ban (banned_subject);

COMMENT ON TABLE chat.chat_ban IS
    'Per-room user bans. Enforced in ChatService.sendMessage() before write.';
