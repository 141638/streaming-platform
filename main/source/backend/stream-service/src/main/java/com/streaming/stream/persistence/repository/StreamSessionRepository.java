package com.streaming.stream.persistence.repository;

import com.streaming.stream.persistence.entity.StreamSessionEntity;
import com.streaming.stream.persistence.entity.StreamStatus;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface StreamSessionRepository extends ReactiveCrudRepository<StreamSessionEntity, UUID> {

    Flux<StreamSessionEntity> findAllByBroadcasterSubject(String broadcasterSubject);

    Mono<Boolean> existsByBroadcasterSubjectAndStatus(String broadcasterSubject, StreamStatus status);

    Mono<StreamSessionEntity> findByStreamKeyHash(String streamKeyHash);

    /**
     * Public-facing sessions for the channel home rail — only LIVE and ENDED.
     * DRAFT, SCHEDULED, and CANCELLED are excluded (creator-only visibility).
     */
    @Query("""
            SELECT * FROM stream.stream_session
            WHERE broadcaster_username = :username
              AND status IN ('live', 'ended')
            ORDER BY created_at DESC
            LIMIT :limit
            """)
    Flux<StreamSessionEntity> findPublicSessionsByUsername(String username, int limit);

    Flux<StreamSessionEntity> findAllByBroadcasterUsernameOrderByCreatedAtDesc(String broadcasterUsername);

    /**
     * Newest single session for a channel — used by the identity endpoint
     * to resolve verified status without fetching all sessions.
     */
    Mono<StreamSessionEntity> findFirstByBroadcasterUsernameOrderByCreatedAtDesc(String broadcasterUsername);

    /**
     * Find live streams ordered by most recently started, capped for the
     * browse/discovery page.
     */
    @Query("""
            SELECT * FROM stream.stream_session
            WHERE status = 'live'
            ORDER BY started_at DESC
            LIMIT :limit
            """)
    Flux<StreamSessionEntity> findLiveStreams(int limit);
}
