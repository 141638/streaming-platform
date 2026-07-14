-- Core notification entity — the system of record for all user-facing notifications.
-- Scoped to the `notification` schema (created in V1).
CREATE TABLE IF NOT EXISTS notification.notification (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_subject  VARCHAR(128) NOT NULL,
    category           VARCHAR(64) NOT NULL,
    action             VARCHAR(128) NOT NULL,
    title              VARCHAR(256) NOT NULL,
    body               TEXT NOT NULL,
    metadata           JSONB,
    is_read            BOOLEAN NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Cursor-based pagination for the notification bell list (GET /v1/notifications).
-- Queries always scope to a single recipient and walk backwards in time.
CREATE INDEX IF NOT EXISTS ix_notification_recipient_created
    ON notification.notification (recipient_subject, created_at DESC);

-- Unread count (GET /v1/notifications/unread-count) — partial index for efficiency.
CREATE INDEX IF NOT EXISTS ix_notification_recipient_unread
    ON notification.notification (recipient_subject)
    WHERE is_read = false;
