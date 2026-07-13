ALTER TABLE stream.stream_session
    ADD COLUMN IF NOT EXISTS archived_url VARCHAR(512);
