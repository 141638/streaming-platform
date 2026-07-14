package com.streaming.stream.messaging;

import com.streaming.stream.persistence.entity.OutboxEvent;
import com.streaming.stream.persistence.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Scheduled poller that reads unpublished rows from the outbox table,
 * publishes them to Kafka, and deletes them on success.
 *
 * <p>Uses {@code FOR UPDATE SKIP LOCKED} so multiple poller instances can
 * safely coexist. Failed publishes are retried on subsequent poll cycles
 * up to {@code maxRetries} times.
 *
 * <p>Runs on a Spring scheduler thread (not the Netty event loop), so
 * {@code .block()} is acceptable — this is background infrastructure,
 * not request-serving code.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${streaming.kafka.topic.stream-control:stream.control}")
    private String topic;

    @Value("${streaming.outbox.batch-size:50}")
    private int batchSize;

    @Value("${streaming.outbox.max-retries:3}")
    private int maxRetries;

    @Value("${streaming.outbox.publish-timeout-seconds:5}")
    private long publishTimeoutSeconds;

    public OutboxPoller(OutboxEventRepository outboxRepository,
                        KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Poll the outbox table and publish any unpublished events to Kafka.
     * Runs on a fixed delay (default 5 seconds).
     */
    @Scheduled(fixedDelayString = "${streaming.outbox.poll-interval-ms:5000}")
    public void poll() {
        outboxRepository.findUnpublished(batchSize)
                .flatMap(this::publishAndDelete, concurrency())
                .collectList()
                .doOnNext(results -> {
                    if (!results.isEmpty()) {
                        log.debug("OutboxPoller published {} events", results.size());
                    }
                })
                .onErrorResume(e -> {
                    log.warn("OutboxPoller poll iteration failed: {}", e.getMessage());
                    return Mono.empty();
                })
                .blockOptional(Duration.ofSeconds(30));
    }

    /**
     * Concurrency level for the flatMap — matches batch size so each row
     * in the batch is published concurrently.
     */
    private int concurrency() {
        return Math.min(batchSize, 16);
    }

    /**
     * Publish a single outbox event to Kafka.
     * On success: delete the row.
     * On failure: increment retry count; after max retries, delete and log.
     */
    private Mono<Void> publishAndDelete(OutboxEvent event) {
        return Mono.fromFuture(
                        kafkaTemplate.send(topic,
                                event.getStreamId().toString(),
                                event.getPayload().asString()))
                .timeout(Duration.ofSeconds(publishTimeoutSeconds))
                .subscribeOn(Schedulers.boundedElastic())
                .then(outboxRepository.delete(event)
                        .doOnSuccess(unused -> log.debug(
                                "Outbox event published: id={} type={}",
                                event.getId(), event.getEventType())))
                .onErrorResume(ex -> handleFailure(event, ex));
    }

    /**
     * Handle a failed publish attempt.
     * Increments {@code retryCount} and updates {@code lastAttemptAt}.
     * After {@code maxRetries}, deletes the row and logs an error
     * (dead-letter topic integration is handled in Task A5).
     */
    private Mono<Void> handleFailure(OutboxEvent event, Throwable ex) {
        int nextRetry = event.getRetryCount() + 1;
        if (nextRetry > maxRetries) {
            log.error("Outbox event exceeded max retries ({}): id={} type={} streamId={} "
                    + "— deleting row (DLQ pending Task A5)",
                    maxRetries, event.getId(), event.getEventType(),
                    event.getStreamId());
            return outboxRepository.delete(event);
        }

        log.warn("Outbox publish failed (attempt {}/{}): id={} type={} error={}",
                nextRetry, maxRetries, event.getId(), event.getEventType(),
                ex.getMessage());

        event.setRetryCount(nextRetry);
        event.setLastAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        return outboxRepository.save(event).then();
    }
}
