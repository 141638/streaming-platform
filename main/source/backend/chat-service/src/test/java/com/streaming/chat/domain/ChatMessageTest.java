package com.streaming.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatMessage} factory methods — verifies that
 * {@code createSystem()} produces a correctly-shaped system message
 * distinct from a normal user message.
 */
@DisplayName("ChatMessage factory")
class ChatMessageTest {

    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 7, 13, 12, 0, 0, 0, ZoneOffset.UTC);

    // ── createSystem() ─────────────────────────────────────────────────────

    @Test
    @DisplayName("createSystem sets messageType to SYSTEM")
    void createSystem_setsSystemType() {
        ChatMessage msg = ChatMessage.createSystem(ROOM_ID, "Stream started", NOW);

        assertThat(msg.getMessageType()).isEqualTo(MessageType.SYSTEM);
    }

    @Test
    @DisplayName("createSystem uses sentinel author subject and username")
    void createSystem_usesSentinelIdentity() {
        ChatMessage msg = ChatMessage.createSystem(ROOM_ID, "Stream ended", NOW);

        assertThat(msg.getAuthorSubject()).isEqualTo("system");
        assertThat(msg.getAuthorUsername()).isEqualTo("System");
    }

    @Test
    @DisplayName("createSystem sets body, roomId, and createdAt correctly")
    void createSystem_setsBodyAndMetadata() {
        ChatMessage msg = ChatMessage.createSystem(ROOM_ID, "Hello world", NOW);

        assertThat(msg.getBody()).isEqualTo("Hello world");
        assertThat(msg.getRoomId()).isEqualTo(ROOM_ID);
        assertThat(msg.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("createSystem generates a non-null UUID id")
    void createSystem_generatesId() {
        ChatMessage msg = ChatMessage.createSystem(ROOM_ID, "test", NOW);

        assertThat(msg.getId()).isNotNull();
    }

    @Test
    @DisplayName("createSystem marks entity as new for R2DBC insert")
    void createSystem_isNew() {
        ChatMessage msg = ChatMessage.createSystem(ROOM_ID, "test", NOW);

        assertThat(msg.isNew()).isTrue();
    }

    @Test
    @DisplayName("createSystem does not set gift fields")
    void createSystem_giftFieldsAreNull() {
        ChatMessage msg = ChatMessage.createSystem(ROOM_ID, "test", NOW);

        assertThat(msg.getGiftAmount()).isNull();
        assertThat(msg.getGiftCurrency()).isNull();
    }

    @Test
    @DisplayName("createSystem does not set avatar URL")
    void createSystem_avatarUrlIsNull() {
        ChatMessage msg = ChatMessage.createSystem(ROOM_ID, "test", NOW);

        assertThat(msg.getAuthorAvatarUrl()).isNull();
    }

    // ── create() (existing) still works ────────────────────────────────────

    @Test
    @DisplayName("create defaults to NORMAL message type")
    void create_defaultsToNormal() {
        ChatMessage msg = ChatMessage.create(ROOM_ID, "user-sub", "Alice", "hello", NOW);

        assertThat(msg.getMessageType()).isEqualTo(MessageType.NORMAL);
        assertThat(msg.getAuthorSubject()).isEqualTo("user-sub");
        assertThat(msg.getAuthorUsername()).isEqualTo("Alice");
        assertThat(msg.getBody()).isEqualTo("hello");
    }
}
