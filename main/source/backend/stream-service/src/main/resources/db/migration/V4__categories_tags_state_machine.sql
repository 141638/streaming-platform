-- Add categories lookup table, tags, state machine columns, and one-live-stream constraint.

-- ── Categories lookup table ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stream.stream_category (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(64) NOT NULL UNIQUE,
    slug          VARCHAR(64) NOT NULL UNIQUE,
    display_order INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE stream.stream_category IS 'Managed category vocabulary for stream discovery';

-- Seed default categories.
INSERT INTO stream.stream_category (name, slug, display_order) VALUES
    ('Gaming', 'gaming', 1),
    ('Music', 'music', 2),
    ('Talk Show', 'talk-show', 3),
    ('Just Chatting', 'just-chatting', 4),
    ('Art', 'art', 5),
    ('Technology', 'technology', 6),
    ('Sports', 'sports', 7),
    ('Education', 'education', 8)
ON CONFLICT (slug) DO NOTHING;

-- ── New columns on stream_session ─────────────────────────────────────────
ALTER TABLE stream.stream_session
    ADD COLUMN IF NOT EXISTS category_id  UUID REFERENCES stream.stream_category(id),
    ADD COLUMN IF NOT EXISTS tags         TEXT[],
    ADD COLUMN IF NOT EXISTS started_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS scheduled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS version      BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN stream.stream_session.category_id IS 'FK to stream_category; nullable for backward compat';
COMMENT ON COLUMN stream.stream_session.tags IS 'Free-form tags for cross-category filtering';
COMMENT ON COLUMN stream.stream_session.started_at IS 'Timestamp when the stream went live';
COMMENT ON COLUMN stream.stream_session.scheduled_at IS 'Planned start time for scheduled streams';
COMMENT ON COLUMN stream.stream_session.version IS 'Optimistic locking counter for R2DBC @Version';

-- ── Indexes ───────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS ix_stream_session_category_id
    ON stream.stream_session(category_id);

CREATE INDEX IF NOT EXISTS ix_stream_session_tags
    ON stream.stream_session USING GIN(tags);

-- ── Business rule: one live stream per broadcaster ────────────────────────
CREATE UNIQUE INDEX IF NOT EXISTS uq_stream_session_one_live
    ON stream.stream_session (broadcaster_subject)
    WHERE status = 'live';
