-- V1: Bootstrap insight schema for engagement analytics.
-- All columns use Tier 1 types only (UUID, VARCHAR, BIGINT, TIMESTAMPTZ).
-- No JSONB, arrays, or custom ENUMs — no R2DBC converters needed.

CREATE SCHEMA IF NOT EXISTS insight;

CREATE TABLE IF NOT EXISTS insight.engagement_event (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id       VARCHAR(64) NOT NULL,
    event_type     VARCHAR(32) NOT NULL,
    stream_id      UUID NOT NULL,
    actor_subject  VARCHAR(128) NOT NULL,
    target_type    VARCHAR(32) NOT NULL DEFAULT 'CHANNEL',
    target_id      VARCHAR(128) NOT NULL,
    category       VARCHAR(128),
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index: per-target view lookups ordered by recency (trending channels/categories)
CREATE INDEX IF NOT EXISTS ix_engagement_target_time
    ON insight.engagement_event (target_type, target_id, occurred_at DESC);

-- Index: per-actor view history (personalization, Phase B)
CREATE INDEX IF NOT EXISTS ix_engagement_actor_time
    ON insight.engagement_event (actor_subject, occurred_at DESC);

-- Index: per-stream analytics (stream dashboard)
CREATE INDEX IF NOT EXISTS ix_engagement_stream_time
    ON insight.engagement_event (stream_id, occurred_at DESC);

-- Unique index: dedup guard — at-least-once Kafka delivery + consumer Redis SETNX
CREATE UNIQUE INDEX IF NOT EXISTS ix_engagement_event_id
    ON insight.engagement_event (event_id);
