package com.streaming.stream.service;

import com.streaming.common.crypto.HashUtils;
import com.streaming.stream.api.dto.CategoryResponse;
import com.streaming.stream.api.dto.CreateStreamRequest;
import com.streaming.stream.api.dto.PublishKeyResponse;

import com.streaming.stream.api.dto.StreamResponse;
import com.streaming.stream.api.dto.StreamSummaryResponse;
import com.streaming.stream.api.dto.UpdateStreamRequest;
import com.streaming.stream.messaging.StreamEvent;
import com.streaming.stream.messaging.StreamEventPublisher;
import com.streaming.stream.persistence.entity.StreamSessionEntity;
import com.streaming.stream.persistence.entity.StreamStatus;
import com.streaming.stream.persistence.repository.StreamCategoryRepository;
import com.streaming.stream.persistence.repository.StreamSessionRepository;
import com.streaming.stream.security.AuthAction;
import com.streaming.stream.security.AuthResourceDomain;
import com.streaming.stream.security.AuthResourceKind;
import com.streaming.stream.security.RequiredAuthority;
import com.streaming.stream.security.StreamAuthorization;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class StreamService {

    private static final Logger log = LoggerFactory.getLogger(StreamService.class);

    private final StreamSessionRepository repository;
    private final StreamCategoryRepository categoryRepository;
    private final StreamAuthorization authorization;
    private final StreamEventPublisher eventPublisher;

    // ── Create ──────────────────────────────────────────────────────────────

    /**
     * Create a new stream. If {@code scheduledAt} is provided, the stream is
     * created in {@code SCHEDULED} status; otherwise it starts as {@code DRAFT}.
     */
    public Mono<StreamResponse> createStream(CreateStreamRequest request, Jwt jwt) {
        final String sub = jwt.getSubject();
        final UUID id = UUID.randomUUID();
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return authorization.requireAccess(jwt, new RequiredAuthority(
                        AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                        AuthAction.CREATE, sub))
                .then(Mono.defer(() -> {
                    StreamSessionEntity entity = buildEntity(id, sub, request, now);
                    if (request.scheduledAt() != null) {
                        entity.setStatus(StreamStatus.SCHEDULED);
                        entity.setScheduledAt(request.scheduledAt());
                    }
                    if (request.categoryId() != null) {
                        return categoryRepository.findById(request.categoryId())
                                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                                        "Category not found: id=" + request.categoryId())))
                                .map(cat -> {
                                    entity.setCategoryId(cat.getId());
                                    entity.setCategory(cat.getName());
                                    return entity;
                                })
                                .flatMap(repository::save);
                    }
                    if (request.category() != null && !request.category().isBlank()) {
                        entity.setCategory(request.category());
                    }
                    return repository.save(entity);
                }))
                .doOnSuccess(saved -> {
                    StreamEvent event = request.scheduledAt() != null
                            ? StreamEvent.scheduled(saved.getId(), sub)
                            : StreamEvent.created(saved.getId(), sub);
                    eventPublisher.publish(event).subscribe();
                    log.info("Stream created: id={} status={} subject={}",
                            saved.getId(), saved.getStatus().wireValue(), sub);
                })
                .map(StreamResponse::from);
    }

    // ── Read ────────────────────────────────────────────────────────────────

    public Flux<StreamSummaryResponse> listMyStreams(String broadcasterSubject) {
        return repository.findAllByBroadcasterSubject(broadcasterSubject)
                .map(StreamSummaryResponse::from);
    }

    public Mono<StreamResponse> getStream(UUID id, Jwt jwt) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.READ, entity.getBroadcasterSubject()))
                        .onErrorMap(StreamAuthorization.StreamAccessDeniedException.class,
                                e -> new StreamNotFoundException(id))
                        .thenReturn(entity))
                .map(StreamResponse::from);
    }

    // ── Update (metadata only) ──────────────────────────────────────────────

    public Mono<StreamResponse> updateStream(UUID id, UpdateStreamRequest request, Jwt jwt) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.UPDATE, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> applyMetadataUpdates(entity, request))
                .flatMap(repository::save)
                .doOnSuccess(saved -> log.info("Stream updated: id={}", saved.getId()))
                .map(StreamResponse::from)
                .onErrorMap(OptimisticLockingFailureException.class,
                        ex -> new StreamConflictException("Stream was modified by another operation. Reload and try again."));
    }

    // ── Lifecycle transitions ───────────────────────────────────────────────

    /**
     * Transition a stream to LIVE. Enforces the one-live-stream-per-broadcaster
     * rule before allowing the transition.
     */
    public Mono<StreamResponse> startStream(UUID id, Jwt jwt) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.LIFECYCLE, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity ->
                        repository.existsByBroadcasterSubjectAndStatus(
                                        entity.getBroadcasterSubject(), StreamStatus.LIVE)
                                .flatMap(hasLive -> {
                                    if (Boolean.TRUE.equals(hasLive)) {
                                        return Mono.error(new StreamAlreadyLiveException(
                                                entity.getBroadcasterSubject()));
                                    }
                                    entity.goLive();
                                    return repository.save(entity);
                                })
                )
                .doOnSuccess(saved -> {
                    eventPublisher.publish(StreamEvent.started(saved.getId(),
                            saved.getBroadcasterSubject())).subscribe();
                    log.info("Stream started: id={}", saved.getId());
                })
                .map(StreamResponse::from)
                .onErrorMap(OptimisticLockingFailureException.class,
                        ex -> new StreamConflictException("Stream was modified by another operation. Reload and try again."));
    }

    /** Transition a stream to ENDED. */
    public Mono<StreamResponse> endStream(UUID id, Jwt jwt) {
        return lifecycleTransition(id, jwt, StreamSessionEntity::end,
                entity -> StreamEvent.ended(entity.getId(), entity.getBroadcasterSubject()),
                "ended");
    }

    /** Transition a stream to CANCELLED. Only allowed from DRAFT or SCHEDULED. */
    public Mono<StreamResponse> cancelStream(UUID id, Jwt jwt) {
        return lifecycleTransition(id, jwt, StreamSessionEntity::cancel,
                entity -> StreamEvent.cancelled(entity.getId(), entity.getBroadcasterSubject()),
                "cancelled");
    }

    // ── Delete (soft-delete via cancel) ─────────────────────────────────────

    public Mono<Void> deleteStream(UUID id, Jwt jwt) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.DELETE, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> {
                    entity.cancel();
                    return repository.save(entity);
                })
                .doOnSuccess(saved -> {
                    eventPublisher.publish(StreamEvent.cancelled(saved.getId(),
                            saved.getBroadcasterSubject())).subscribe();
                    log.info("Stream cancelled via delete: id={}", saved.getId());
                })
                .then();
    }

    // ── Publish key ─────────────────────────────────────────────────────────

    public Mono<PublishKeyResponse> issuePublishKey(UUID streamId, Jwt jwt) {
        return repository.findById(streamId)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(streamId)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.ISSUE_KEY, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .map(entity -> {
                    String rawKey = UUID.randomUUID().toString().replace("-", "");
                    entity.setStreamKeyHash(HashUtils.sha256Hex(rawKey));
                    entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    return new PendingKey(entity, rawKey);
                })
                .flatMap(pending -> repository.save(pending.entity)
                        .map(saved -> new PendingKey(saved, pending.rawKey)))
                .map(pending -> new PublishKeyResponse(
                        pending.entity.getId(),
                        "sk_" + pending.rawKey,
                        "rtmp://localhost:1935/live/",
                        OffsetDateTime.now(ZoneOffset.UTC).plusHours(24)
                ))
                .doOnSuccess(resp -> log.info("Publish key issued for stream: id={}", streamId));
    }

    public Mono<PublishKeyResponse> getPublishKey(UUID streamId, Jwt jwt) {
        return repository.findById(streamId)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(streamId)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.READ, entity.getBroadcasterSubject()))
                        .onErrorMap(StreamAuthorization.StreamAccessDeniedException.class,
                                e -> new StreamNotFoundException(streamId))
                        .thenReturn(entity))
                .map(entity -> new PublishKeyResponse(
                        entity.getId(),
                        "sk_****",
                        "rtmp://localhost:1935/live/",
                        null
                ));
    }

    // ── Categories ──────────────────────────────────────────────────────────

    public Flux<CategoryResponse> listCategories() {
        return categoryRepository.findAll()
                .sort((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
                .map(CategoryResponse::from);
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private StreamSessionEntity buildEntity(UUID id, String sub, CreateStreamRequest request,
                                            OffsetDateTime now) {
        StreamSessionEntity entity = new StreamSessionEntity();
        entity.setId(id);
        entity.setNew(true);
        entity.setBroadcasterSubject(sub);
        entity.setTitle(request.title());
        entity.setDescription(request.description());
        entity.setMaxViewers(request.maxViewers());
        entity.setStatus(StreamStatus.DRAFT);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        if (request.tags() != null && !request.tags().isEmpty()) {
            entity.setTags(request.tags().toArray(String[]::new));
        }

        return entity;
    }

    private Mono<StreamSessionEntity> applyMetadataUpdates(StreamSessionEntity entity,
                                                           UpdateStreamRequest request) {
        if (request.title() != null) {
            entity.setTitle(request.title());
        }
        if (request.description() != null) {
            entity.setDescription(request.description());
        }
        if (request.maxViewers() != null) {
            entity.setMaxViewers(request.maxViewers());
        }

        if (request.categoryId() != null) {
            return categoryRepository.findById(request.categoryId())
                    .switchIfEmpty(Mono.error(new IllegalArgumentException(
                            "Category not found: id=" + request.categoryId())))
                    .map(category -> {
                        entity.setCategoryId(category.getId());
                        entity.setCategory(category.getName());
                        return entity;
                    });
        } else if (request.category() != null) {
            entity.setCategory(request.category());
        }

        if (request.tags() != null) {
            entity.setTags(request.tags().toArray(String[]::new));
        }

        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return Mono.just(entity);
    }

    /**
     * Generic lifecycle transition helper for simple status changes
     * that don't need extra validation beyond the PBAC check.
     */
    private Mono<StreamResponse> lifecycleTransition(
            UUID id, Jwt jwt,
            java.util.function.Consumer<StreamSessionEntity> transition,
            java.util.function.Function<StreamSessionEntity, StreamEvent> eventFactory,
            String actionLabel) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.LIFECYCLE, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> {
                    transition.accept(entity);
                    return repository.save(entity);
                })
                .doOnSuccess(saved -> {
                    eventPublisher.publish(eventFactory.apply(saved)).subscribe();
                    log.info("Stream {}: id={}", actionLabel, saved.getId());
                })
                .map(StreamResponse::from)
                .onErrorMap(OptimisticLockingFailureException.class,
                        ex -> new StreamConflictException("Stream was modified by another operation. Reload and try again."));
    }

    // ── Inner types ─────────────────────────────────────────────────────────

    private record PendingKey(StreamSessionEntity entity, String rawKey) {}

    // ── Exceptions ──────────────────────────────────────────────────────────

    public static class StreamNotFoundException extends RuntimeException {
        public StreamNotFoundException(UUID id) {
            super("Stream not found: id=" + id);
        }
    }

    public static class StreamAlreadyLiveException extends RuntimeException {
        public StreamAlreadyLiveException(String broadcasterSubject) {
            super("Broadcaster already has a live stream: " + broadcasterSubject);
        }
    }

    public static class StreamConflictException extends RuntimeException {
        public StreamConflictException(String message) {
            super(message);
        }
    }
}
