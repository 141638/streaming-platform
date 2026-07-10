package com.streaming.chat.infrastructure.persistence;

import com.streaming.chat.domain.ChatBan;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R2DBC repository for {@link ChatBan} entities.
 */
public interface ReactiveChatBanRepository extends ReactiveCrudRepository<ChatBan, UUID> {

    /**
     * Find the ban for a given user in a given room, if any.
     * The {@code (room_id, banned_subject)} pair is unique (see V3 migration).
     */
    Mono<ChatBan> findByRoomIdAndBannedSubject(UUID roomId, String bannedSubject);

    /**
     * List all bans for a room.
     */
    Flux<ChatBan> findByRoomId(UUID roomId);

    /**
     * Remove the ban for a given user in a given room (idempotent unban).
     */
    Mono<Void> deleteByRoomIdAndBannedSubject(UUID roomId, String bannedSubject);
}
