package com.streaming.stream.service;

import com.streaming.common.crypto.HashUtils;
import com.streaming.stream.api.dto.CreateStreamRequest;
import com.streaming.stream.api.dto.PublishKeyResponse;
import com.streaming.stream.api.dto.StreamResponse;
import com.streaming.stream.api.dto.StreamSummaryResponse;
import com.streaming.stream.api.dto.UpdateStreamRequest;
import com.streaming.stream.persistence.entity.StreamSessionEntity;
import com.streaming.stream.persistence.entity.StreamStatus;
import com.streaming.stream.persistence.repository.StreamSessionRepository;
import com.streaming.stream.security.AuthAction;
import com.streaming.stream.security.AuthResourceDomain;
import com.streaming.stream.security.AuthResourceKind;
import com.streaming.stream.security.RequiredAuthority;
import com.streaming.stream.security.StreamAuthorization;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Stub stream service — returns generated data with correct contracts.
 * Business logic (state transitions, key rotation, SRS integration) will
 * replace the stubs in a later phase.
 */
@Service
@RequiredArgsConstructor
public class StreamService {

    private static final Logger log = LoggerFactory.getLogger(StreamService.class);

    private final StreamSessionRepository repository;
    private final StreamAuthorization authorization;

    public Mono<StreamResponse> createStream(CreateStreamRequest request, Jwt jwt) {
        final String sub = jwt.getSubject();
        final UUID id = UUID.randomUUID();
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        StreamSessionEntity entity = new StreamSessionEntity();
        entity.setId(id);
        entity.setNew(true);
        entity.setBroadcasterSubject(sub);
        entity.setTitle(request.title());
        entity.setDescription(request.description());
        entity.setCategory(request.category());
        entity.setMaxViewers(request.maxViewers());
        entity.setStatus(StreamStatus.DRAFT);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        return authorization.requireAccess(jwt, new RequiredAuthority(
                        AuthResourceDomain.STREAM,
                        AuthResourceKind.SESSION,
                        AuthAction.CREATE,
                        sub
                ))
                .then(repository.save(entity))
                .doOnSuccess(saved -> log.info("Stream created: id={}, subject={}", saved.getId(), sub))
                .map(StreamResponse::from);
    }

    public Flux<StreamSummaryResponse> listMyStreams(String broadcasterSubject) {
        return repository.findAllByBroadcasterSubject(broadcasterSubject)
                .map(StreamSummaryResponse::from);
    }

    public Mono<StreamResponse> getStream(UUID id, Jwt jwt) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM,
                                AuthResourceKind.SESSION,
                                AuthAction.READ,
                                entity.getBroadcasterSubject())
                        )
                        .onErrorMap(StreamAuthorization.StreamAccessDeniedException.class,
                                e -> new StreamNotFoundException(id))
                        .thenReturn(entity))
                .map(StreamResponse::from);
    }

    public Mono<StreamResponse> updateStream(UUID id, UpdateStreamRequest request, Jwt jwt) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.UPDATE, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> {
                    if (request.title() != null) {
                        entity.setTitle(request.title());
                    }
                    if (request.description() != null) {
                        entity.setDescription(request.description());
                    }
                    if (request.category() != null) {
                        entity.setCategory(request.category());
                    }
                    if (request.maxViewers() != null) {
                        entity.setMaxViewers(request.maxViewers());
                    }
                    if (request.status() != null) {
                        entity.setStatus(StreamStatus.fromWireValue(request.status()));
                    }
                    entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    return repository.save(entity);
                })
                .doOnSuccess(saved -> log.info("Stream updated: id={}", saved.getId()))
                .map(StreamResponse::from);
    }

    public Mono<Void> deleteStream(UUID id, Jwt jwt) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.DELETE, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> {
                    entity.setStatus(StreamStatus.CANCELLED);
                    entity.setEndedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    return repository.save(entity);
                })
                .doOnSuccess(saved -> log.info("Stream cancelled: id={}", saved.getId()))
                .then();
    }

    public Mono<PublishKeyResponse> issuePublishKey(UUID streamId, Jwt jwt) {
        return repository.findById(streamId)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(streamId)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
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
                        .requireAccess(jwt, new RequiredAuthority(AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.READ, entity.getBroadcasterSubject()))
                        .onErrorMap(StreamAuthorization.StreamAccessDeniedException.class,
                                e -> new StreamNotFoundException(streamId))
                        .thenReturn(entity))
                .map(entity -> new PublishKeyResponse(
                        entity.getId(),
                        "sk_****", // masked
                        "rtmp://localhost:1935/live/",
                        null
                ));
    }

    private record PendingKey(StreamSessionEntity entity, String rawKey) {}

    public static class StreamNotFoundException extends RuntimeException {
        public StreamNotFoundException(UUID id) {
            super("Stream not found: id=" + id);
        }
    }
}
