package com.streaming.chat.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.streaming.chat.config.ChatCacheProperties;
import com.streaming.chat.support.AbstractRedisIntegrationTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link RedisReconnectListener#evictAllRooms()} — mechanism #2
 * of ADR-0003. Verifies the reconnect eviction path removes every
 * {@code chat:room:*:recent} key while leaving unrelated keys untouched.
 */
@DisplayName("RedisReconnectListener (Testcontainers Redis)")
class RedisReconnectListenerTest extends AbstractRedisIntegrationTest {

    private RedisMessageCache cache;
    private RedisReconnectListener listener;

    @BeforeEach
    void buildComponents() {
        cache = new RedisMessageCache(redisTemplate, new ChatCacheProperties(Duration.ofHours(1)));
        listener = new RedisReconnectListener(redisTemplate, connectionFactory);
    }

    // -- 11. evict-on-reconnect --------------------------------------------

    @Test
    @DisplayName("evictAllRooms deletes every room recent-key and reports the count")
    void evictsAllRoomKeys() {
        // Arrange — three populated rooms plus one unrelated key
        cache.addToRecent("room-a", message("room-a", "a1", utc(1_000L))).block(Duration.ofSeconds(5));
        cache.addToRecent("room-b", message("room-b", "b1", utc(2_000L))).block(Duration.ofSeconds(5));
        cache.addToRecent("room-c", message("room-c", "c1", utc(3_000L))).block(Duration.ofSeconds(5));
        redisTemplate.opsForValue().set("chat:other:sentinel", "keep-me").block(Duration.ofSeconds(5));

        // Act
        Long evicted = listener.evictAllRooms().block(Duration.ofSeconds(10));

        // Assert — all three room keys gone
        assertThat(evicted).isEqualTo(3L);
        assertThat(cache.getRecent("room-a", 100).block(Duration.ofSeconds(5))).isEmpty();
        assertThat(cache.getRecent("room-b", 100).block(Duration.ofSeconds(5))).isEmpty();
        assertThat(cache.getRecent("room-c", 100).block(Duration.ofSeconds(5))).isEmpty();

        List<String> survivors = redisTemplate.keys("chat:room:*:recent")
                .collectList().block(Duration.ofSeconds(5));
        assertThat(survivors).isEmpty();

        // Unrelated key must survive
        assertThat(redisTemplate.opsForValue().get("chat:other:sentinel").block(Duration.ofSeconds(5)))
                .isEqualTo("keep-me");
    }

    @Test
    @DisplayName("evictAllRooms is a no-op that returns zero when no room keys exist")
    void evictsNothingWhenEmpty() {
        // Act
        Long evicted = listener.evictAllRooms().block(Duration.ofSeconds(10));

        // Assert
        assertThat(evicted).isZero();
    }
}
