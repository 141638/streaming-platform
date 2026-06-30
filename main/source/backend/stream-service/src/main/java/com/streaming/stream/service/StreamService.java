package com.streaming.stream.service;

import com.streaming.stream.api.dto.CreateStreamRequest;
import com.streaming.stream.api.dto.PublishKeyResponse;
import com.streaming.stream.api.dto.StreamResponse;
import com.streaming.stream.api.dto.StreamSummaryResponse;
import com.streaming.stream.api.dto.UpdateStreamRequest;
import com.streaming.stream.persistence.entity.StreamSessionEntity;
import com.streaming.stream.persistence.repository.StreamSessionRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

    public Mono<StreamResponse> createStream(String broadcasterSubject, CreateStreamRequest request) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        StreamSessionEntity entity = new StreamSessionEntity();
        entity.setId(id);
        entity.setBroadcasterSubject(broadcasterSubject);
        entity.setTitle(request.title());
        entity.setDescription(request.description());
        entity.setCategory(request.category());
        entity.setMaxViewers(request.maxViewers());
        entity.setStatus("draft");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        return repository.save(entity)
                .doOnSuccess(saved -> log.info("Stream created: id={}, subject={}", saved.getId(), broadcasterSubject))
                .map(StreamResponse::from);
    }

    public Flux<StreamSummaryResponse> listMyStreams(String broadcasterSubject) {
        return repository.findAllByBroadcasterSubject(broadcasterSubject)
                .map(StreamSummaryResponse::from);
    }

    public Mono<StreamResponse> getStream(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .map(StreamResponse::from);
    }

    public Mono<StreamResponse> updateStream(UUID id, UpdateStreamRequest request) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
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
                        entity.setStatus(request.status());
                    }
                    entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    return repository.save(entity);
                })
                .doOnSuccess(saved -> log.info("Stream updated: id={}", saved.getId()))
                .map(StreamResponse::from);
    }

    public Mono<Void> deleteStream(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> {
                    entity.setStatus("cancelled");
                    entity.setEndedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    return repository.save(entity);
                })
                .doOnSuccess(saved -> log.info("Stream cancelled: id={}", saved.getId()))
                .then();
    }

    public Mono<PublishKeyResponse> issuePublishKey(UUID streamId) {
        return repository.findById(streamId)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(streamId)))
                .map(entity -> {
                    String rawKey = UUID.randomUUID().toString().replace("-", "");
                    String keyHash = sha256Hex(rawKey);
                    entity.setStreamKeyHash(keyHash);
                    entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    return entity;
                })
                .flatMap(repository::save)
                .map(entity -> new PublishKeyResponse(
                        entity.getId(),
                        "sk_" + UUID.randomUUID().toString().replace("-", ""),
                        "rtmp://localhost:1935/live/",
                        OffsetDateTime.now(ZoneOffset.UTC).plusHours(24)
                ))
                .doOnSuccess(resp -> log.info("Publish key issued for stream: id={}", streamId));
    }

    public Mono<PublishKeyResponse> getPublishKey(UUID streamId) {
        return repository.findById(streamId)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(streamId)))
                .map(entity -> new PublishKeyResponse(
                        entity.getId(),
                        "sk_****", // masked
                        "rtmp://localhost:1935/live/",
                        null
                ));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static class StreamNotFoundException extends RuntimeException {
        public StreamNotFoundException(UUID id) {
            super("Stream not found: id=" + id);
        }
    }
}
