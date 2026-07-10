-- V9: Social links on broadcaster_profile for the About tab
-- Stored as a JSONB array of {platform, url} objects.
ALTER TABLE stream.broadcaster_profile
    ADD COLUMN IF NOT EXISTS social_links JSONB;
