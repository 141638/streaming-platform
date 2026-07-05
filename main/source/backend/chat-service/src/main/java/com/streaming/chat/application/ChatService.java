package com.streaming.chat.application;

import com.streaming.chat.api.dto.MessageResponse;
import com.streaming.chat.api.dto.RoomResponse;
import com.streaming.chat.domain.ChatMessage;
import com.streaming.chat.domain.ChatRoom;
import com.streaming.chat.infrastructure.cache.RedisMessageCache;
import com.streaming.chat.infrastructure.persistence.ReactiveChatMessageRepository;
import com.streaming.chat.infrastructure.persistence.ReactiveChatRoomRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Application service for chat message operations.
 *
 * <p>Owns the cache-aside orchestration: reads hit Redis first and fall back
 * to PostgreSQL; writes go to PostgreSQL first and then update Redis.
 *
 * <p><b>Current scaffold:</b> writes are Redis-only (PG persistence is the
 * next work item). The author identity is derived from the JWT {@code sub},
 * fixing the impersonation bug in the prototype controller.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_RECENT = 50;

    private final ReactiveChatRoomRepository roomRepository;
    private final ReactiveChatMessageRepository messageRepository;
    private final RedisMessageCache cache;

    /**
     * Send a message to a chat room.
     *
     * <p>If the room does not exist yet, it is created on-the-fly (this
     * simplifies the Phase 3 scaffold; Phase 3.3 will replace this with
     * Kafka-driven room creation from stream events).
     *
     * @param roomKey      the room's external key
     * @param authorSubject the JWT {@code sub} claim — the authenticated user
     * @param body          the message content
     * @return the sent message as a response DTO
     */
    public Mono<MessageResponse> sendMessage(String roomKey, String authorSubject, String body) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return getOrCreateRoom(roomKey, now)
                .filter(ChatRoom::isActive)
                .switchIfEmpty(Mono.error(new RoomArchivedException(roomKey)))
                .flatMap(room -> {
                    log.info("Message hit.");
                    ChatMessage msg = ChatMessage.create(room.getId(), authorSubject, body, now);
                    return messageRepository.save(msg)
                            .map(saved -> MessageResponse.from(saved, room.getExternalKey()));
                })
                .flatMap(response -> cache.addToRecent(roomKey, response)
                        .doOnNext(success -> {
                            if (Boolean.TRUE.equals(success)) {
                                log.debug("Message cached: roomKey={}, id={}", roomKey, response.id());
                            }
                        })
                        .thenReturn(response));
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
                                                count -> {},
                                                err -> log.warn("Backfill cache write failed for roomKey={}", roomKey, err)
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
                .map(RoomResponse::from);
    }

    /**
     * Get messages older than the given cursor, for lazy-load history.
     *
     * @param roomKey the room's external key
     * @param cursor  ISO-8601 timestamp of the oldest message currently loaded
     * @param limit   max number of messages to return
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
                            .collectList();
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

    // -- internal ----------------------------------------------------------

    private Mono<ChatRoom> getOrCreateRoom(String externalKey, OffsetDateTime now) {
        return roomRepository.findByExternalKey(externalKey)
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("Auto-creating chat room for externalKey={}", externalKey);
                    return roomRepository.save(ChatRoom.create(externalKey, now));
                }));
    }

    // -- exceptions --------------------------------------------------------

    public static class RoomArchivedException extends RuntimeException {
        public RoomArchivedException(String roomKey) {
            super("Chat room is archived: roomKey=" + roomKey);
        }
    }
}
