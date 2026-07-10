package com.streaming.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.streaming.chat.api.dto.MessageResponse;
import com.streaming.chat.config.ChatCacheProperties;
import com.streaming.chat.domain.ChatMessage;
import com.streaming.chat.domain.ChatRoom;
import com.streaming.chat.infrastructure.cache.RedisMessageCache;
import com.streaming.chat.infrastructure.persistence.ReactiveChatMessageRepository;
import com.streaming.chat.infrastructure.persistence.ReactiveChatRoomRepository;
import com.streaming.chat.security.ChatAuthorization;
import com.streaming.chat.support.AbstractCacheIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Cache-aside orchestration tests for {@link ChatService} against real Redis + PG.
 *
 * <p>{@code ChatService} is constructed by hand with a no-op {@link SendGuard} so the
 * suite stays decoupled from Track A's in-flight {@code BanSendGuard}/PBAC wiring.
 * The repositories and {@link RedisMessageCache} are the real Spring-wired beans, so
 * these tests exercise genuine PG fallback, async backfill, and evict behaviour.
 */
@DisplayName("ChatService cache-aside (Testcontainers PG + Redis)")
class ChatServiceTest extends AbstractCacheIntegrationTest {

    private static final String SUB = "author-sub";
    private static final String USERNAME = "tester";

    /** A JWT with a stable subject — PBAC is disabled by default so it is never inspected. */
    private static final Jwt JWT = Jwt.withTokenValue("test-token")
            .header("alg", "HS256")
            .claim("sub", SUB)
            .build();

    @Autowired
    private ReactiveChatRoomRepository roomRepository;
    @Autowired
    private ReactiveChatMessageRepository messageRepository;
    @Autowired
    private RedisMessageCache cache;
    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    private final SendGuard noOpGuard = (room, authorSubject) -> Mono.empty();

    /** Mocked because ChatAuthorization's PBAC gating is Track A's concern. */
    private final ChatAuthorization authz = Mockito.mock(ChatAuthorization.class);

    private ChatService service;

    @BeforeEach
    void setUp() {
        // PBAC disabled → requireAccess short-circuits to Mono.empty()
        Mockito.when(authz.requireAccess(any(), any())).thenReturn(Mono.empty());
        service = new ChatService(roomRepository, messageRepository, cache, noOpGuard, authz);
        // clean slate — PG then Redis
        messageRepository.deleteAll().block(Duration.ofSeconds(10));
        roomRepository.deleteAll().block(Duration.ofSeconds(10));
        redisTemplate.getConnectionFactory().getReactiveConnection()
                .serverCommands().flushAll().block(Duration.ofSeconds(10));
    }

    // -- helpers -----------------------------------------------------------

