-- V6: Fan-out job queue table.
--
-- Each row is one fan-out "job" — created when a followed broadcaster
-- starts a stream, picked up asynchronously by FanOutPoller.
--
-- States: PENDING → PROCESSING → COMPLETED (ACK)
--         PENDING → PROCESSING → FAILED → retry → PROCESSING (NACK + retry)
--         PENDING → PROCESSING → FAILED → DEAD (DLQ, after maxRetries)
--
-- Idempotency: UNIQUE (event_id, job_type) — replay the same Kafka event → skip.
-- Visibility timeout: claimed_at + claimed_by — detect stuck jobs (future).

CREATE TABLE IF NOT EXISTS notification.fan_out_job (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id              VARCHAR(128) NOT NULL,
    job_type              VARCHAR(64)  NOT NULL,
    broadcaster_subject   VARCHAR(128) NOT NULL,
    target_type           VARCHAR(64)  NOT NULL,
    target_id             VARCHAR(128) NOT NULL,
    state                 VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    total_subscribers     INTEGER,
    processed_subscribers INTEGER      NOT NULL DEFAULT 0,
    retry_count           INTEGER      NOT NULL DEFAULT 0,
    claimed_at            TIMESTAMPTZ,
    claimed_by            VARCHAR(128),
    last_error            TEXT,
    scheduled_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Poller's primary scan: find PENDING jobs ready for processing.
CREATE INDEX IF NOT EXISTS ix_fan_out_job_poll
    ON notification.fan_out_job (state, scheduled_at, created_at);

-- Idempotency guard: same Kafka event + job type → skip duplicate enqueue.
CREATE UNIQUE INDEX IF NOT EXISTS uq_fan_out_job_dedup
    ON notification.fan_out_job (event_id, job_type);

-- Stuck-job detection (future): find jobs in PROCESSING state past their
-- visibility timeout (e.g., claimed_at < now() - 5 minutes).
CREATE INDEX IF NOT EXISTS ix_fan_out_job_claimed
    ON notification.fan_out_job (state, claimed_at)
    WHERE state = 'PROCESSING';
