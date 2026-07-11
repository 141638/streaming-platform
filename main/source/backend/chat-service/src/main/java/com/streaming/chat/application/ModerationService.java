package com.streaming.chat.application;

import com.streaming.chat.api.dto.BanResponse;
import com.streaming.chat.application.ChatService.RoomNotFoundException;
import com.streaming.chat.domain.ChatBan;
import com.streaming.chat.domain.ChatRoom;
import com.streaming.chat.infrastructure.persistence.ReactiveChatBanRepository;
import com.streaming.chat.infrastructure.persistence.ReactiveChatRoomRepository;
import com.streaming.chat.security.AuthAction;
import com.streaming.chat.security.AuthResourceDomain;
import com.streaming.chat.security.AuthResourceKind;
import com.streaming.chat.security.ChatAuthorization;
import com.streaming.chat.security.RequiredAuthority;
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
     */
    public Mono<BanResponse> ban(Jwt jwt, String roomKey, String targetSubject, String reason) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return authorizedRoom(jwt, roomKey)
                .flatMap(room -> banRepository
                        .deleteByRoomIdAndBannedSubject(room.getId(), targetSubject)
                        .then(banRepository.save(
                                ChatBan.create(room.getId(), targetSubject, jwt.getSubject(), reason, now, null))))
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
     * List all bans for a room.
     */
    public Flux<BanResponse> listBans(Jwt jwt, String roomKey) {
        return authorizedRoom(jwt, roomKey)
                .flatMapMany(room -> banRepository.findByRoomId(room.getId()))
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
}
