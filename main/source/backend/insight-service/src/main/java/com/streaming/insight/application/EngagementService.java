package com.streaming.insight.application;

import com.streaming.common.messaging.EngagementEvent;
import com.streaming.insight.domain.model.EngagementEventEntity;
import com.streaming.insight.infrastructure.persistence.EngagementEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Persists deserialized {@link EngagementEvent} records to the database.
 *
 * <p>Thin service — converts the common event record to an entity and delegates
 * to the repository. No business logic beyond the conversion.
 */
@Service
public class EngagementService {

    private static final Logger log = LoggerFactory.getLogger(EngagementService.class);

    private final EngagementEventRepository repository;

    public EngagementService(EngagementEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Persist a VIEW engagement event.
     *
     * @param event the deserialized event from Kafka
     * @return empty Mono — this is a side effect that completes when the row is inserted
     */
    public Mono<Void> persistView(EngagementEvent event) {
        EngagementEventEntity entity = EngagementEventEntity.create(event);
        return repository.save(entity)
                .doOnSuccess(e -> log.trace("Engagement event persisted: eventId={}", event.eventId()))
                .doOnError(ex -> log.warn("Failed to persist engagement event eventId={}: {}",
                        event.eventId(), ex.getMessage()))
                .onErrorComplete()
                .then();
    }
}
