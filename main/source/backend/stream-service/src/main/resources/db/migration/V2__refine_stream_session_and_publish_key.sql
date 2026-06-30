-- Refine stream_session with metadata columns and add stream_publish_key for key rotation.

ALTER TABLE stream.stream_session
    ADD COLUMN IF NOT EXISTS title        VARCHAR(256) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS description  VARCHAR(2048),
    ADD COLUMN IF NOT EXISTS category     VARCHAR(64),
    ADD COLUMN IF NOT EXISTS max_viewers  INTEGER,
    ADD COLUMN IF NOT EXISTS updated_at   TIMESTAMPTZ NOT NULL DEFAULT now();

COMMENT ON COLUMN stream.stream_session.title IS 'User-facing stream title';
COMMENT ON COLUMN stream.stream_session.description IS 'Optional stream description';
COMMENT ON COLUMN stream.stream_session.category IS 'Stream category/tag for discovery';
COMMENT ON COLUMN stream.stream_session.max_viewers IS 'Optional viewer cap (null = unlimited)';
COMMENT ON COLUMN stream.stream_session.updated_at IS 'Last mutation timestamp';

-- Separate publish key table to support rotation without touching the session row.
CREATE TABLE IF NOT EXISTS stream.stream_publish_key (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID NOT NULL REFERENCES stream.stream_session(id) ON DELETE CASCADE,
    key_hash    VARCHAR(128) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_stream_publish_key_session
    ON stream.stream_publish_key(session_id);

COMMENT ON TABLE stream.stream_publish_key IS 'Publish credentials per session; supports rotation via revoke + re-issue';
