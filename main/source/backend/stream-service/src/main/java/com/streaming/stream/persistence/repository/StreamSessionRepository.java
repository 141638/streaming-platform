package com.streaming.stream.persistence.repository;

import com.streaming.stream.persistence.entity.StreamSessionEntity;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface StreamSessionRepository extends ReactiveCrudRepository<StreamSessionEntity, UUID> {

    Flux<StreamSessionEntity> findAllByBroadcasterSubject(String broadcasterSubject);
}
