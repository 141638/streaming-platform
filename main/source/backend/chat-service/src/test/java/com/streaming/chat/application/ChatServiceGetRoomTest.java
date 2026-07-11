package com.streaming.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.streaming.chat.api.dto.RoomResponse;
import com.streaming.chat.config.ChatPbacProperties;
import com.streaming.chat.domain.ChatRoom;
import com.streaming.chat.infrastructure.cache.RedisMessageCache;
import com.streaming.chat.infrastructure.persistence.ReactiveChatMessageRepository;
import com.streaming.chat.infrastructure.persistence.ReactiveChatRoomRepository;
import com.streaming.chat.security.ChatAuthorization;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
        ChatService service = new ChatService(roomRepository, messageRepository, cache, noOpGuard, authz);
        when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room()));
        RoomResponse response = service.getRoom(jwt, ROOM_KEY).block();
        assertThat(response).isNotNull();
        return response.viewerCanModerate();
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
}
