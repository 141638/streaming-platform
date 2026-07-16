package com.streaming.notification.messaging;

import com.streaming.notification.domain.OutboxEntry;
import com.streaming.notification.infrastructure.email.EmailAdapter;
import com.streaming.notification.infrastructure.persistence.ReactiveOutboxRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Scheduled poller that reads PENDING rows from the notification outbox table
 * and dispatches them via the appropriate channel adapter (email, future push).
 *
 * <p>Uses {@code FOR UPDATE SKIP LOCKED} so multiple poller instances can
 * safely coexist. Failed deliveries are retried on subsequent poll cycles
 * up to {@code maxRetries} times, then marked DEAD.
 *
 * <p>Mirrors {@code stream-service:OutboxPoller} — same locking strategy,
 * same retry semantics, same concurrency model.
 *
 * <p>Runs on a Spring scheduler thread (not the Netty event loop), so
 * {@code .blockOptional()} is acceptable — this is background infrastructure,
 * not request-serving code.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final ReactiveOutboxRepository outboxRepository;
    private final EmailAdapter emailAdapter;

    @Value("${notification.outbox.batch-size:50}")
    private int batchSize;

    @Value("${notification.outbox.max-retries:3}")
    private int maxRetries;

    public OutboxPoller(ReactiveOutboxRepository outboxRepository,
                        EmailAdapter emailAdapter) {
        this.outboxRepository = outboxRepository;
        this.emailAdapter = emailAdapter;
    }

    /**
     * Poll the outbox table and dispatch PENDING entries.
     * Runs on a fixed delay (default 5 seconds).
     */
    @Scheduled(fixedDelayString = "${notification.outbox.poll-interval:5000}")
    public void poll() {
        outboxRepository.pollPending(batchSize)
                .flatMap(this::processEntry, concurrency())
                .collectList()
                .doOnNext(results -> {
                    if (!results.isEmpty()) {
                        log.debug("OutboxPoller processed {} entries", results.size());
                    }
                })
                .onErrorResume(e -> {
                    log.warn("OutboxPoller poll iteration failed: {}", e.getMessage());
                    return Mono.empty();
                })
                .blockOptional(Duration.ofSeconds(30));
    }

    private int concurrency() {
        return Math.min(batchSize, 16);
    }

    /**
     * Dispatch a single outbox entry via the appropriate channel adapter.
     */
    private Mono<Void> processEntry(OutboxEntry entry) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // For now, the only channel adapter is email.
        // Future: route by notification_preference channel.
        return emailAdapter.send(entry)
                .then(outboxRepository.updateState(
                        entry.getId(), "SENT", entry.getRetryCount(), now))
                .doOnSuccess(unused -> log.debug(
                        "Outbox entry sent: id={} aggregateId={}",
                        entry.getId(), entry.getAggregateId()))
                .onErrorResume(ex -> handleFailure(entry, ex, now));
    }

    /**
     * Handle a failed delivery attempt.
     * After {@code maxRetries}, marks the entry DEAD. Otherwise records
     * the failure for retry on the next poll cycle.
     */
    private Mono<Void> handleFailure(OutboxEntry entry, Throwable ex, OffsetDateTime now) {
        int nextRetry = entry.getRetryCount() + 1;
        if (nextRetry > maxRetries) {
            log.error("Outbox entry exceeded max retries ({}): id={} aggregateId={} "
                    + "— marking DEAD",
                    maxRetries, entry.getId(), entry.getAggregateId());
            entry.markDead(now);
            return outboxRepository.updateState(
                    entry.getId(), "DEAD", nextRetry, now);
        }

        log.warn("Outbox delivery failed (attempt {}/{}): id={} aggregateId={} error={}",
                nextRetry, maxRetries, entry.getId(), entry.getAggregateId(),
                ex.getMessage());

        entry.recordFailure(now);
        return outboxRepository.updateState(
                entry.getId(), "FAILED", nextRetry, now);
    }
}
