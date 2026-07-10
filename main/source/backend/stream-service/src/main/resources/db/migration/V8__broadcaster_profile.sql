-- V8: Broadcaster profile for channel About tab
-- Stores channel-level metadata (bio) keyed by the broadcaster's public username.
-- This is separate from stream_session so bio persists even with no streams.
CREATE TABLE IF NOT EXISTS stream.broadcaster_profile (
    username    VARCHAR(128) PRIMARY KEY,
    bio         TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
