-- Drop the not null constraint of stream_session's stream_key_hash, used for draft stream session

ALTER TABLE stream.stream_session ALTER COLUMN stream_key_hash DROP NOT NULL;
