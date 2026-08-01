package com.streaming.chat.domain;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * R2DBC entity for {@code chat.chat_message}.
 *
 * <p>Messages are always scoped to a room; the author is identified by the
 * opaque {@code sub} claim from the JWT — never from the request body.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "chat_message")
public class ChatMessage implements Persistable<UUID> {

    @Id
    private UUID id;

    /** R2DBC INSERT/UPDATE discriminator — set to {@code true} for new entities. */
    @Transient
    private boolean isNew;

    @Column("room_id")
    private UUID roomId;

    @Column("author_subject")
    private String authorSubject;

    /** Denormalized from JWT {@code attr.username} at write time. */
    @Column("author_username")
    private String authorUsername;

    /** Future: user-uploaded avatar URL. Falls back to DiceBear in the frontend. */
    @Column("author_avatar_url")
    private String authorAvatarUrl;

    private String body;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("message_type")
    private MessageType messageType;

    @Column("gift_amount")
    private java.math.BigDecimal giftAmount;

    @Column("gift_currency")
    private String giftCurrency;

    /**
     * Usernames extracted from the message body via @mention parsing.
     * Stored as a PostgreSQL {@code TEXT[]} array; empty set when no mentions.
     * Only populated when the user selects a mention from the autocomplete
     * dropdown (backend parses it regardless, but the frontend gate ensures
     * precision — see ADR-0009).
     *
     * <p>DEFERRED (Phase 6 — Notification Service):
     * For each username in this set:
     *   1. Resolve user subject from auth-service or local denormalization
     *   2. Check SSE presence
     *   3. If online → push SSE notification
     *   4. If offline → queue email digest (batch window: 15 min)
     *   5. Email deep-link: /stream/{roomKey}?scrollTo={messageId}
     */
    @Column("mentions")
    private String[] mentions = new String[0];

    /**
     * Client-provided idempotency key.
     *
     * <p>WebSocket send frames carry {@code clientId} ({@code client-{ts}-{counter}});
     * REST requests forward the {@code Idempotency-Key} header. Stored on the entity
     * so the database unique constraint ({@code uq_chat_message_client_id}) rejects
     * duplicate sends at the persistence layer.
     *
     * <p>Nullable — historical rows (pre-V7) and system messages without a Kafka
     * event identifier skip idempotency with a null value. The partial unique index
     * ({@code WHERE client_id IS NOT NULL}) ensures nulls coexist freely.
     *
     * <p>See ADR-0011: Message Idempotency via client_id Unique Constraint.
     */
    @Column("client_id")
    private String clientId;

    // -- factory -----------------------------------------------------------

    public static ChatMessage create(UUID roomId, String authorSubject, String authorUsername, String body, OffsetDateTime now) {
        return create(roomId, authorSubject, authorUsername, body, now, new String[0], null);
    }

    public static ChatMessage create(UUID roomId, String authorSubject, String authorUsername, String body, OffsetDateTime now, String[] mentions) {
        return create(roomId, authorSubject, authorUsername, body, now, mentions, null);
    }

    /**
     * Full factory with optional client-provided idempotency key.
     *
     * @param clientId the idempotency key from the WebSocket frame or REST header;
     *                 null for legacy callers or non-idempotent sends
     */
    public static ChatMessage create(UUID roomId, String authorSubject, String authorUsername, String body,
                                      OffsetDateTime now, String[] mentions, @jakarta.annotation.Nullable String clientId) {
        ChatMessage msg = new ChatMessage();
        msg.setId(UUID.randomUUID());
        msg.setNew(true);
        msg.setRoomId(roomId);
        msg.setAuthorSubject(authorSubject);
        msg.setAuthorUsername(authorUsername);
        msg.setBody(body);
        msg.setCreatedAt(now);
        msg.setMessageType(MessageType.NORMAL);
        msg.setMentions(mentions);
        msg.setClientId(clientId);
        return msg;
    }

    /**
     * Factory for system messages — automated announcements (stream started, stream
     * ended, etc.) that are not authored by any user. Uses sentinel identity values
     * ({@code authorSubject = "system"}, {@code authorUsername = "System"}) so the
     * frontend can render them distinctly.
     */
    public static ChatMessage createSystem(UUID roomId, String body, OffsetDateTime now) {
        ChatMessage msg = new ChatMessage();
        msg.setId(UUID.randomUUID());
        msg.setNew(true);
        msg.setRoomId(roomId);
        msg.setAuthorSubject("system");
        msg.setAuthorUsername("System");
        msg.setBody(body);
        msg.setCreatedAt(now);
        msg.setMessageType(MessageType.SYSTEM);
        msg.setMentions(new String[0]);
        return msg;
    }

    // -- domain ------------------------------------------------------------

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
