package com.streaming.stream.persistence.repository;

import com.streaming.stream.persistence.entity.StreamSessionEntity;
import com.streaming.stream.persistence.entity.StreamStatus;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface StreamSessionRepository extends ReactiveCrudRepository<StreamSessionEntity, UUID> {

    Flux<StreamSessionEntity> findAllByBroadcasterSubject(String broadcasterSubject);

    Mono<Boolean> existsByBroadcasterSubjectAndStatus(String broadcasterSubject, StreamStatus status);
}
