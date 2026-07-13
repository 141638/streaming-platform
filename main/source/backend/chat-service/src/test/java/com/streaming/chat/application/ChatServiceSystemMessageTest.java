package com.streaming.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.streaming.chat.api.dto.MessageResponse;
import com.streaming.chat.config.ChatPbacProperties;
import com.streaming.chat.domain.ChatMessage;
import com.streaming.chat.domain.ChatRoom;
import com.streaming.chat.domain.RoomStatus;
import com.streaming.chat.infrastructure.cache.RedisMessageCache;
import com.streaming.chat.infrastructure.persistence.ReactiveChatBanRepository;
import com.streaming.chat.infrastructure.persistence.ReactiveChatMessageRepository;
import com.streaming.chat.infrastructure.persistence.ReactiveChatRoomRepository;
import com.streaming.chat.security.ChatAuthorization;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link ChatService#sendSystemMessage} — verifies that system
 * messages bypass auth/guard but still validate room existence and activity.
 *
 * <p>Uses Mockito stubs for repositories and cache; no JWT or PBAC setup needed.
 */
@DisplayName("ChatService.sendSystemMessage")
@ExtendWith(MockitoExtension.class)
class ChatServiceSystemMessageTest {

    private static final String ROOM_KEY = "system-test-room";
    private static final UUID ROOM_ID = UUID.randomUUID();

    @Mock
    private ReactiveChatRoomRepository roomRepository;
    @Mock
    private ReactiveChatMessageRepository messageRepository;
    @Mock
    private RedisMessageCache cache;
    @Mock
    private ReactiveChatBanRepository banRepository;

    private final SendGuard noOpGuard = (room, authorSubject) -> Mono.empty();

    private ChatAuthorization chatAuthorization;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        // PBAC disabled — sendSystemMessage bypasses it anyway, but the
        // ChatService constructor requires a real ChatAuthorization instance.
        chatAuthorization = new ChatAuthorization(new ChatPbacProperties(false));
        chatService = new ChatService(
                roomRepository, messageRepository, cache, noOpGuard, chatAuthorization, banRepository);
    }

    private static ChatRoom activeRoom() {
        ChatRoom room = ChatRoom.create(ROOM_KEY, "owner-sub", OffsetDateTime.now(ZoneOffset.UTC));
        room.setId(ROOM_ID);
        room.setNew(false);
        return room;
    }

    private static ChatRoom archivedRoom() {
        ChatRoom room = activeRoom();
        room.setStatus(RoomStatus.ARCHIVED);
        return room;
    }

    // ── Happy path ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("with an active room")
    class WithActiveRoom {

        @BeforeEach
        void stubActiveRoom() {
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(activeRoom()));
            // messageRepository.save() echoes back the saved entity
            when(messageRepository.save(any(ChatMessage.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            // cache.addToRecent returns true (success)
            when(cache.addToRecent(any(String.class), any(MessageResponse.class)))
                    .thenReturn(Mono.just(true));
            // These are never called by sendSystemMessage but Mockito strict mode
            // verifies all stubs — lenient prevents UnnecessaryStubbingException.
            lenient().when(banRepository.findByRoomIdAndBannedSubject(any(), any()))
                    .thenReturn(Mono.empty());
        }

        @Test
        @DisplayName("returns a MessageResponse with type SYSTEM")
        void postsSystemMessage() {
            Mono<MessageResponse> result = chatService.sendSystemMessage(ROOM_KEY, "Stream started");

            StepVerifier.create(result)
                    .assertNext(msg -> {
                        assertThat(msg.roomKey()).isEqualTo(ROOM_KEY);
                        assertThat(msg.body()).isEqualTo("Stream started");
                        assertThat(msg.messageType()).isEqualTo("SYSTEM");
                        assertThat(msg.authorSubject()).isEqualTo("system");
                        assertThat(msg.authorUsername()).isEqualTo("System");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("writes to the cache after persisting")
        void writesToCache() {
            StepVerifier.create(chatService.sendSystemMessage(ROOM_KEY, "test"))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(cache).addToRecent(any(String.class), any(MessageResponse.class));
        }

        @Test
        @DisplayName("does NOT invoke PBAC authorization or ban guard")
        void bypassesAuthAndGuard() {
            StepVerifier.create(chatService.sendSystemMessage(ROOM_KEY, "test"))
                    .expectNextCount(1)
                    .verifyComplete();

            // Ban repository should never be queried — sendSystemMessage skips the guard
            verifyNoInteractions(banRepository);
        }
    }

    // ── Error paths ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("with a missing room")
    class WithMissingRoom {

        @BeforeEach
        void stubMissingRoom() {
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.empty());
        }

        @Test
        @DisplayName("errors with RoomNotFoundException")
        void throwsRoomNotFound() {
            StepVerifier.create(chatService.sendSystemMessage(ROOM_KEY, "test"))
                    .verifyError(ChatService.RoomNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("with an archived room")
    class WithArchivedRoom {

        @BeforeEach
        void stubArchivedRoom() {
            when(roomRepository.findByExternalKey(ROOM_KEY)).thenReturn(Mono.just(archivedRoom()));
        }

        @Test
        @DisplayName("errors with RoomArchivedException")
        void throwsRoomArchived() {
            StepVerifier.create(chatService.sendSystemMessage(ROOM_KEY, "test"))
                    .verifyError(ChatService.RoomArchivedException.class);
        }
    }

    // ── parseMentions() ───────────────────────────────────────────────────

    @Nested
    @DisplayName("parseMentions")
    class ParseMentions {

        @Test
        @DisplayName("extracts single @mention")
        void extractsSingleMention() {
            assertThat(ChatService.parseMentions("hello @alice"))
                    .containsExactly("alice");
        }

        @Test
        @DisplayName("extracts multiple @mentions")
        void extractsMultipleMentions() {
            assertThat(ChatService.parseMentions("@alice @bob check this out"))
                    .containsExactlyInAnyOrder("alice", "bob");
        }

        @Test
        @DisplayName("returns empty set when no @mention")
        void returnsEmptyWhenNoMention() {
            assertThat(ChatService.parseMentions("hello world"))
                    .isEmpty();
        }

        @Test
        @DisplayName("returns empty set for null body")
        void returnsEmptyForNull() {
            assertThat(ChatService.parseMentions(null))
                    .isEmpty();
        }

        @Test
        @DisplayName("returns empty set for blank body")
        void returnsEmptyForBlank() {
            assertThat(ChatService.parseMentions("   "))
                    .isEmpty();
        }

        @Test
        @DisplayName("does not match @ in middle of word")
        void ignoresMidWordAt() {
            assertThat(ChatService.parseMentions("email@example.com @valid"))
                    .containsExactly("valid");
        }

        @Test
        @DisplayName("deduplicates repeated mentions")
        void deduplicatesRepeated() {
            assertThat(ChatService.parseMentions("@alice @alice @bob"))
                    .containsExactlyInAnyOrder("alice", "bob");
        }
    }
}
