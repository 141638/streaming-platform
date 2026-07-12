package com.streaming.chat.infrastructure.persistence;

import com.streaming.chat.domain.ChatBan;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
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
     * List all bans for a room, including expired ones.
     */
    Flux<ChatBan> findByRoomId(UUID roomId);

    /**
     * List only the <em>active</em> bans for a room: permanent bans
     * ({@code expires_at IS NULL}) plus temporary bans that have not yet lapsed
     * ({@code expires_at > :now}).
     *
     * <p>The predicate is the SQL mirror of {@code BanSendGuard.isActive}, so the
     * moderator-facing roster ({@link com.streaming.chat.application.ModerationService#listBans})
     * matches what the send-path guard enforces. Filtering at the query keeps
     * expired rows off the wire (table name resolves against the {@code chat}
     * search_path, same as the derived queries).
     */
    @Query("SELECT * FROM chat_ban "
            + "WHERE room_id = :roomId AND (expires_at IS NULL OR expires_at > :now)")
    Flux<ChatBan> findActiveByRoomId(UUID roomId, OffsetDateTime now);

    /**
     * Remove the ban for a given user in a given room (idempotent unban).
     */
    Mono<Void> deleteByRoomIdAndBannedSubject(UUID roomId, String bannedSubject);
}
