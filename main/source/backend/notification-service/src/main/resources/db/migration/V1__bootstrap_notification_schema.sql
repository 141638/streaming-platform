-- Notification bounded context — complements Kafka-fed workflows.
CREATE SCHEMA IF NOT EXISTS notification;

CREATE TABLE IF NOT EXISTS notification.notification_outbox (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type   VARCHAR(64) NOT NULL,
    aggregate_id     VARCHAR(128) NOT NULL,
    payload          JSONB NOT NULL,
    state            VARCHAR(32) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_notification_outbox_state_created
    ON notification.notification_outbox (state, created_at);

CREATE TABLE IF NOT EXISTS notification.channel_subscription (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscriber_subject  VARCHAR(128) NOT NULL,
    channel             VARCHAR(64) NOT NULL,
    topic_glob          VARCHAR(256),
    active              BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_channel_subscription_subject
    ON notification.channel_subscription (subscriber_subject);
