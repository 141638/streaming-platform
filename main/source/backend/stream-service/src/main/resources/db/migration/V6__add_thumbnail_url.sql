-- Add a nullable thumbnail URL to stream sessions.
--
-- Phase 2.5 introduces the field and the UI display; the value is populated
-- later by the SRS auto-snapshot flow (Phase 2.9, depends on Phase 4.0 SRS).
-- Custom cover-art upload via object storage (MinIO) is deferred — see
-- docs/adr/stream/0005-stream-thumbnails.md.
--
-- The column is client-read-only in Phase 2.5: create/update requests do not
-- accept it. It stays NULL until a snapshot is generated, and the frontend
-- renders a placeholder image while NULL.

ALTER TABLE stream.stream_session
    ADD COLUMN IF NOT EXISTS thumbnail_url VARCHAR(512);

COMMENT ON COLUMN stream.stream_session.thumbnail_url IS
    'URL of the stream thumbnail; NULL until an SRS snapshot is generated (Phase 2.9). Custom upload via MinIO deferred (ADR-0005)';
