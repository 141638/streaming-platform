package com.streaming.chat.infrastructure.persistence;

import com.streaming.chat.domain.ChatRoom;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/**
 * R2DBC repository for {@link ChatRoom} entities.
 */
public interface ReactiveChatRoomRepository extends ReactiveCrudRepository<ChatRoom, UUID> {

    /**
     * Find a room by its external key (the stream session external key).
     */
    Mono<ChatRoom> findByExternalKey(String externalKey);

    /**
     * Check whether a room with the given external key already exists.
     */
    Mono<Boolean> existsByExternalKey(String externalKey);
}
