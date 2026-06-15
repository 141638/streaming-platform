-- Stream bounded context — broadcaster_subject is opaque (no FK to auth).
CREATE SCHEMA IF NOT EXISTS stream;

CREATE TABLE IF NOT EXISTS stream.stream_session (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stream_key_hash       VARCHAR(128) NOT NULL,
    broadcaster_subject   VARCHAR(128),
    status                VARCHAR(32) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at              TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_stream_session_status ON stream.stream_session (status);
CREATE INDEX IF NOT EXISTS ix_stream_session_created_at ON stream.stream_session (created_at);
