CREATE TABLE IF NOT EXISTS stream.stream_watch_history (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_subject            VARCHAR(128) NOT NULL,
    stream_id               UUID NOT NULL REFERENCES stream.stream_session(id),
    watched_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    watch_duration_seconds  BIGINT DEFAULT 0,
    CONSTRAINT uq_watch_history_user_stream UNIQUE (user_subject, stream_id)
);

CREATE INDEX IF NOT EXISTS ix_watch_history_user
    ON stream.stream_watch_history (user_subject, watched_at DESC);
