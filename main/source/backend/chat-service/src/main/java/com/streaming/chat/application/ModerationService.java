package com.streaming.chat.application;

import com.streaming.chat.api.dto.BanResponse;
import com.streaming.chat.application.ChatService.RoomNotFoundException;
import com.streaming.chat.domain.ChatBan;
import com.streaming.chat.domain.ChatRoom;
import com.streaming.chat.infrastructure.persistence.ReactiveChatBanRepository;
import com.streaming.chat.infrastructure.persistence.ReactiveChatRoomRepository;
import com.streaming.chat.security.ChatAuthorization;
import com.streaming.pbac.AuthAction;
import com.streaming.pbac.AuthResourceDomain;
import com.streaming.pbac.AuthResourceKind;
import com.streaming.pbac.RequiredAuthority;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Application service for room moderation (bans).
 *
 * <p>Every operation loads the target room (404 via {@link RoomNotFoundException}
 * if missing), then enforces the PBAC {@code chat:moderation moderate} authority
 * against the room owner ({@code broadcasterSubject}) before mutating state.
 * PBAC enforcement is gated by {@code chat.pbac.enabled} inside
 * {@link ChatAuthorization} (ships dark).
 */
@Service
@RequiredArgsConstructor
public class ModerationService {

    private final ReactiveChatRoomRepository roomRepository;
    private final ReactiveChatBanRepository banRepository;
    private final ChatAuthorization chatAuthorization;

    /**
     * Ban a user from a room. Idempotent: an existing ban for the same
     * {@code (room, subject)} pair is replaced.
     *
     * @param bannedUsername  denormalized display name of the banned user, from
     *                        the request (client already holds it); may be {@code null}
     * @param bannedByUsername denormalized display name of the moderator, from the
     *                        JWT {@code attr.username}; may be {@code null}
     * @param durationSeconds ban duration in seconds; {@code null} = permanent,
     *                        a positive value = temporary ban expiring at
     *                        {@code now + durationSeconds}
     */
    public Mono<BanResponse> ban(
            Jwt jwt, String roomKey, String targetSubject, String bannedUsername,
            String bannedByUsername, String reason, Long durationSeconds) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = durationSeconds == null ? null : now.plusSeconds(durationSeconds);
        return authorizedRoom(jwt, roomKey)
                .flatMap(room -> banRepository
                        .deleteByRoomIdAndBannedSubject(room.getId(), targetSubject)
                        .then(banRepository.save(ChatBan.create(
                                room.getId(), targetSubject, bannedUsername,
                                jwt.getSubject(), bannedByUsername, reason, now, expiresAt))))
                .map(BanResponse::from);
    }

    /**
     * Re-base an existing ban's duration in place — the streamer-facing
     * alternative to unban + re-ban. Computes {@code expiresAt = now +
     * durationSeconds} ({@code null} duration ⇒ permanent) and updates the
     * loaded row (so the {@code (room, subject)} identity is preserved). Errors
     * with {@link BanNotFoundException} (404) when no ban exists for the subject.
     */
    public Mono<BanResponse> updateBanDuration(
            Jwt jwt, String roomKey, String targetSubject, Long durationSeconds) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = durationSeconds == null ? null : now.plusSeconds(durationSeconds);
        return authorizedRoom(jwt, roomKey)
                .flatMap(room -> banRepository
                        .findByRoomIdAndBannedSubject(room.getId(), targetSubject)
                        .switchIfEmpty(Mono.error(new BanNotFoundException(roomKey, targetSubject)))
                        .flatMap(ban -> {
                            ban.setExpiresAt(expiresAt);
                            // A loaded entity has isNew=false → save() issues an UPDATE,
                            // keeping the same row (unique (room_id, banned_subject) holds).
                            // Wave 2 (ADR-0007): emit a chat.moderation duration-delta event
                            // here so the banned user learns the new / lifted expiry.
                            return banRepository.save(ban);
                        }))
                .map(BanResponse::from);
    }

    /**
     * Lift a user's ban from a room. Idempotent: no-op if no ban exists.
     */
    public Mono<Void> unban(Jwt jwt, String roomKey, String targetSubject) {
        return authorizedRoom(jwt, roomKey)
                .flatMap(room -> banRepository.deleteByRoomIdAndBannedSubject(room.getId(), targetSubject));
    }

    /**
     * List the <em>active</em> bans for a room — permanent bans and temporary
     * bans that have not yet lapsed. Expired rows are excluded at the query level
     * ({@code expires_at IS NULL OR expires_at > now}) so the roster shown to
     * moderators matches exactly what {@code BanSendGuard} enforces on the send
     * path.
     */
    public Flux<BanResponse> listBans(Jwt jwt, String roomKey) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return authorizedRoom(jwt, roomKey)
                .flatMapMany(room -> banRepository.findActiveByRoomId(room.getId(), now))
                .map(BanResponse::from);
    }

    /**
     * Load the room (404 if missing) and require the {@code moderate} authority
     * against the room owner before returning it.
     */
    private Mono<ChatRoom> authorizedRoom(Jwt jwt, String roomKey) {
        return roomRepository.findByExternalKey(roomKey)
                .switchIfEmpty(Mono.error(new RoomNotFoundException(roomKey)))
                .flatMap(room -> chatAuthorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.CHAT, AuthResourceKind.MODERATION,
                                AuthAction.MODERATE, room.getBroadcasterSubject()))
                        .thenReturn(room));
    }

    // -- exceptions --------------------------------------------------------

    /**
     * Thrown when a moderation action targets a subject that has no ban in the
     * room (e.g. modifying the duration of a ban that was already lifted or
     * lapsed-and-purged). Mapped to {@code 404 CHAT_BAN_NOT_FOUND}.
     */
    public static class BanNotFoundException extends RuntimeException {
        public BanNotFoundException(String roomKey, String subject) {
            super("Ban not found: roomKey=" + roomKey + ", subject=" + subject);
        }
    }
}
