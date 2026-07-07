-- Reverse V3: make stream_key_hash NOT NULL again.
-- All DRAFT and LIVE streams always have a publish key (ADR-0004 §7).
-- SCHEDULED streams are terminal and have no key; they are created
-- via a separate path that does not touch stream_key_hash (the column
-- remains NULL for SCHEDULED rows, which is fine — V3's DROP NOT NULL
-- was only needed for the now-removed DRAFT→SCHEDULED transition).

-- Backfill any legacy NULLs with an empty string to satisfy the constraint.
UPDATE stream.stream_session
   SET stream_key_hash = ''
 WHERE stream_key_hash IS NULL;

ALTER TABLE stream.stream_session ALTER COLUMN stream_key_hash SET NOT NULL;

-- Store the plain SRS stream name alongside its hash so the service can
-- reconstruct RTMP/HLS URLs for GET /publish-key without reversing SHA-256.
ALTER TABLE stream.stream_session
    ADD COLUMN IF NOT EXISTS srs_name VARCHAR(36);

COMMENT ON COLUMN stream.stream_session.srs_name IS
    'Plain SRS stream name (UUID) for URL construction; NULL when no key has been issued';
