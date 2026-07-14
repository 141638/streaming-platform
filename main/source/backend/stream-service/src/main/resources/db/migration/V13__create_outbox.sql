-- Outbox table for at-least-once Kafka event delivery.
-- Events are written in the same DB transaction as the entity change
-- and published asynchronously by OutboxPoller.
--
-- See: docs/adr/stream/0009-outbox-pattern.md (to be written in Task A6)

CREATE TABLE IF NOT EXISTS stream.outbox (
    id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    stream_id UUID NOT NULL,
    payload JSONB NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_attempt_at TIMESTAMPTZ,
    published BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON stream.outbox (created_at, id)
    WHERE published = false;
