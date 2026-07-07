package com.streaming.stream.service;

import com.streaming.common.crypto.HashUtils;
import com.streaming.stream.api.dto.CategoryResponse;
import com.streaming.stream.api.dto.CreateStreamRequest;
import com.streaming.stream.api.dto.PublishKeyResponse;
import com.streaming.stream.api.dto.StreamResponse;
import com.streaming.stream.api.dto.StreamSummaryResponse;
import com.streaming.stream.api.dto.UpdateStreamRequest;
import com.streaming.stream.config.PublishTokenProperties;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class StreamService {

    private static final Logger log = LoggerFactory.getLogger(StreamService.class);

    private final StreamSessionRepository repository;
    private final StreamCategoryRepository categoryRepository;
    private final StreamAuthorization authorization;
    private final StreamEventPublisher eventPublisher;
    private final PublishTokenService publishTokenService;
    private final PublishTokenProperties publishTokenProps;

    public StreamService(StreamSessionRepository repository,
                         StreamCategoryRepository categoryRepository,
                         StreamAuthorization authorization,
                         StreamEventPublisher eventPublisher,
                         PublishTokenService publishTokenService,
                         PublishTokenProperties publishTokenProps) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.authorization = authorization;
        this.eventPublisher = eventPublisher;
        this.publishTokenService = publishTokenService;
        this.publishTokenProps = publishTokenProps;
    }

    // ── Create ──────────────────────────────────────────────────────────────

    /**
     * Create a new stream. If {@code scheduledAt} is provided, the stream is
     * created in {@code SCHEDULED} status with no publish key. Otherwise it
     * starts as {@code DRAFT} with a publish key (srsName + JWT token).
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
                    } else {
                        // DRAFT: generate srsName and store its hash
                        String srsName = newSrsName();
                        entity.setStreamKeyHash(HashUtils.sha256Hex(srsName));
                        entity.setSrsName(srsName);
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

    /**
     * Issue a fresh publish key. DRAFT: full rotation (new srsName + JWT).
     * LIVE: JWT-only rotation (same srsName, new JWT — protects HLS playback).
     */
    public Mono<PublishKeyResponse> issuePublishKey(UUID streamId, Jwt jwt) {
        return repository.findById(streamId)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(streamId)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.ISSUE_KEY, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> {
                    if (entity.getStatus() != StreamStatus.DRAFT
                            && entity.getStatus() != StreamStatus.LIVE) {
                        return Mono.error(new IllegalStateException(
                                "Publish key can only be issued for DRAFT or LIVE streams"));
                    }

                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                    boolean isLive = entity.getStatus() == StreamStatus.LIVE;

                    // LIVE: keep srsName stable so HLS playback isn't interrupted
                    String srsName = isLive ? entity.getSrsName() : newSrsName();
                    String token = publishTokenService.issueToken(
                            streamId, srsName, entity.getBroadcasterSubject());

                    entity.setSrsName(srsName);
                    entity.setStreamKeyHash(HashUtils.sha256Hex(srsName));
                    entity.setUpdatedAt(now);

                    return repository.save(entity)
                            .map(saved -> buildPublishKeyResponse(
                                    saved, srsName, token, now));
                })
                .doOnSuccess(resp -> log.info(
                        "Publish key issued for stream: id={}", streamId));
    }

    /** View an existing publish key (token masked). */
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
                .map(entity -> {
                    if (entity.getSrsName() == null) {
                        throw new NoPublishKeyException(streamId);
                    }
                    return buildPublishKeyResponse(entity, entity.getSrsName(),
                            "****", null);
                });
    }

    // ── Go-live from SCHEDULED ──────────────────────────────────────────────

    /**
     * Activate a scheduled stream: SCHEDULED → DRAFT with a fresh publish key.
     * This is the only way to move out of SCHEDULED status.
     */
    public Mono<PublishKeyResponse> goLiveFromSchedule(UUID streamId, Jwt jwt) {
        return repository.findById(streamId)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(streamId)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.LIFECYCLE, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> {
                    if (entity.getStatus() != StreamStatus.SCHEDULED) {
                        return Mono.error(new IllegalStateException(
                                "Only SCHEDULED streams can go live. Current: "
                                        + entity.getStatus().wireValue()));
                    }

                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                    String srsName = newSrsName();
                    String token = publishTokenService.issueToken(
                            streamId, srsName, entity.getBroadcasterSubject());

                    entity.setSrsName(srsName);
                    entity.setStreamKeyHash(HashUtils.sha256Hex(srsName));
                    entity.setStatus(StreamStatus.DRAFT);
                    entity.setScheduledAt(null);
                    entity.setUpdatedAt(now);

                    return repository.save(entity)
                            .map(saved -> buildPublishKeyResponse(
                                    saved, srsName, token, now));
                })
                .doOnSuccess(resp -> log.info(
                        "Stream activated from SCHEDULED: id={}", streamId));
    }

    // ── Webhook handlers (internal — no PBAC, auth via publish token) ───────

    /**
     * Handle an SRS {@code on_publish} webhook. If the stream is DRAFT,
     * transitions it to LIVE. If already LIVE, allows the reconnection
     * (Sol3: expiry is not checked for LIVE reconnects).
     */
    public Mono<Void> handlePublish(String srsName, String rawToken) {
        String hash = HashUtils.sha256Hex(srsName);
        return repository.findByStreamKeyHash(hash)
                .switchIfEmpty(Mono.error(new InvalidPublishTokenException(
                        "No stream found for srsName")))
                .flatMap(entity -> {
                    StreamStatus status = entity.getStatus();
                    if (status == StreamStatus.ENDED
                            || status == StreamStatus.CANCELLED) {
                        return Mono.error(new InvalidPublishTokenException(
                                "Stream is " + status.wireValue()));
                    }

                    return publishTokenService.validateForPublish(
                                    rawToken, srsName, status)
                            .then(Mono.defer(() -> {
                                if (status == StreamStatus.DRAFT) {
                                    return repository
                                            .existsByBroadcasterSubjectAndStatus(
                                                    entity.getBroadcasterSubject(),
                                                    StreamStatus.LIVE)
                                            .flatMap(hasLive -> {
                                                if (Boolean.TRUE.equals(hasLive)) {
                                                    return Mono.error(
                                                            new StreamAlreadyLiveException(
                                                                    entity.getBroadcasterSubject()));
                                                }
                                                entity.goLive();
                                                return repository.save(entity)
                                                        .doOnSuccess(saved -> {
                                                            eventPublisher.publish(
                                                                    StreamEvent.started(
                                                                            saved.getId(),
                                                                            saved.getBroadcasterSubject()))
                                                                    .subscribe();
                                                            log.info("Stream started via webhook: id={}",
                                                                    saved.getId());
                                                        });
                                            });
                                }
                                // LIVE → reconnect, no state change
                                log.info("Stream reconnect via webhook: id={}",
                                        entity.getId());
                                return Mono.<StreamSessionEntity>just(entity);
                            }));
                })
                .then();
    }

    /** Handle an SRS {@code on_unpublish} webhook. Ends the stream if LIVE. */
    public Mono<Void> handleUnpublish(String srsName) {
        String hash = HashUtils.sha256Hex(srsName);
        return repository.findByStreamKeyHash(hash)
                .flatMap(entity -> {
                    if (entity.getStatus() != StreamStatus.LIVE) {
                        log.info("on_unpublish for non-LIVE stream: id={} status={}",
                                entity.getId(), entity.getStatus().wireValue());
                        return Mono.<StreamSessionEntity>just(entity);
                    }
                    entity.end();
                    return repository.save(entity)
                            .doOnSuccess(saved -> {
                                eventPublisher.publish(
                                        StreamEvent.ended(saved.getId(),
                                                saved.getBroadcasterSubject()))
                                        .subscribe();
                                log.info("Stream ended via webhook: id={}",
                                        saved.getId());
                            });
                })
                .onErrorResume(e -> {
                    log.warn("on_unpublish lookup failed (idempotent no-op): {}",
                            e.getMessage());
                    return Mono.empty();
                })
                .then();
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

    // ── Private helpers ─────────────────────────────────────────────────────

    /** Generate a new SRS stream name (UUID without dashes). */
    private static String newSrsName() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Build a PublishKeyResponse from entity state. */
    private PublishKeyResponse buildPublishKeyResponse(
            StreamSessionEntity entity, String srsName,
            String token, OffsetDateTime now) {
        String rtmpUrl = String.format("%s/live/%s?token=%s",
                publishTokenProps.srsRtmpHost(), srsName, token);
        String playUrl = String.format("%s/live/%s.m3u8",
                publishTokenProps.srsHlsHost(), srsName);
        OffsetDateTime expiresAt = now != null
                ? now.plus(publishTokenProps.ttl())
                : null;
        return new PublishKeyResponse(
                entity.getId(), srsName, rtmpUrl, playUrl, token, expiresAt);
    }

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

    public static class NoPublishKeyException extends RuntimeException {
        public NoPublishKeyException(UUID streamId) {
            super("No publish key has been issued for stream: id=" + streamId);
        }
    }

    /** Thrown when webhook publish token validation fails. */
    public static class InvalidPublishTokenException extends RuntimeException {
        public InvalidPublishTokenException(String message) {
            super(message);
        }
    }
}
