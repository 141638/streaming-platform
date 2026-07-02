package com.streaming.chat.infrastructure.persistence;

import com.streaming.chat.domain.ChatMessage;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

/**
 * R2DBC repository for {@link ChatMessage} entities.
 */
public interface ReactiveChatMessageRepository extends ReactiveCrudRepository<ChatMessage, UUID> {

    /**
     * Find messages for a room ordered by creation time descending (newest first).
     */
    Flux<ChatMessage> findByRoomIdOrderByCreatedAtDesc(UUID roomId);
}
