-- The V1 `notification_outbox` schema was designed for a future outbox poller
-- but only included the bare-minimum columns (no retry_count, no last_attempt_at).
-- The V5 migration completes the schema to match the OutboxEntry entity.
--
-- 1. Change payload from JSONB to TEXT (same pattern as V3 for notification.metadata)
--    to avoid R2DBC wire-type mismatches at persist time.
ALTER TABLE notification.notification_outbox
    ALTER COLUMN payload TYPE TEXT;

-- 2. Add retry tracking columns required by OutboxPoller (mirrors stream-service pattern)
ALTER TABLE notification.notification_outbox
    ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE notification.notification_outbox
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMPTZ;
