package com.streaming.chat.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.streaming.chat.api.dto.MessageResponse;
import com.streaming.chat.config.ChatCacheProperties;
import com.streaming.chat.support.AbstractRedisIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for {@link RedisMessageCache} against a real Redis container.
 *
 * <p>Covers the ZSET hot-cache contract from ADR-0001 (write/read/retention/cursor),
 * the graceful-degradation contract (ADR-0000 "Redis is disposable"), and the
 * per-key TTL safety net from ADR-0003.
 */
@DisplayName("RedisMessageCache (Testcontainers Redis)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisMessageCacheTest extends AbstractRedisIntegrationTest {

    private static final String ROOM = "room-abc";
    private static final int LIMIT = 100;

    private RedisMessageCache cache;
    private ChatCacheProperties props;

    @BeforeEach
    void buildCache() {
        props = new ChatCacheProperties(Duration.ofHours(1));
        cache = new RedisMessageCache(redisTemplate, props);
    }

    private static String recentKey(String roomKey) {
        return "chat:room:" + roomKey + ":recent";
    }

    // -- 1. hot write ------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("addToRecent then getRecent returns the deserialized message with fields intact")
    void hotWriteRoundTrips() {
        // Arrange
        OffsetDateTime createdAt = utc(1_700_000_000_000L);
        MessageResponse msg = message(ROOM, "hello world", createdAt);

        // Act
        Boolean written = cache.addToRecent(ROOM, msg).block(Duration.ofSeconds(5));
        List<MessageResponse> read = cache.getRecent(ROOM, LIMIT).block(Duration.ofSeconds(5));

        // Assert
        assertThat(written).isTrue();
        assertThat(read).hasSize(1);
        assertThat(read.get(0).id()).isEqualTo(msg.id());
        assertThat(read.get(0).body()).isEqualTo("hello world");
        assertThat(read.get(0).createdAt().toInstant()).isEqualTo(createdAt.toInstant());
    }

    @Test
    @Order(2)
    @DisplayName("getRecent orders messages newest-first by score")
    void readOrdersNewestFirst() {
        // Arrange — insert oldest first so ordering must come from the ZSET, not insert order
        MessageResponse oldest = message(ROOM, "oldest", utc(1_000L));
        MessageResponse middle = message(ROOM, "middle", utc(2_000L));
        MessageResponse newest = message(ROOM, "newest", utc(3_000L));
        cache.addToRecent(ROOM, oldest).block(Duration.ofSeconds(5));
        cache.addToRecent(ROOM, newest).block(Duration.ofSeconds(5));
        cache.addToRecent(ROOM, middle).block(Duration.ofSeconds(5));

        // Act
        List<MessageResponse> read = cache.getRecent(ROOM, LIMIT).block(Duration.ofSeconds(5));

        // Assert
        assertThat(read).extracting(MessageResponse::body)
                .containsExactly("newest", "middle", "oldest");
    }

    // -- 2. read -----------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("getRecent on an empty key returns an empty list (cache miss)")
    void readEmptyKeyReturnsEmpty() {
        // Act
        List<MessageResponse> read = cache.getRecent("no-such-room", LIMIT).block(Duration.ofSeconds(5));

        // Assert
        assertThat(read).isEmpty();
    }

    // -- 3. retention ------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("writing 150 messages trims the ZSET to the 100 newest (ZREMRANGEBYRANK)")
    void retentionCapsAtHundred() {
        // Arrange
        for (int i = 0; i < 150; i++) {
            cache.addToRecent(ROOM, message(ROOM, "m" + i, utc(1_000L + i))).block(Duration.ofSeconds(5));
        }

        // Act
        List<MessageResponse> read = cache.getRecent(ROOM, 500).block(Duration.ofSeconds(5));

        // Assert — only the newest 100 survive; the oldest 50 (m0..m49) are trimmed
        assertThat(read).hasSize(100);
        assertThat(read.get(0).body()).isEqualTo("m149");
        assertThat(read).extracting(MessageResponse::body).doesNotContain("m0", "m49");
        assertThat(read.get(99).body()).isEqualTo("m50");
    }

    // -- 4. cursor ---------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("getBefore returns only messages with score strictly below the cursor")
    void cursorReturnsOlderMessagesOnly() {
        // Arrange — scores 1000..1049
        long base = 1_000L;
        for (int i = 0; i < 50; i++) {
            cache.addToRecent(ROOM, message(ROOM, "m" + i, utc(base + i))).block(Duration.ofSeconds(5));
        }
        double cursor = base + 25; // exclusive upper bound

        // Act
        List<MessageResponse> read = cache.getBefore(ROOM, cursor, LIMIT).block(Duration.ofSeconds(5));

        // Assert — scores 1000..1024 → 25 messages, all strictly < cursor
        assertThat(read).hasSize(25);
        assertThat(read).allSatisfy(m ->
                assertThat((double) m.createdAt().toInstant().toEpochMilli()).isLessThan(cursor));
        assertThat(read.get(0).body()).isEqualTo("m24");
    }

    // -- 9. TTL ------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("addToRecent sets a positive TTL bounded by the configured room-ttl")
    void writeAppliesTtl() {
        // Act
        cache.addToRecent(ROOM, message(ROOM, "with ttl", utc(5_000L))).block(Duration.ofSeconds(5));
        Duration ttl = redisTemplate.getExpire(recentKey(ROOM)).block(Duration.ofSeconds(5));

        // Assert
        assertThat(ttl).isNotNull();
        assertThat(ttl).isPositive();
        assertThat(ttl).isLessThanOrEqualTo(props.roomTtl());
    }

    @Test
    @Order(7)
    @DisplayName("a room key expires after its TTL, turning the next read into a cache miss")
    void keyExpiresAfterTtl() throws InterruptedException {
        // Arrange — a cache with a 1-second TTL
        RedisMessageCache shortTtl = new RedisMessageCache(
                redisTemplate, new ChatCacheProperties(Duration.ofSeconds(1)));
        shortTtl.addToRecent(ROOM, message(ROOM, "ephemeral", utc(6_000L))).block(Duration.ofSeconds(5));
        assertThat(shortTtl.getRecent(ROOM, LIMIT).block(Duration.ofSeconds(5))).hasSize(1);

        // Act — wait past the TTL
        Thread.sleep(1_500L);
        List<MessageResponse> afterExpiry = shortTtl.getRecent(ROOM, LIMIT).block(Duration.ofSeconds(5));

        // Assert
        assertThat(afterExpiry).isEmpty();
    }

    // -- 5. resilience (runs last: stops the shared container) --------------

    @Test
    @Order(100)
    @DisplayName("when Redis is unavailable, writes return false and reads return empty without throwing")
    void resilientWhenRedisDown() {
        // Arrange — prove the cache works, then take Redis away
        cache.addToRecent(ROOM, message(ROOM, "before outage", utc(7_000L))).block(Duration.ofSeconds(5));
        REDIS.stop();

        // Act + Assert — no operation propagates an error
        Boolean write = cache.addToRecent(ROOM, message(ROOM, "during outage", utc(8_000L)))
                .block(Duration.ofSeconds(10));
        List<MessageResponse> recent = cache.getRecent(ROOM, LIMIT).block(Duration.ofSeconds(10));
        List<MessageResponse> before = cache.getBefore(ROOM, 9_000L, LIMIT).block(Duration.ofSeconds(10));

        assertThat(write).isFalse();
        assertThat(recent).isEmpty();
        assertThat(before).isEmpty();
    }
}
