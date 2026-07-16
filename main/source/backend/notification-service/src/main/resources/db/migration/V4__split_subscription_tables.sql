-- Split the V1 `channel_subscription` table into two tables with distinct concerns:
--   notification_preference — *how* to deliver (channel, topic_glob, active)
--   subscription            — *what* to notify about (polymorphic target)
--
-- See ADR-0001 for the rationale: a user changing their delivery channel
-- should be 1 row update regardless of how many streamers they follow.
-- The original table conflated delivery preference with follow target.

-- 1. Rename the existing table (columns already match notification_preference)
ALTER TABLE notification.channel_subscription
    RENAME TO notification_preference;

-- 2. Rename the index to match the new table name
ALTER INDEX IF EXISTS ix_channel_subscription_subject
    RENAME TO ix_notification_preference_subject;

-- 3. Add unique constraint — one preference row per (user, channel)
ALTER TABLE notification.notification_preference
    ADD CONSTRAINT uq_notification_preference
    UNIQUE (subscriber_subject, channel);

-- 4. Add updated_at for tracking preference mutations
ALTER TABLE notification.notification_preference
    ADD COLUMN updated_at TIMESTAMPTZ;

-- 5. New table: what the user wants notifications about
CREATE TABLE notification.subscription (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscriber_subject  VARCHAR(128) NOT NULL,
    target_id           VARCHAR(128) NOT NULL,
    target_type         VARCHAR(64) NOT NULL,
    active              BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_subscription
        UNIQUE (subscriber_subject, target_type, target_id)
);

-- 6. Partial index for fan-out lookups — only active subscriptions
CREATE INDEX ix_subscription_target
    ON notification.subscription (target_type, target_id)
    WHERE active = true;
