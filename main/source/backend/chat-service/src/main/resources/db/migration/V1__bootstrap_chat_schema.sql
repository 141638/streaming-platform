-- Chat bounded context — Postgres durable store; Redis is hot-path cache elsewhere.
CREATE SCHEMA IF NOT EXISTS chat;

CREATE TABLE IF NOT EXISTS chat.chat_room (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_key  VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_chat_room_external_key UNIQUE (external_key)
);

CREATE TABLE IF NOT EXISTS chat.chat_message (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id         UUID NOT NULL,
    author_subject  VARCHAR(128) NOT NULL,
    body            TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_chat_message_room
        FOREIGN KEY (room_id) REFERENCES chat.chat_room (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_chat_message_room ON chat.chat_message (room_id);
CREATE INDEX IF NOT EXISTS ix_chat_message_created_at ON chat.chat_message (created_at);
