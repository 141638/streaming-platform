package com.streaming.chat.application;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.streaming.chat.application.BanSendGuard.UserBannedException;
import com.streaming.chat.domain.ChatBan;
import com.streaming.chat.domain.ChatRoom;
import com.streaming.chat.infrastructure.persistence.ReactiveChatBanRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("BanSendGuard")
@ExtendWith(MockitoExtension.class)
class BanSendGuardTest {

    private static final String ROOM_KEY = "room-abc";
    private static final String OWNER_SUB = "e8f9a1b2-3c4d-5e6f-7a8b-9c0d1e2f3a4b";
    private static final String SENDER_SUB = "f9a1b2c3-4d5e-6f7a-8b9c-0d1e2f3a4b5c";

    @Mock
    private ReactiveChatBanRepository banRepository;

    private BanSendGuard guard;
    private ChatRoom room;

    @BeforeEach
    void setUp() {
        guard = new BanSendGuard(banRepository);
        room = ChatRoom.create(ROOM_KEY, OWNER_SUB, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private ChatBan ban(OffsetDateTime expiresAt) {
        return ChatBan.create(
                room.getId(), SENDER_SUB, null, OWNER_SUB, null, "spam",
                OffsetDateTime.now(ZoneOffset.UTC), expiresAt);
    }

    @Test
    @DisplayName("errors with UserBannedException when an active permanent ban exists")
    void errorsWhenPermanentBan() {
        when(banRepository.findByRoomIdAndBannedSubject(eq(room.getId()), eq(SENDER_SUB)))
                .thenReturn(Mono.just(ban(null)));

        StepVerifier.create(guard.check(room, SENDER_SUB))
                .expectError(UserBannedException.class)
                .verify();
    }

    @Test
    @DisplayName("errors when the ban expires in the future")
    void errorsWhenFutureExpiry() {
        when(banRepository.findByRoomIdAndBannedSubject(eq(room.getId()), eq(SENDER_SUB)))
                .thenReturn(Mono.just(ban(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1))));

        StepVerifier.create(guard.check(room, SENDER_SUB))
                .expectError(UserBannedException.class)
                .verify();
    }

    @Test
    @DisplayName("passes when the ban has already expired")
    void passesWhenExpired() {
        when(banRepository.findByRoomIdAndBannedSubject(eq(room.getId()), eq(SENDER_SUB)))
                .thenReturn(Mono.just(ban(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1))));

        StepVerifier.create(guard.check(room, SENDER_SUB))
                .verifyComplete();
    }

    @Test
    @DisplayName("passes when no ban exists for the sender")
    void passesWhenNoBan() {
        when(banRepository.findByRoomIdAndBannedSubject(eq(room.getId()), eq(SENDER_SUB)))
                .thenReturn(Mono.empty());

        StepVerifier.create(guard.check(room, SENDER_SUB))
                .verifyComplete();
    }
}
