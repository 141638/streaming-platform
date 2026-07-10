package com.streaming.chat.application;

import com.streaming.chat.api.dto.MessageResponse;
import com.streaming.chat.api.dto.RoomResponse;
import com.streaming.chat.domain.ChatMessage;
import com.streaming.chat.domain.ChatRoom;
import com.streaming.chat.infrastructure.cache.RedisMessageCache;
import com.streaming.chat.infrastructure.persistence.ReactiveChatMessageRepository;
import com.streaming.chat.infrastructure.persistence.ReactiveChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Application service for chat message operations.
 *
 * <p>Owns the cache-aside orchestration: reads hit Redis first and fall back
 * to PostgreSQL; writes go to PostgreSQL first and then update Redis.
 *
 * <p>The write path is a small composed pipeline —
 * {@code lookup → active-check → guard → persist → cache} — so cross-cutting
 * concerns plug into explicit seams:
 * <ul>
 *   <li>{@link SendGuard} is the Layer-2 (resource-state) pre-write hook; Phase
 *       3.4 supplies the ban-enforcing implementation.</li>
 *   <li>The cache-write step self-heals a silently-dropped write by evicting the
 *       room key so the next read rebuilds from PG (ADR-0003 §evict-on-write-failure).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_RECENT = 50;

    private final ReactiveChatRoomRepository roomRepository;
    private final ReactiveChatMessageRepository messageRepository;
    private final RedisMessageCache cache;
    private final SendGuard sendGuard;

    /**
     * Send a message to a chat room.
     *
     * <p>The room must already exist (created from a {@code STREAM_CREATED} Kafka
     * event, Phase 3.3) and be active; otherwise the call errors with
     * {@link RoomNotFoundException} / {@link RoomArchivedException}. The
     * {@link SendGuard} runs after the active check and before persistence.
     *
     * @param roomKey the room's external key
     * @param authorSubject the JWT {@code sub} claim — the authenticated user
     * @param authorUsername denormalized display name from JWT {@code attr.username}
     * @param body the message content
     * @return the sent message as a response DTO
     */
    public Mono<MessageResponse> sendMessage(String roomKey, String authorSubject, String authorUsername, String body) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return roomRepository.findByExternalKey(roomKey)
                .switchIfEmpty(Mono.error(new RoomNotFoundException(roomKey)))
                .filter(ChatRoom::isActive)
                .switchIfEmpty(Mono.error(new RoomArchivedException(roomKey)))
                .flatMap(room -> sendGuard.check(room, authorSubject).thenReturn(room))
                .flatMap(room -> persistAndCache(room, authorSubject, authorUsername, body, now));
    }

    private Mono<MessageResponse> persistAndCache(
            ChatRoom room, String authorSubject, String authorUsername, String body, OffsetDateTime now) {
        ChatMessage msg = ChatMessage.create(room.getId(), authorSubject, authorUsername, body, now);
        return messageRepository.save(msg)
                .map(saved -> MessageResponse.from(saved, room.getExternalKey()))
                .flatMap(this::cacheWrite);
    }

    /**
     * Update the hot cache after a successful PG persist. If the cache write
     * silently fails while Redis is otherwise serving reads, the room key would
     * serve a permanent hole (missing this message) until TTL expiry — so we
     * evict the key, forcing the next read to miss and rebuild the complete set
     * from PG. Turns a silent staleness window into a single cold read.
     *
     * <p>See ADR-0003 §evict-on-write-failure.
     * TODO(3.x-deferred): periodic reconciliation sweep for hot rooms — see
     * ADR-0003 §Deferred (Option 2). Revisit if evict-on-failure proves insufficient.
     */
    private Mono<MessageResponse> cacheWrite(MessageResponse response) {
        return cache.addToRecent(response.roomKey(), response)
                .flatMap(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        log.debug("Message cached: roomKey={}, id={}", response.roomKey(), response.id());
                        return Mono.just(response);
                    }
                    log.warn("Cache write failed for roomKey={}, evicting key to force PG rebuild on next read",
                            response.roomKey());
                    return cache.evictRoom(response.roomKey()).thenReturn(response);
                });
    }

    /**
     * Get recent messages for a room.
     *
     * <p>Attempts Redis first; falls back to PostgreSQL on cache miss.
     *
     * @param roomKey the room's external key
     * @return the most recent messages (up to {@value #MAX_RECENT})
     */
    public Mono<List<MessageResponse>> getRecentMessages(String roomKey) {
        return cache.getRecent(roomKey, MAX_RECENT)
                .flatMap(cached -> {
                    if (!cached.isEmpty()) {
                        log.debug("Cache hit for roomKey={}, count={}", roomKey, cached.size());
                        return Mono.just(cached);
                    }
                    log.debug("Cache miss for roomKey={}, falling back to PG", roomKey);
                    return roomRepository.findByExternalKey(roomKey)
                            .flatMapMany(room -> messageRepository
                                    .findByRoomIdOrderByCreatedAtDesc(room.getId())
                                    .take(MAX_RECENT)
                                    .map(msg -> MessageResponse.from(msg, room.getExternalKey())))
                            .collectList()
                            .flatMap(fromPg -> {
                                // async backfill — don't block the response
                                Flux.fromIterable(fromPg)
                                        .flatMap(m -> cache.addToRecent(roomKey, m))
                                        .subscribe(
                                                count -> {
                                                },
                                                err -> log.warn("Backfill cache write failed for roomKey={}", roomKey,
                                                        err)
                                        );
                                return Mono.just(fromPg);
                            });
                });
    }

    /**
     * Look up a room by its external key.
     *
     * @param roomKey the room's external key
     * @return the room metadata, or {@link Mono#empty()} if not found
     */
    public Mono<RoomResponse> getRoom(String roomKey) {
        return roomRepository.findByExternalKey(roomKey)
                .switchIfEmpty(Mono.error(new RoomNotFoundException(roomKey)))
                .map(RoomResponse::from);
    }

    /**
     * Get messages older than the given cursor, for lazy-load history.
     *
     * @param roomKey the room's external key
     * @param cursor ISO-8601 timestamp of the oldest message currently loaded
     * @param limit max number of messages to return
     * @return messages ordered newest-first (empty list if none)
     */
    public Mono<List<MessageResponse>> getMessagesBefore(String roomKey, String cursor, int limit) {
        double maxScore = parseCursorToEpochMillis(cursor);
        return cache.getBefore(roomKey, maxScore, limit)
                .flatMap(cached -> {
                    if (!cached.isEmpty()) {
                        log.debug("Cache before-hit for roomKey={}, count={}", roomKey, cached.size());
                        return Mono.just(cached);
                    }
                    log.debug("Cache before-miss for roomKey={}, falling back to PG", roomKey);
                    return roomRepository.findByExternalKey(roomKey)
                            .flatMapMany(room -> messageRepository
                                    .findByRoomIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                                            room.getId(),
                                            parseCursorToInstant(cursor))
                                    .take(limit)
                                    .map(msg -> MessageResponse.from(msg, room.getExternalKey())))
                            .collectList()
                            .flatMap(fromPg -> {
                                // async backfill — don't block the response
                                Flux.fromIterable(fromPg)
                                        .flatMap(m -> cache.addToRecent(roomKey, m))
                                        .subscribe(
                                                count -> {},
                                                err -> log.warn("Backfill cache write failed for roomKey={}", roomKey,
                                                        err)
                                        );
                                return Mono.just(fromPg);
                            });
                });
    }

    private static double parseCursorToEpochMillis(String cursor) {
        try {
            return Instant.parse(cursor).toEpochMilli();
        } catch (Exception e) {
            log.warn("Invalid cursor, falling back to now: {}", cursor);
            return System.currentTimeMillis();
        }
    }

    private static Instant parseCursorToInstant(String cursor) {
        try {
            return Instant.parse(cursor);
        } catch (Exception e) {
            return Instant.now();
        }
    }

    // -- exceptions --------------------------------------------------------

    public static class RoomNotFoundException extends RuntimeException {
        public RoomNotFoundException(String roomKey) {
            super("Chat room is notfound: roomKey=" + roomKey);
        }
    }

    public static class RoomArchivedException extends RuntimeException {
        public RoomArchivedException(String roomKey) {
            super("Chat room is archived: roomKey=" + roomKey);
        }
    }
}
