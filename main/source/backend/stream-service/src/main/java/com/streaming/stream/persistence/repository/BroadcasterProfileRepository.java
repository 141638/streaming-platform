package com.streaming.stream.persistence.repository;

import com.streaming.stream.persistence.entity.BroadcasterProfileEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface BroadcasterProfileRepository
        extends ReactiveCrudRepository<BroadcasterProfileEntity, String> {

    Mono<BroadcasterProfileEntity> findByUsername(String username);
}
