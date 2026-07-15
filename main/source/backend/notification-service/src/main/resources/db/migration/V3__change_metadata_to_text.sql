-- The Notification entity stores metadata as a plain String to avoid JSONB converter
-- complexity (see docs/R2DBC-JSONB-CONVERTER-PATTERN.md). The V2 migration incorrectly
-- defined this column as JSONB, which causes R2DBC wire-type mismatches at persist time:
--   ERROR: column "metadata" is of type jsonb but expression is of type character varying
-- Switching to TEXT matches the entity design intent and eliminates the converter requirement.
ALTER TABLE notification.notification
    ALTER COLUMN metadata TYPE TEXT;
