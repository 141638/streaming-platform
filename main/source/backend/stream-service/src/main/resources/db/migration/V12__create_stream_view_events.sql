-- Persisted view events for analytics (ADR-0008 Phase 1).
-- One row per unique (stream_id, user_id) pair.
-- Populated by ViewCountFlushService from Redis hashes.
--
-- Future: add watch_duration_seconds column when client-side heartbeat
-- pings are implemented for actual watched-time tracking.

CREATE TABLE IF NOT EXISTS stream.stream_view_event (
    id BIGSERIAL PRIMARY KEY,
    stream_id UUID NOT NULL REFERENCES stream.stream_session(id),
    user_id VARCHAR(255) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (stream_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_view_event_stream_id
    ON stream.stream_view_event (stream_id);
