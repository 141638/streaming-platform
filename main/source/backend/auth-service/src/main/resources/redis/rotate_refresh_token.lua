-- Atomic refresh token rotation with replay-attack detection.
--
-- Replaces the old JPA-based pessimistic-lock + @Transactional approach.
-- Executed via Redis EVALSHA for O(1) script-parsing after the first call.
--
-- KEYS:
--   KEYS[1]  rt:<oldHash>           -- Hash of the token being rotated
--   KEYS[2]  rt:<newHash>           -- Hash of the replacement token
--   KEYS[3]  rt_family:<familyId>   -- SET of token hashes in the family
--   KEYS[4]  rt_user:<userId>       -- SET of token hashes for the user
--
-- ARGV:
--   ARGV[1]  new-token-hash (hex)          -- the new token's SHA-256 hash (hex string)
--   ARGV[2]  new-token-user-id             -- UUID string
--   ARGV[3]  new-token-family-id           -- UUID string
--   ARGV[4]  new-token-expires-at          -- ISO-8601 timestamp
--   ARGV[5]  new-token-created-at          -- ISO-8601 timestamp
--   ARGV[6]  ttl-seconds                   -- token expiry in seconds (for EXPIRE)
--   ARGV[7]  now-timestamp                 -- current UTC timestamp in ISO-8601
--
-- RETURN:
--   "OK"                  -- rotation succeeded
--   "EXPIRED"             -- old token has passed its expires_at
--   "REVOKED_FAMILY"      -- old token was already revoked (replay attack); entire family revoked
--   "INVALID"             -- old token hash not found

local oldKey      = KEYS[1]
local newKey      = KEYS[2]
local familyKey   = KEYS[3]
local userKey     = KEYS[4]

local newHash     = ARGV[1]
local newUserId   = ARGV[2]
local newFamilyId = ARGV[3]
local newExpires  = ARGV[4]
local newCreated  = ARGV[5]
local ttlSeconds  = tonumber(ARGV[6])
local now         = ARGV[7]

-- 1. Read the old token
local old = redis.call('HGETALL', oldKey)
if #old == 0 then
    return 'INVALID'
end

-- Convert flat list to a table for lookup
local oldMap = {}
for i = 1, #old, 2 do
    oldMap[old[i]] = old[i + 1]
end

-- 2. Replay-attack detection: if the old token was already revoked, revoke the entire family
if oldMap['revokedAt'] ~= nil and oldMap['revokedAt'] ~= '' then
    local members = redis.call('SMEMBERS', familyKey)
    for _, member in ipairs(members) do
        redis.call('HSET', member, 'revokedAt', now)
    end
    return 'REVOKED_FAMILY'
end

-- 3. Expiry check
if oldMap['expiresAt'] <= now then
    return 'EXPIRED'
end

-- 4. Valid rotation: revoke the old token
redis.call('HSET', oldKey, 'revokedAt', now)

-- 5. Persist the new token
redis.call('HSET', newKey,
    'userId',    newUserId,
    'familyId',  newFamilyId,
    'expiresAt', newExpires,
    'createdAt', newCreated
)
redis.call('EXPIRE', newKey, ttlSeconds)

-- 6. Maintain family index (old out, new in)
redis.call('SREM', familyKey, oldKey)
redis.call('SADD', familyKey, newKey)
redis.call('EXPIRE', familyKey, ttlSeconds)

-- 7. Maintain user index
redis.call('SADD', userKey, newKey)
-- No EXPIRE on user index — other tokens in the set may have longer TTLs.
-- Stale entries accumulate harmlessly (O(user's token count), cleaned by eventual housekeeping).

return 'OK'
