-- Denormalize broadcaster identity into stream_session for channel-page reads.
--
-- Values are populated from the JWT attr claim at stream creation (client-read-only,
-- never from the request body). Pre-existing rows and tokens issued before the
-- username claim was added (auth A1) will have NULL — the channel read endpoint
-- handles that gracefully.
--
-- See docs/plans/channel-page-phase-a-b-blueprint.md § A3.
-- See docs/plans/channel-page-scope-review.md § 0.1.

ALTER TABLE stream.stream_session
    ADD COLUMN IF NOT EXISTS broadcaster_username VARCHAR(128),
    ADD COLUMN IF NOT EXISTS broadcaster_verified BOOLEAN;

COMMENT ON COLUMN stream.stream_session.broadcaster_username IS
    'Public channel handle denormalized from JWT attr at stream creation; NULL for pre-existing rows or tokens issued before the username claim was added';

COMMENT ON COLUMN stream.stream_session.broadcaster_verified IS
    'Verified streamer status denormalized from JWT attr; NULL for pre-existing rows';
