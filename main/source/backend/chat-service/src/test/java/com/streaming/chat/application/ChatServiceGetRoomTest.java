package com.streaming.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.streaming.chat.api.dto.RoomResponse;
import com.streaming.chat.config.ChatPbacProperties;
import com.streaming.chat.domain.ChatBan;
import com.streaming.chat.domain.ChatRoom;
import com.streaming.chat.infrastructure.cache.RedisMessageCache;
import com.streaming.chat.infrastructure.persistence.ReactiveChatBanRepository;
import com.streaming.chat.infrastructure.persistence.ReactiveChatMessageRepository;
import com.streaming.chat.infrastructure.persistence.ReactiveChatRoomRepository;
import com.streaming.chat.security.ChatAuthorization;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/**
 * Focused unit tests for {@link ChatService#getRoom} — specifically the
 * {@code viewerCanModerate} capability signal. Uses a <em>real</em>
 * {@link ChatAuthorization} so the flag-independence of the capability query is
 * exercised end-to-end (the cache/message repository are unused mocks — getRoom
 * only touches the room repository and authorization).
 *
 * <p>Contract under test: {@code viewerCanModerate} is {@code true} for a room
 * owner (self-scoped moderate) and platform staff (wildcard moderate),
 * {@code false} for a plain viewer, and — critically — the same value whether
 * {@code chat.pbac.enabled} is {@code true} or {@code false}, because capability
 * display must not depend on the enforcement dark-launch flag.
 */
@DisplayName("ChatService.getRoom — viewerCanModerate capability signal")
@ExtendWith(MockitoExtension.class)
class ChatServiceGetRoomTest {

    private static final String ROOM_KEY = "room-abc";
    private static final String OWNER_SUB = "e8f9a1b2-3c4d-5e6f-7a8b-9c0d1e2f3a4b";
    private static final String OTHER_SUB = "f9a1b2c3-4d5e-6f7a-8b9c-0d1e2f3a4b5c";

    // Materialized ent lines mirroring auth-service V10 per-role grammar. Each role
    // carries chat:room:* read so the getRoom READ gate passes when the flag is ON.
    private static final List<String> VIEWER = List.of(
            "allow chat:room:* read",
            "allow chat:message:* read send read_history");
    private static final List<String> STREAMER = List.of(
            "allow chat:room:* read",
            "allow chat:message:* read send read_history",
            "allow chat:moderation:self moderate");
    private static final List<String> MODERATOR = List.of(
            "allow chat:moderation:* moderate",
            "allow chat:message:* read read_history send delete",
            "allow chat:room:* read");

    @Mock
    private ReactiveChatRoomRepository roomRepository;
    @Mock
    private ReactiveChatMessageRepository messageRepository;
    @Mock
    private RedisMessageCache cache;
    @Mock
    private ReactiveChatBanRepository banRepository;

    private final SendGuard noOpGuard = (room, authorSubject) -> Mono.empty();

    private static Jwt jwt(String sub, List<String> ent) {
        var builder = Jwt.withTokenValue("test-token").header("alg", "HS256").claim("sub", sub);
        if (ent != null) {
            builder.claim("ent", ent);
        }
        return builder.build();
    }

    private static ChatRoom room() {
        return ChatRoom.create(ROOM_KEY, OWNER_SUB, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** Run getRoom with a real ChatAuthorization at the given flag state; return the capability bit. */
    private boolean viewerCanModerate(Jwt jwt, boolean pbacEnabled) {
        ChatAuthorization authz = new ChatAuthorization(new ChatPbacProperties(pbacEnabled));
        ChatService service = new ChatService(
                roomRepository, messageRepository, cache, noOpGuard, authz, banRepository);
        when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room()));
        // The capability tests only assert viewerCanModerate — the caller has no ban.
        // lenient: the null-principal path short-circuits before the ban lookup runs.
        lenient().when(banRepository.findByRoomIdAndBannedSubject(any(), any()))
                .thenReturn(Mono.empty());
        RoomResponse response = service.getRoom(jwt, ROOM_KEY).block();
        assertThat(response).isNotNull();
        return response.viewerCanModerate();
    }

    /** Run getRoom with the given (active/expired/absent) ban and return the viewerBanned bit. */
    private boolean viewerBanned(Jwt jwt, ChatBan existingBan) {
        ChatAuthorization authz = new ChatAuthorization(new ChatPbacProperties(false));
        ChatService service = new ChatService(
                roomRepository, messageRepository, cache, noOpGuard, authz, banRepository);
        ChatRoom room = room();
        when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));
        // lenient: a null principal short-circuits resolveViewerBanned before this lookup.
        lenient().when(banRepository.findByRoomIdAndBannedSubject(eq(room.getId()), any()))
                .thenReturn(existingBan == null ? Mono.empty() : Mono.just(existingBan));
        RoomResponse response = service.getRoom(jwt, ROOM_KEY).block();
        assertThat(response).isNotNull();
        return response.viewerBanned();
    }

    private static ChatBan ban(OffsetDateTime expiresAt) {
        return ChatBan.create(
                UUID.randomUUID(), OTHER_SUB, null, OWNER_SUB, null, "spam",
                OffsetDateTime.now(ZoneOffset.UTC), expiresAt);
    }

    @Test
    @DisplayName("owner (self-scoped moderate) → true, regardless of the enforcement flag")
    void ownerCanModerate() {
        Jwt owner = jwt(OWNER_SUB, STREAMER);
        assertThat(viewerCanModerate(owner, false)).isTrue();
        assertThat(viewerCanModerate(owner, true)).isTrue();
    }

    @Test
    @DisplayName("staff (wildcard moderate) → true, regardless of the enforcement flag")
    void staffCanModerate() {
        Jwt staff = jwt(OTHER_SUB, MODERATOR);
        assertThat(viewerCanModerate(staff, false)).isTrue();
        assertThat(viewerCanModerate(staff, true)).isTrue();
    }

    @Test
    @DisplayName("plain viewer → false, regardless of the enforcement flag")
    void viewerCannotModerate() {
        Jwt viewer = jwt(OTHER_SUB, VIEWER);
        assertThat(viewerCanModerate(viewer, false)).isFalse();
        assertThat(viewerCanModerate(viewer, true)).isFalse();
    }

    @Test
    @DisplayName("unauthenticated principal (null jwt) → false, no error (flag dark)")
    void nullPrincipalCannotModerate() {
        // With PBAC dark (the default), the READ gate short-circuits without touching
        // the principal, and the capability query maps a null principal to false.
        assertThat(viewerCanModerate(null, false)).isFalse();
    }

    @Test
    @DisplayName("an active (permanent) ban for the caller → viewerBanned true")
    void activeBanFlagsViewer() {
        Jwt viewer = jwt(OTHER_SUB, VIEWER);
        assertThat(viewerBanned(viewer, ban(null))).isTrue();
    }

    @Test
    @DisplayName("no ban row for the caller → viewerBanned false")
    void noBanLeavesViewerUnflagged() {
        Jwt viewer = jwt(OTHER_SUB, VIEWER);
        assertThat(viewerBanned(viewer, null)).isFalse();
    }

    @Test
    @DisplayName("an already-lapsed ban → viewerBanned false (matches the send-guard rule)")
    void expiredBanDoesNotFlagViewer() {
        Jwt viewer = jwt(OTHER_SUB, VIEWER);
        assertThat(viewerBanned(viewer, ban(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)))).isFalse();
    }

    @Test
    @DisplayName("null principal → viewerBanned false, without a ban lookup")
    void nullPrincipalIsNeverBanned() {
        assertThat(viewerBanned(null, ban(null))).isFalse();
    }
}
