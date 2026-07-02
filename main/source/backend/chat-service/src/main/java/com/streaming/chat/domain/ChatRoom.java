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
 * R2DBC entity for {@code chat.chat_room}.
 *
 * <p>Each room is keyed by an {@code external_key} that maps 1:1 to a
 * stream session external key from the stream service.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "chat_room")
public class ChatRoom implements Persistable<UUID> {

    @Id
    private UUID id;

    /** R2DBC INSERT/UPDATE discriminator — set to {@code true} for new entities. */
    @Transient
    private boolean isNew;

    @Column("external_key")
    private String externalKey;

    private RoomStatus status;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("archived_at")
    private OffsetDateTime archivedAt;

    // -- factory -----------------------------------------------------------

    public static ChatRoom create(String externalKey, OffsetDateTime now) {
        ChatRoom room = new ChatRoom();
        room.setId(UUID.randomUUID());
        room.setNew(true);
        room.setExternalKey(externalKey);
        room.setStatus(RoomStatus.ACTIVE);
        room.setCreatedAt(now);
        return room;
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

    public boolean isActive() {
        return status == RoomStatus.ACTIVE;
    }

    public void archive(OffsetDateTime when) {
        this.status = RoomStatus.ARCHIVED;
        this.archivedAt = when;
    }
}
