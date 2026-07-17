CREATE TABLE IF NOT EXISTS stream.stream_viewer_snapshot (
    id              BIGSERIAL PRIMARY KEY,
    stream_id       UUID NOT NULL,
    minute_bucket   TIMESTAMPTZ NOT NULL,
    viewer_count    BIGINT NOT NULL DEFAULT 0,
    harvested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_stream_minute UNIQUE (stream_id, minute_bucket)
);

CREATE INDEX IF NOT EXISTS ix_snapshot_stream_time
    ON stream.stream_viewer_snapshot (stream_id, minute_bucket DESC);
