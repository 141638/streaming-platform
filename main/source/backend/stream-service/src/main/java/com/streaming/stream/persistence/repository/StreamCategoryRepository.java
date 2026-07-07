package com.streaming.stream.persistence.repository;

import com.streaming.stream.persistence.entity.StreamCategoryEntity;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface StreamCategoryRepository extends ReactiveCrudRepository<StreamCategoryEntity, UUID> {

    Mono<StreamCategoryEntity> findBySlug(String slug);

    Mono<StreamCategoryEntity> findByName(String name);
}
