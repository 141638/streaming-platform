package com.streaming.notification.application;

import com.streaming.notification.api.dto.NotificationResponse;
import com.streaming.notification.domain.Notification;
import com.streaming.notification.infrastructure.SseConnectionRegistry;
import com.streaming.notification.infrastructure.persistence.ReactiveNotificationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Delivers a notification through all active channels.
 *
 * <p>This is a concrete {@code @Service}, not an interface. Delivery channels
 * (persist, SSE push, outbox enqueue) are additive pipeline stages, not alternative
 * strategies. An interface would imply swappable implementations, which is the
 * wrong abstraction for a composed pipeline.
 *
 * <p><b>Pipeline ordering (ADR-0002 §2):</b>
 * <ol>
 *   <li>Persist to PostgreSQL — required, synchronous. Failure propagates.</li>
 *   <li>SSE push — fire-and-forget, best-effort. Failures are logged.</li>
 *   <li>Outbox enqueue — for non-in_app channels (email, future push).
 *       The outbox poller handles dispatch asynchronously.</li>
 * </ol>
 *
 * <p>The fast path (persist + SSE, ~10ms) is decoupled from the slow path
 * (SMTP, 500ms–2s). The user gets the in-app notification immediately; email
 * arrives seconds later via the outbox.
 */
@Service
@RequiredArgsConstructor
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final ReactiveNotificationRepository repository;
    private final SseConnectionRegistry sseRegistry;
    private final OutboxService outboxService;

    /**
     * Deliver a notification through all active channels.
     *
     * <p>Step 1 — persist (required, in-chain).
     * <p>Step 2 — SSE push (fire-and-forget, best-effort).
     * <p>Step 3 — outbox enqueue is added in the outbox wiring phase
     * (see {@code OutboxService}).
     *
     * @param notification the notification to deliver
     * @return empty Mono that completes when persist + push are done
     */
    public Mono<Void> deliver(Notification notification) {
        return repository.save(notification)
                .flatMap(saved -> {
                    log.info("Notification delivered: id={} category={} recipient={}",
                            saved.getId(), saved.getCategory(),
                            saved.getRecipientSubject());
                    // SSE push — best-effort, fire-and-forget
                    sseRegistry.push(saved.getRecipientSubject(),
                            NotificationResponse.from(saved));
                    // Outbox enqueue — chained so errors are visible
                    return outboxService.enqueue(saved).thenReturn(saved);
                })
                .then();
    }

    /**
     * Fan-out to multiple recipients with bounded concurrency.
     *
     * <p>For MVP, this runs inline in the reactive chain. When subscriber
     * counts demand it, this switches to outbox-driven fan-out (designed
     * in ADR-0002 §4).
     *
     * @param notifications the notifications to deliver (one per recipient)
     * @param concurrency   max concurrent deliveries
     * @return empty Mono when all deliveries complete
     */
    public Mono<Void> deliverToMany(List<Notification> notifications, int concurrency) {
        return Flux.fromIterable(notifications)
                .flatMap(this::deliver, concurrency)
                .then();
    }
}
