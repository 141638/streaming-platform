package com.streaming.stream.persistence.entity;

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

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "stream_watch_history")
public class WatchHistoryEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew;

    @Column("user_subject")
    private String userSubject;

    @Column("stream_id")
    private UUID streamId;

    @Column("watched_at")
    private OffsetDateTime watchedAt;

    @Column("watch_duration_seconds")
    private Long watchDurationSeconds;
}
