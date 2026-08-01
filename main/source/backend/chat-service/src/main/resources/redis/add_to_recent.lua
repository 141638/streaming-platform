-- Atomic ZADD + trim + EXPIRE for the chat message hot-cache.
--
-- Replaces the 3-round-trip Java pipeline in
-- RedisMessageCache.addToRecent() with a single EVALSHA call.
-- Eliminates the race window where two concurrent writers
-- interleave ZADD and ZREMRANGEBYRANK, causing one writer to
-- accidentally trim messages the other just added.
--
-- Spring Data Redis' ScriptExecutor handles the EVALSHA-to-EVAL
-- fallback transparently when the script SHA is missing (e.g.
-- after a Redis restart).
--
-- KEYS:
--   KEYS[1]  chat:room:{roomKey}:recent
--
-- ARGV:
--   ARGV[1]  score          -- epoch-millis (double)
--   ARGV[2]  json           -- serialised MessageResponse
--   ARGV[3]  retention-cap  -- max members to keep (e.g. 100)
--   ARGV[4]  ttl-seconds    -- key expiry in seconds
--
-- RETURN:
--   Long  1  (always; the caller maps to boolean)

redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
local stop = -(tonumber(ARGV[3]) + 1)
redis.call('ZREMRANGEBYRANK', KEYS[1], 0, stop)
redis.call('EXPIRE', KEYS[1], ARGV[4])
return 1
