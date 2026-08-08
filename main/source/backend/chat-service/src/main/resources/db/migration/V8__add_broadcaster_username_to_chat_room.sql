-- V8: Add broadcaster_username to chat_room for moderation event display.
-- The username is denormalized from the STREAM_CREATED event at room creation time.
-- Pre-existing rooms will have NULL — the frontend falls back to DiceBear avatars
-- generated from broadcaster_subject.
ALTER TABLE chat.chat_room
    ADD COLUMN broadcaster_username TEXT;
