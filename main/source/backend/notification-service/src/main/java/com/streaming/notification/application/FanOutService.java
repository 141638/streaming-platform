package com.streaming.notification.application;

import com.streaming.common.messaging.StreamEvent;
import com.streaming.notification.domain.FanOutJob;
import com.streaming.notification.infrastructure.persistence.ReactiveFanOutJobRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Enqueues a fan-out job when a followed broadcaster starts a stream.
 *
 * <p>Called from {@code StreamControlListener.onStreamStarted()} — replaces
 * the inline {@code deliverToMany()} fan-out path. The enqueue is a single
 * INSERT (~2ms), so the Kafka consumer thread returns immediately. The
 * heavy work (subscriber lookup, chunked dispatch) happens asynchronously
 * in {@code FanOutPoller}.
 *
 * <p>Idempotency is enforced by the unique index on {@code (event_id, job_type)}.
 * Replaying the same Kafka event produces a duplicate INSERT → caught by
 * {@code DataIntegrityViolationException} → logged and skipped.
 */
@Service
@RequiredArgsConstructor
public class FanOutService {

    private static final Logger log = LoggerFactory.getLogger(FanOutService.class);

    private final ReactiveFanOutJobRepository fanOutJobRepository;

    /**
     * Enqueue a fan-out job for a stream event.
     *
     * <p>Idempotent — if a job for this (eventId, jobType) already exists,
     * the duplicate is logged and skipped.
     *
     * @param event the stream event carrying broadcaster info
     * @return empty Mono (completes when the INSERT succeeds or is skipped)
     */
    public Mono<Void> enqueue(StreamEvent event) {
        String eventId = event.eventId();
        String jobType = event.eventType();  // e.g., "STREAM_STARTED"

        // Idempotency guard: skip if already enqueued
        return fanOutJobRepository.existsByEventIdAndJobType(eventId, jobType)
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        log.debug("Fan-out job already enqueued — skipping: eventId={} type={}",
                                eventId, jobType);
                        return Mono.empty();
                    }
                    return doEnqueue(event, eventId, jobType);
                });
    }

    private Mono<Void> doEnqueue(StreamEvent event, String eventId, String jobType) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        FanOutJob job = FanOutJob.create(
                eventId,
                jobType,
                event.broadcasterSubject(),
                "CHANNEL",                     // targetType
                event.broadcasterSubject(),    // targetId
                now);

        return fanOutJobRepository.save(job)
                .doOnSuccess(saved -> log.info(
                        "Fan-out job enqueued: jobId={} broadcaster={} eventId={}",
                        saved.getId(), event.broadcasterSubject(), eventId))
                .doOnError(err -> log.error(
                        "Failed to enqueue fan-out job: eventId={} broadcaster={} error={}",
                        eventId, event.broadcasterSubject(), err.getMessage()))
                .then();
    }
}
