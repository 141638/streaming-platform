-- Add client-provided idempotency key to chat_message.
--
-- The clientId is the primary defense against duplicate message persistence.
-- WebSocket send frames carry a clientId (client-{ts}-{counter}); REST requests
-- forward the Idempotency-Key header. The column is nullable -- historical rows
-- (pre-V7) have no clientId, and system messages without a Kafka eventId also
-- skip idempotency with a null clientId.
--
-- The partial unique index (WHERE client_id IS NOT NULL) ensures that only
-- non-null values are checked for uniqueness -- NULLs coexist freely per SQL
-- standard, so historical rows and non-idempotent system messages are unaffected.
--
-- See ADR-0011: Message Idempotency via client_id Unique Constraint.

ALTER TABLE chat.chat_message
    ADD COLUMN client_id VARCHAR(64);

CREATE UNIQUE INDEX uq_chat_message_client_id
    ON chat.chat_message (client_id)
    WHERE client_id IS NOT NULL;
