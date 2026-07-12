-- Wave 1.1: denormalized usernames on chat_ban so the moderation roster can show
-- human-readable names (the banned user + the moderator) instead of raw JWT subs.
-- Follows the chat_message.author_username precedent (V3). Both nullable: rows
-- written before this migration — or when a name is unknown — fall back to a
-- truncated subject on the client.

ALTER TABLE chat.chat_ban
    ADD COLUMN IF NOT EXISTS banned_username    VARCHAR(128),
    ADD COLUMN IF NOT EXISTS banned_by_username VARCHAR(128);

COMMENT ON COLUMN chat.chat_ban.banned_username IS
    'Denormalized display name of the banned user (supplied by the ban request); NULL for pre-Wave-1.1 rows or when unknown';
COMMENT ON COLUMN chat.chat_ban.banned_by_username IS
    'Denormalized display name of the moderator (from JWT attr.username at ban time); NULL when unknown';