    private ChatRoom saveRoom(String key, boolean active) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ChatRoom room = ChatRoom.create(key, SUB, now);
        if (!active) {
            room.archive(now);
        }
        return roomRepository.save(room).block(Duration.ofSeconds(10));
    }

    private void savePgMessages(ChatRoom room, int count) {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(count);
        for (int i = 0; i < count; i++) {
            ChatMessage msg = ChatMessage.create(
                    room.getId(), SUB, USERNAME, "pg-" + i, base.plusMinutes(i));
            messageRepository.save(msg).block(Duration.ofSeconds(10));
        }
    }

    private List<MessageResponse> awaitCacheSize(String roomKey, int expected) {
        for (int attempt = 0; attempt < 40; attempt++) {
            List<MessageResponse> cached = cache.getRecent(roomKey, 100).block(Duration.ofSeconds(5));
            if (cached != null && cached.size() >= expected) {
                return cached;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return cache.getRecent(roomKey, 100).block(Duration.ofSeconds(5));
    }

    // -- 6a. cache hit → no PG needed --------------------------------------

    @Test
    @DisplayName("getRecentMessages returns cached data even when the room is absent from PG")
    void cacheHitServesWithoutPg() {
        // Arrange — a message lives ONLY in the cache; PG has no such room
        String roomKey = "cache-only-room";
        MessageResponse cachedOnly = message(roomKey, "from cache");
        cache.addToRecent(roomKey, cachedOnly).block(Duration.ofSeconds(5));

        // Act + Assert
        StepVerifier.create(service.getRecentMessages(JWT, roomKey))
                .assertNext(list -> {
                    assertThat(list).hasSize(1);
                    assertThat(list.get(0).body()).isEqualTo("from cache");
                })
                .verifyComplete();
    }

    // -- 6b / 8. cache miss → PG fallback + async backfill -----------------

    @Test
    @DisplayName("cache miss falls back to PG, backfills the cache, and the next read hits cache")
    void cacheMissFallsBackToPgAndBackfills() {
        // Arrange
        String roomKey = "warmup-room";
        ChatRoom room = saveRoom(roomKey, true);
        savePgMessages(room, 5);

        // Act — first read is a miss → PG fallback
        List<MessageResponse> firstRead = service.getRecentMessages(JWT, roomKey).block(Duration.ofSeconds(10));

        // Assert — PG data returned, newest-first
        assertThat(firstRead).hasSize(5);
        assertThat(firstRead.get(0).body()).isEqualTo("pg-4");

        // Assert — async backfill eventually populates the cache
        List<MessageResponse> cached = awaitCacheSize(roomKey, 5);
        assertThat(cached).hasSize(5);
        assertThat(cached.get(0).body()).isEqualTo("pg-4");
    }

    // -- 6c. Redis down → PG fallback, no throw ----------------------------

    @Test
    @DisplayName("when Redis is unreachable, getRecentMessages still serves PG data without throwing")
    void redisDownFallsBackToPg() {
        // Arrange — a ChatService whose cache points at an unreachable Redis
        String roomKey = "redis-down-room";
        ChatRoom room = saveRoom(roomKey, true);
        savePgMessages(room, 3);

        LettuceConnectionFactory brokenFactory = new LettuceConnectionFactory("127.0.0.1", 1);
        brokenFactory.afterPropertiesSet();
        try {
            RedisMessageCache brokenCache = new RedisMessageCache(
                    new ReactiveStringRedisTemplate(brokenFactory),
                    new ChatCacheProperties(Duration.ofSeconds(1)));
            ChatService brokenService = new ChatService(
                    roomRepository, messageRepository, brokenCache, noOpGuard, authz);

            // Act + Assert
            StepVerifier.create(brokenService.getRecentMessages(JWT, roomKey))
                    .assertNext(list -> assertThat(list).hasSize(3))
                    .verifyComplete();
        } finally {
            brokenFactory.destroy();
        }
    }

    // -- 7. archived room rejects send -------------------------------------

    @Test
    @DisplayName("sendMessage to an archived room errors with RoomArchivedException")
    void sendToArchivedRoomIsRejected() {
        // Arrange
        String roomKey = "archived-room";
        saveRoom(roomKey, false);

        // Act + Assert
        StepVerifier.create(service.sendMessage(JWT, roomKey, SUB, USERNAME, "should fail"))
                .expectError(ChatService.RoomArchivedException.class)
                .verify();
    }

    @Test
    @DisplayName("sendMessage to a missing room errors with RoomNotFoundException")
    void sendToMissingRoomIsRejected() {
        // Act + Assert
        StepVerifier.create(service.sendMessage(JWT, "ghost", SUB, USERNAME, "no room"))
                .expectError(ChatService.RoomNotFoundException.class)
                .verify();
    }

    // -- 10. evict-on-write-failure ----------------------------------------

    @Test
    @DisplayName("a failed cache write during send evicts the room key so the next read rebuilds from PG")
    void failedCacheWriteEvictsRoomKey() {
        // Arrange — active room; seed the cache key so there is something to evict
        String roomKey = "evict-room";
        saveRoom(roomKey, true);
        cache.addToRecent(roomKey, message(roomKey, "stale-seed")).block(Duration.ofSeconds(5));
        assertThat(cache.getRecent(roomKey, 100).block(Duration.ofSeconds(5))).hasSize(1);

        // Spy cache: addToRecent silently fails; evictRoom delegates to the real impl
        RedisMessageCache spyCache = Mockito.spy(cache);
        Mockito.doReturn(Mono.just(false)).when(spyCache).addToRecent(anyString(), any());
        ChatService svc = new ChatService(roomRepository, messageRepository, spyCache, noOpGuard, authz);

        // Act — the send persists to PG but the cache write "fails"
        MessageResponse sent = svc.sendMessage(JWT, roomKey, SUB, USERNAME, "real message")
                .block(Duration.ofSeconds(10));

        // Assert — message is durable in PG
        assertThat(sent).isNotNull();
        Long pgCount = messageRepository.findByRoomIdOrderByCreatedAtDesc(
                roomRepository.findByExternalKey(roomKey).block(Duration.ofSeconds(5)).getId())
                .count().block(Duration.ofSeconds(5));
        assertThat(pgCount).isEqualTo(1L);

        // Assert — the room key was evicted (no stale hole left behind)
        Mockito.verify(spyCache).evictRoom(roomKey);
        assertThat(cache.getRecent(roomKey, 100).block(Duration.ofSeconds(5))).isEmpty();
    }

    // -- fixtures ----------------------------------------------------------

    private static MessageResponse message(String roomKey, String body) {
        return new MessageResponse(
                UUID.randomUUID(), roomKey, SUB, USERNAME, null, body,
                "NORMAL", null, null, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
