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
 * R2DBC entity for {@code chat.chat_ban}.
 *
 * <p>A per-room ban of a user (by JWT {@code sub}). Enforced on the send path by
 * {@code BanSendGuard} before a message is persisted. A {@code null}
 * {@link #expiresAt} means a permanent ban; otherwise the ban lapses once the
 * timestamp passes.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "chat_ban")
public class ChatBan implements Persistable<UUID> {

    @Id
    private UUID id;

    /** R2DBC INSERT/UPDATE discriminator — set to {@code true} for new entities. */
    @Transient
    private boolean isNew;

    @Column("room_id")
    private UUID roomId;

    @Column("banned_subject")
    private String bannedSubject;

    @Column("banned_by_subject")
    private String bannedBySubject;

    private String reason;

    @Column("created_at")
    private OffsetDateTime createdAt;

    /** {@code null} = permanent ban. */
    @Column("expires_at")
    private OffsetDateTime expiresAt;

    // -- factory -----------------------------------------------------------

    public static ChatBan create(
            UUID roomId,
            String bannedSubject,
            String bannedBySubject,
            String reason,
            OffsetDateTime now,
            OffsetDateTime expiresAt) {
        ChatBan ban = new ChatBan();
        ban.setId(UUID.randomUUID());
        ban.setNew(true);
        ban.setRoomId(roomId);
        ban.setBannedSubject(bannedSubject);
        ban.setBannedBySubject(bannedBySubject);
        ban.setReason(reason);
        ban.setCreatedAt(now);
        ban.setExpiresAt(expiresAt);
        return ban;
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
