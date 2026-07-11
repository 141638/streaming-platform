package com.streaming.chat.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streaming.chat.application.ChatService.RoomNotFoundException;
import com.streaming.chat.config.ChatPbacProperties;
import com.streaming.chat.domain.ChatBan;
import com.streaming.chat.domain.ChatRoom;
import com.streaming.chat.infrastructure.persistence.ReactiveChatBanRepository;
import com.streaming.chat.infrastructure.persistence.ReactiveChatRoomRepository;
import com.streaming.chat.security.ChatAuthorization;
import com.streaming.chat.security.ChatAuthorization.ChatAccessDeniedException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("ModerationService")
@ExtendWith(MockitoExtension.class)
class ModerationServiceTest {

    private static final String ROOM_KEY = "room-abc";
    private static final String OWNER_SUB = "e8f9a1b2-3c4d-5e6f-7a8b-9c0d1e2f3a4b";
    private static final String OTHER_SUB = "f9a1b2c3-4d5e-6f7a-8b9c-0d1e2f3a4b5c";
    private static final String TARGET_SUB = "aabbccdd-1122-3344-5566-77889900aabb";

    @Mock
    private ReactiveChatRoomRepository roomRepository;

    @Mock
    private ReactiveChatBanRepository banRepository;

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

    /** Real ChatAuthorization wired with the given flag state, so gating is exercised end-to-end. */
    private ModerationService serviceWithPbac(boolean enabled) {
        ChatAuthorization authorization = new ChatAuthorization(new ChatPbacProperties(enabled));
        return new ModerationService(roomRepository, banRepository, authorization);
    }

    @Nested
    @DisplayName("with PBAC disabled (ships dark)")
    class FlagOff {

        @Test
        @DisplayName("ban succeeds for any caller and persists the ban")
        void banSucceedsWhenDisabled() {
            ModerationService service = serviceWithPbac(false);
            ChatRoom room = room();
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));
            when(banRepository.deleteByRoomIdAndBannedSubject(room.getId(), TARGET_SUB))
                    .thenReturn(Mono.empty());
            when(banRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.ban(jwt(OTHER_SUB, null), ROOM_KEY, TARGET_SUB, "spam"))
                    .assertNext(response -> {
                        org.assertj.core.api.Assertions.assertThat(response.bannedSubject()).isEqualTo(TARGET_SUB);
                        org.assertj.core.api.Assertions.assertThat(response.bannedBySubject()).isEqualTo(OTHER_SUB);
                        org.assertj.core.api.Assertions.assertThat(response.reason()).isEqualTo("spam");
                    })
                    .verifyComplete();

            verify(banRepository).save(any());
        }

        @Test
        @DisplayName("listBans returns bans for the room")
        void listBansWhenDisabled() {
            ModerationService service = serviceWithPbac(false);
            ChatRoom room = room();
            ChatBan existing = ChatBan.create(room.getId(), TARGET_SUB, OWNER_SUB, "spam",
                    OffsetDateTime.now(ZoneOffset.UTC), null);
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));
            when(banRepository.findByRoomId(room.getId())).thenReturn(Flux.just(existing));

            StepVerifier.create(service.listBans(jwt(OTHER_SUB, null), ROOM_KEY))
                    .assertNext(response ->
                            org.assertj.core.api.Assertions.assertThat(response.bannedSubject()).isEqualTo(TARGET_SUB))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("with PBAC enabled")
    class FlagOn {

        @Test
        @DisplayName("denies a non-owner caller without a moderate entitlement")
        void deniesNonOwnerWithoutEntitlement() {
            ModerationService service = serviceWithPbac(true);
            ChatRoom room = room();
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));

            StepVerifier.create(service.ban(jwt(OTHER_SUB, List.of()), ROOM_KEY, TARGET_SUB, "spam"))
                    .expectError(ChatAccessDeniedException.class)
                    .verify();

            verify(banRepository, never()).save(any());
        }

        @Test
        @DisplayName("allows a caller holding a wildcard moderate entitlement")
        void allowsWithModerateEntitlement() {
            ModerationService service = serviceWithPbac(true);
            ChatRoom room = room();
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));
            when(banRepository.deleteByRoomIdAndBannedSubject(room.getId(), TARGET_SUB))
                    .thenReturn(Mono.empty());
            when(banRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            Jwt staff = jwt(OTHER_SUB, List.of("allow chat:moderation:* moderate"));
            StepVerifier.create(service.ban(staff, ROOM_KEY, TARGET_SUB, "spam"))
                    .assertNext(response ->
                            org.assertj.core.api.Assertions.assertThat(response.bannedSubject()).isEqualTo(TARGET_SUB))
                    .verifyComplete();
        }

        @Test
        @DisplayName("allows the room owner via self-scoped moderate entitlement")
        void allowsOwnerViaSelfScope() {
            ModerationService service = serviceWithPbac(true);
            ChatRoom room = room();
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));
            when(banRepository.deleteByRoomIdAndBannedSubject(room.getId(), TARGET_SUB))
                    .thenReturn(Mono.empty());
            when(banRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            Jwt owner = jwt(OWNER_SUB, List.of("allow chat:moderation:self moderate"));
            StepVerifier.create(service.ban(owner, ROOM_KEY, TARGET_SUB, "spam"))
                    .assertNext(response ->
                            org.assertj.core.api.Assertions.assertThat(response.bannedBySubject()).isEqualTo(OWNER_SUB))
                    .verifyComplete();
        }
    }

    @Test
    @DisplayName("errors with RoomNotFoundException when the room is missing")
    void roomNotFound() {
        ModerationService service = serviceWithPbac(false);
        when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.empty());

        StepVerifier.create(service.ban(jwt(OTHER_SUB, null), ROOM_KEY, TARGET_SUB, "spam"))
                .expectError(RoomNotFoundException.class)
                .verify();

        verify(banRepository, never()).save(any());
    }
}
