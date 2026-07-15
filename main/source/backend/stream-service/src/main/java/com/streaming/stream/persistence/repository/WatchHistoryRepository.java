package com.streaming.stream.persistence.repository;

import com.streaming.stream.persistence.entity.WatchHistoryEntity;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface WatchHistoryRepository extends ReactiveCrudRepository<WatchHistoryEntity, UUID> {

    Mono<WatchHistoryEntity> findByUserSubjectAndStreamId(String userSubject, UUID streamId);

    Flux<WatchHistoryEntity> findAllByUserSubjectOrderByWatchedAtDesc(String userSubject);
}
