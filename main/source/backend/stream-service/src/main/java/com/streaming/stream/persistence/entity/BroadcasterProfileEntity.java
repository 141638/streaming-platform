package com.streaming.stream.persistence.entity;

import com.streaming.stream.api.dto.SocialLink;
import java.time.OffsetDateTime;
import java.util.List;
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
 * Per-broadcaster channel profile metadata — bio, social links, etc.
 *
 * <p>Keyed by {@code username} (not subject) because the channel page is
 * addressed by the public handle. This table is deliberately separate from
 * {@code stream_session} so the bio persists even when a broadcaster has
 * no streams yet.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "broadcaster_profile")
public class BroadcasterProfileEntity implements Persistable<String> {

    @Id
    private String username;

    @Transient
    private boolean isNew;

    private String bio;

    /**
     * JSONB array of social link objects, mapped via
     * {@link com.streaming.stream.config.SocialLinksReadingConverter} /
     * {@link com.streaming.stream.config.SocialLinksWritingConverter}.
     */
    @Column("social_links")
    private List<SocialLink> socialLinks;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    // ── Persistable contract ──────────────────────────────────────────────

    @Override
    public String getId() {
        return username;
    }

    // ── Factory ────────────────────────────────────────────────────────────

    public static BroadcasterProfileEntity create(String username, String bio) {
        BroadcasterProfileEntity entity = new BroadcasterProfileEntity();
        entity.setNew(true);
        entity.setUsername(username);
        entity.setBio(bio);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        return entity;
    }
}
