-- Atomic sliding-window-log rate limiter.
--
-- Each client IP gets a sorted set keyed on epoch-millisecond timestamps.
-- On every request: prune expired entries, count the remainder, reject if
-- at or above the limit, otherwise record this request and allow.
--
-- Executed via ReactiveRedisTemplate.execute() for a single atomic
-- round-trip with no application-level locks.
--
-- KEYS:
--   KEYS[1]  rl:<ip>     -- Sorted Set of request timestamps
--
-- ARGV:
--   ARGV[1]  now-epoch-ms          -- current time in milliseconds
--   ARGV[2]  window-ms             -- trailing window size in milliseconds
--   ARGV[3]  limit                 -- max requests allowed in the window
--   ARGV[4]  ttl-seconds           -- key expiry (2x window, prevents stale-key buildup)
--   ARGV[5]  nonce                 -- random int to guarantee unique ZADD member per request
--
-- RETURN:
--   Integer count — the number of requests in the current window AFTER
--   recording this one.  The caller compares with the limit:
--   count > limit  → 429 Too Many Requests
--   count <= limit → allowed

local key       = KEYS[1]
local nowMs     = tonumber(ARGV[1])
local windowMs  = tonumber(ARGV[2])
local limit     = tonumber(ARGV[3])
local ttlSecs   = tonumber(ARGV[4])
local nonce     = ARGV[5]

-- 1. Remove entries older than the sliding window
local cutoff = nowMs - windowMs
redis.call('ZREMRANGEBYSCORE', key, 0, cutoff)

-- 2. Count the remaining (in-window) entries
local count = redis.call('ZCARD', key)

-- 3. If already at or above limit, reject WITHOUT recording this request
if count >= limit then
    -- Refresh TTL so the key doesn't disappear mid-window on an active spammer
    redis.call('EXPIRE', key, ttlSecs)
    return count
end

-- 4. Record this request.  Score = epoch-ms for sliding-window ordering.
--    Member = epoch-ms:nonce — the nonce guarantees uniqueness when two
--    requests land in the same millisecond (ZADD overwrites on member collision).
redis.call('ZADD', key, nowMs, nowMs .. ':' .. nonce)

-- 5. Prevent stale-key accumulation for abandoned IPs
redis.call('EXPIRE', key, ttlSecs)

return count + 1
