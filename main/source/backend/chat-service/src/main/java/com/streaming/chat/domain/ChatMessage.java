package com.streaming.chat.domain;

import java.time.OffsetDateTime;
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

    private String body;

    @Column("created_at")
    private OffsetDateTime createdAt;

    // -- factory -----------------------------------------------------------

    public static ChatMessage create(UUID roomId, String authorSubject, String body, OffsetDateTime now) {
        ChatMessage msg = new ChatMessage();
        msg.setId(UUID.randomUUID());
        msg.setNew(true);
        msg.setRoomId(roomId);
        msg.setAuthorSubject(authorSubject);
        msg.setBody(body);
        msg.setCreatedAt(now);
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
