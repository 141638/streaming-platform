package com.streaming.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

            StepVerifier.create(service.ban(jwt(OTHER_SUB, null), ROOM_KEY, TARGET_SUB, null, null, "spam", null))
                    .assertNext(response -> {
                        assertThat(response.bannedSubject()).isEqualTo(TARGET_SUB);
                        assertThat(response.bannedBySubject()).isEqualTo(OTHER_SUB);
                        assertThat(response.reason()).isEqualTo("spam");
                    })
                    .verifyComplete();

            verify(banRepository).save(any());
        }

        @Test
        @DisplayName("listBans returns the active bans for the room")
        void listBansWhenDisabled() {
            ModerationService service = serviceWithPbac(false);
            ChatRoom room = room();
            ChatBan existing = ChatBan.create(room.getId(), TARGET_SUB, null, OWNER_SUB, null, "spam",
                    OffsetDateTime.now(ZoneOffset.UTC), null);
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));
            when(banRepository.findActiveByRoomId(eq(room.getId()), any()))
                    .thenReturn(Flux.just(existing));

            StepVerifier.create(service.listBans(jwt(OTHER_SUB, null), ROOM_KEY))
                    .assertNext(response -> assertThat(response.bannedSubject()).isEqualTo(TARGET_SUB))
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

            StepVerifier.create(service.ban(jwt(OTHER_SUB, List.of()), ROOM_KEY, TARGET_SUB, null, null, "spam", null))
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
            StepVerifier.create(service.ban(staff, ROOM_KEY, TARGET_SUB, null, null, "spam", null))
                    .assertNext(response -> assertThat(response.bannedSubject()).isEqualTo(TARGET_SUB))
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
            StepVerifier.create(service.ban(owner, ROOM_KEY, TARGET_SUB, null, null, "spam", null))
                    .assertNext(response -> assertThat(response.bannedBySubject()).isEqualTo(OWNER_SUB))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("temporary vs permanent ban duration")
    class BanDuration {

        @Test
        @DisplayName("a positive durationSeconds sets expiresAt = now + duration")
        void temporaryBanSetsExpiry() {
            ModerationService service = serviceWithPbac(false);
            ChatRoom room = room();
            long duration = 3600L;
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));
            when(banRepository.deleteByRoomIdAndBannedSubject(room.getId(), TARGET_SUB))
                    .thenReturn(Mono.empty());
            ArgumentCaptor<ChatBan> captor = ArgumentCaptor.forClass(ChatBan.class);
            when(banRepository.save(captor.capture())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.ban(jwt(OTHER_SUB, null), ROOM_KEY, TARGET_SUB, null, null, "spam", duration))
                    .assertNext(response -> {
                        assertThat(response.expiresAt()).isNotNull();
                        // computed off the same `now`, so expiresAt == createdAt + duration
                        assertThat(response.expiresAt())
                                .isEqualTo(response.createdAt().plusSeconds(duration));
                    })
                    .verifyComplete();

            ChatBan persisted = captor.getValue();
            assertThat(persisted.getExpiresAt()).isNotNull();
            assertThat(ChronoUnit.SECONDS.between(persisted.getCreatedAt(), persisted.getExpiresAt()))
                    .isEqualTo(duration);
        }

        @Test
        @DisplayName("a null durationSeconds persists a permanent ban (expiresAt == null)")
        void permanentBanHasNoExpiry() {
            ModerationService service = serviceWithPbac(false);
            ChatRoom room = room();
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));
            when(banRepository.deleteByRoomIdAndBannedSubject(room.getId(), TARGET_SUB))
                    .thenReturn(Mono.empty());
            ArgumentCaptor<ChatBan> captor = ArgumentCaptor.forClass(ChatBan.class);
            when(banRepository.save(captor.capture())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.ban(jwt(OTHER_SUB, null), ROOM_KEY, TARGET_SUB, null, null, "spam", null))
                    .assertNext(response -> assertThat(response.expiresAt()).isNull())
                    .verifyComplete();

            assertThat(captor.getValue().getExpiresAt()).isNull();
        }
    }

    @Nested
    @DisplayName("listBans excludes expired rows")
    class ListBansActiveOnly {

        @Test
        @DisplayName("queries the active-only roster (expired rows never reach the response)")
        void listBansUsesActiveQueryWithCurrentTime() {
            ModerationService service = serviceWithPbac(false);
            ChatRoom room = room();
            OffsetDateTime nowish = OffsetDateTime.now(ZoneOffset.UTC);
            ChatBan permanent = ChatBan.create(room.getId(), TARGET_SUB, null, OWNER_SUB, null, "spam", nowish, null);
            ChatBan future = ChatBan.create(room.getId(), OTHER_SUB, null, OWNER_SUB, null, "temp", nowish,
                    nowish.plusHours(1));
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));
            // The repository filters at the query; an already-expired row is simply absent.
            ArgumentCaptor<OffsetDateTime> nowCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
            when(banRepository.findActiveByRoomId(eq(room.getId()), nowCaptor.capture()))
                    .thenReturn(Flux.just(permanent, future));

            StepVerifier.create(service.listBans(jwt(OTHER_SUB, null), ROOM_KEY))
                    .assertNext(r -> assertThat(r.bannedSubject()).isEqualTo(TARGET_SUB))
                    .assertNext(r -> assertThat(r.bannedSubject()).isEqualTo(OTHER_SUB))
                    .verifyComplete();

            // roster uses the active-only path (mirrors BanSendGuard enforcement), not the raw findByRoomId
            verify(banRepository, never()).findByRoomId(any());
            assertThat(nowCaptor.getValue()).isNotNull();
            assertThat(ChronoUnit.MINUTES.between(nowish, nowCaptor.getValue())).isZero();
        }
    }

    @Nested
    @DisplayName("username capture")
    class Usernames {

        @Test
        @DisplayName("ban stores the banned + moderator usernames on the row and response")
        void banCapturesUsernames() {
            ModerationService service = serviceWithPbac(false);
            ChatRoom room = room();
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));
            when(banRepository.deleteByRoomIdAndBannedSubject(room.getId(), TARGET_SUB))
                    .thenReturn(Mono.empty());
            ArgumentCaptor<ChatBan> captor = ArgumentCaptor.forClass(ChatBan.class);
            when(banRepository.save(captor.capture())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.ban(
                            jwt(OTHER_SUB, null), ROOM_KEY, TARGET_SUB, "spammer", "modName", "spam", null))
                    .assertNext(response -> {
                        assertThat(response.bannedUsername()).isEqualTo("spammer");
                        assertThat(response.bannedByUsername()).isEqualTo("modName");
                    })
                    .verifyComplete();

            ChatBan persisted = captor.getValue();
            assertThat(persisted.getBannedUsername()).isEqualTo("spammer");
            assertThat(persisted.getBannedByUsername()).isEqualTo("modName");
        }
    }

    @Nested
    @DisplayName("updateBanDuration re-bases an existing ban")
    class UpdateDuration {

        @Test
        @DisplayName("re-bases expiresAt off now and UPDATEs the same row")
        void reBasesExpiry() {
            ModerationService service = serviceWithPbac(false);
            ChatRoom room = room();
            ChatBan existing = ChatBan.create(room.getId(), TARGET_SUB, null, OWNER_SUB, null, "spam",
                    OffsetDateTime.now(ZoneOffset.UTC).minusHours(1),
                    OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
            // Model a DB-hydrated row: R2DBC-loaded entities have isNew=false (the
            // create() factory sets true). This is precisely what makes save() emit an
            // UPDATE rather than an INSERT — a Mockito-returned create() fixture can't
            // reproduce it, so we set it explicitly.
            existing.setNew(false);
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));
            when(banRepository.findByRoomIdAndBannedSubject(room.getId(), TARGET_SUB))
                    .thenReturn(Mono.just(existing));
            ArgumentCaptor<ChatBan> captor = ArgumentCaptor.forClass(ChatBan.class);
            when(banRepository.save(captor.capture())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            long sevenDays = 7L * 24 * 3600;
            StepVerifier.create(
                            service.updateBanDuration(jwt(OTHER_SUB, null), ROOM_KEY, TARGET_SUB, sevenDays))
                    .assertNext(response -> {
                        assertThat(response.expiresAt()).isNotNull();
                        // re-based off now (not the old +1h expiry) → ~7 days out
                        assertThat(response.expiresAt())
                                .isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusDays(6));
                    })
                    .verifyComplete();

            // the loaded row is mutated in place (UPDATE, same identity), never a fresh insert
            assertThat(captor.getValue()).isSameAs(existing);
            assertThat(captor.getValue().isNew()).isFalse();
        }

        @Test
        @DisplayName("null duration promotes the ban to permanent (expiresAt == null)")
        void promotesToPermanent() {
            ModerationService service = serviceWithPbac(false);
            ChatRoom room = room();
            ChatBan existing = ChatBan.create(room.getId(), TARGET_SUB, null, OWNER_SUB, null, "spam",
                    OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));
            when(banRepository.findByRoomIdAndBannedSubject(room.getId(), TARGET_SUB))
                    .thenReturn(Mono.just(existing));
            when(banRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(
                            service.updateBanDuration(jwt(OTHER_SUB, null), ROOM_KEY, TARGET_SUB, null))
                    .assertNext(response -> assertThat(response.expiresAt()).isNull())
                    .verifyComplete();
        }

        @Test
        @DisplayName("errors with BanNotFoundException when the subject has no ban")
        void banNotFound() {
            ModerationService service = serviceWithPbac(false);
            ChatRoom room = room();
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(room));
            when(banRepository.findByRoomIdAndBannedSubject(room.getId(), TARGET_SUB))
                    .thenReturn(Mono.empty());

            StepVerifier.create(
                            service.updateBanDuration(jwt(OTHER_SUB, null), ROOM_KEY, TARGET_SUB, 3600L))
                    .expectError(ModerationService.BanNotFoundException.class)
                    .verify();

            verify(banRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("errors with RoomNotFoundException when the room is missing")
    void roomNotFound() {
        ModerationService service = serviceWithPbac(false);
        when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.empty());

        StepVerifier.create(service.ban(jwt(OTHER_SUB, null), ROOM_KEY, TARGET_SUB, null, null, "spam", null))
                .expectError(RoomNotFoundException.class)
                .verify();

        verify(banRepository, never()).save(any());
    }
}
