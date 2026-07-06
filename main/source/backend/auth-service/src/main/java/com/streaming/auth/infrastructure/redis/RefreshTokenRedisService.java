package com.streaming.auth.infrastructure.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Low-level Redis operations for refresh token lifecycle.
 *
 * <p>Token rotation is driven by an atomic Lua script
 * ({@code redis/rotate_refresh_token.lua}) — no application-level
 * locks or transactions are needed.
 */
@Component
public class RefreshTokenRedisService {

    private static final String KEY_PREFIX = "rt:";
    private static final String FAMILY_PREFIX = "rt_family:";
    private static final String USER_PREFIX = "rt_user:";
    private static final String HASH_KEY_REVOKED_AT = "revokedAt";

    private final RedisTemplate<String, String> redis;
    private final RedisScript<String> rotateScript;

    public RefreshTokenRedisService(RedisTemplate<String, String> redisTemplate) throws IOException {
        this.redis = redisTemplate;
        String lua = new ClassPathResource("redis/rotate_refresh_token.lua")
                .getContentAsString(StandardCharsets.UTF_8);
        this.rotateScript = new DefaultRedisScript<>(lua, String.class);
    }

    // ── Public API ──────────────────────────────────────────────────

    /** Persist a new token and register it in the family + user indexes. */
    public void issueNewFamily(String tokenHash, UUID userId, UUID familyId, OffsetDateTime expiresAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long ttl = Duration.between(now, expiresAt).getSeconds();
        if (ttl <= 0) {
            throw new IllegalArgumentException("Token expiry must be in the future");
        }

        String key = tokenKey(tokenHash);
        String familyKey = familyKey(familyId);
        String userKey = userKey(userId);

        redis.opsForHash().putAll(key, Map.of(
                "userId", userId.toString(),
                "familyId", familyId.toString(),
                "expiresAt", expiresAt.toString(),
                "createdAt", now.toString()
        ));
        redis.expire(key, Duration.ofSeconds(ttl));

        redis.opsForSet().add(familyKey, key);
        redis.expire(familyKey, Duration.ofSeconds(ttl));

        redis.opsForSet().add(userKey, key);
    }

    /** Result of an atomic token rotation. */
    public enum RotateResult {OK, EXPIRED, REVOKED_FAMILY, INVALID}

    /**
     * Atomically rotate one refresh token for another.
     *
     * <p>The Lua script handles validation, replay detection, revocation,
     * and index maintenance in a single Redis round-trip.
     */
    public RotateResult rotate(
            String oldHash,
            String newHash,
            UUID userId,
            UUID familyId,
            OffsetDateTime expiresAt
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long ttl = Duration.between(now, expiresAt).getSeconds();
        if (ttl <= 0) {
            throw new IllegalArgumentException("Token expiry must be in the future");
        }

        List<String> keys = List.of(
                tokenKey(oldHash),
                tokenKey(newHash),
                familyKey(familyId),
                userKey(userId)
        );

        String result = redis.execute(rotateScript,
                keys,
                newHash,
                userId.toString(),
                familyId.toString(),
                expiresAt.toString(),
                now.toString(),
                String.valueOf(ttl),
                now.toString()
        );

        return RotateResult.valueOf(result);
    }

    /** Revoke all active tokens in a family (logout). */
    public void revokeFamily(UUID familyId) {
        Set<String> members = redis.opsForSet().members(familyKey(familyId));
        if (members == null || members.isEmpty()) {
            return;
        }
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        for (String key : members) {
            redis.opsForHash().put(key, HASH_KEY_REVOKED_AT, now);
        }
    }

    /** Revoke all active tokens for a user (password reset). */
    public void revokeAllByUser(UUID userId) {
        Set<String> members = redis.opsForSet().members(userKey(userId));
        if (members == null || members.isEmpty()) {
            return;
        }
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        for (String key : members) {
            redis.opsForHash().put(key, HASH_KEY_REVOKED_AT, now);
        }
    }

    /** Look up token metadata for non-rotation operations (e.g. logout lookup). */
    public Optional<TokenMetadata> findByHash(String tokenHash) {
        var entries = redis.opsForHash().entries(tokenKey(tokenHash));
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TokenMetadata(
                UUID.fromString((String) entries.get("userId")),
                UUID.fromString((String) entries.get("familyId")),
                OffsetDateTime.parse((String) entries.get("expiresAt")),
                entries.containsKey(HASH_KEY_REVOKED_AT) && !((String) entries.get(HASH_KEY_REVOKED_AT)).isEmpty()
        ));
    }

    // ── Key helpers ─────────────────────────────────────────────────

    private static String tokenKey(String hash) {
        return KEY_PREFIX + hash;
    }

    private static String familyKey(UUID familyId) {
        return FAMILY_PREFIX + familyId.toString();
    }

    private static String userKey(UUID userId) {
        return USER_PREFIX + userId.toString();
    }

    // ── Value type ──────────────────────────────────────────────────

    public record TokenMetadata(
            UUID userId,
            UUID familyId,
            OffsetDateTime expiresAt,
            boolean revoked
    ) {
    }
}
