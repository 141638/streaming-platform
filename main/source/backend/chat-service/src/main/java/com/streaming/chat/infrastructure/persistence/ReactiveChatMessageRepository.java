package com.streaming.chat.infrastructure.persistence;

import com.streaming.chat.domain.ChatMessage;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R2DBC repository for {@link ChatMessage} entities.
 */
public interface ReactiveChatMessageRepository extends ReactiveCrudRepository<ChatMessage, UUID> {

    /**
     * Find messages for a room ordered by creation time descending (newest first).
     */
    Flux<ChatMessage> findByRoomIdOrderByCreatedAtDesc(UUID roomId);

    /**
     * Find messages older than a cursor for lazy-load pagination,
     * ordered newest-first.
     */
    Flux<ChatMessage> findByRoomIdAndCreatedAtBeforeOrderByCreatedAtDesc(
            UUID roomId, Instant createdAt);

    /**
     * Find distinct non-null author usernames for a room, filtered by prefix.
     * Used by the @mention autocomplete to suggest participants beyond the
     * current visible message list.
     */
    @Query("SELECT DISTINCT author_username FROM chat.chat_message "
            + "WHERE room_id = :roomId AND author_username IS NOT NULL "
            + "AND author_username ILIKE :query || '%' "
            + "ORDER BY author_username LIMIT :limit")
    Flux<String> findDistinctAuthorUsernamesByRoomId(UUID roomId, String query, int limit);

    /**
     * Find a message by its client-provided idempotency key.
     *
     * <p>Used to resolve the existing message on a duplicate-key violation —
     * returns the already-persisted message so the caller gets the same
     * confirmed response as the original send.
     *
     * <p>See ADR-0011: Message Idempotency via client_id Unique Constraint.
     */
    Mono<ChatMessage> findByClientId(String clientId);
}
