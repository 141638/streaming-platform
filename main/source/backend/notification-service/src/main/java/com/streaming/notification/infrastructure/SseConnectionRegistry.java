package com.streaming.notification.infrastructure;

import com.streaming.notification.api.dto.NotificationResponse;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * In-memory SSE connection registry keyed by user subject (JWT {@code sub}).
 *
 * <p>Each user may hold multiple connections (browser tabs, devices). A
 * {@link CopyOnWriteArraySet} per user stores the active sinks. When a
 * notification is pushed, it fans out to every connected sink for that user.
 *
 * <p><b>Single-instance only.</b> In a multi-instance deployment, a push on
 * instance A cannot reach a connection on instance B. Redis Pub/Sub fan-out
 * (Phase 6.x) addresses this by broadcasting pushes across all instances,
 * allowing each to deliver to its locally-connected users.
 */
@Component
public class SseConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseConnectionRegistry.class);
    private static final int BACKPRESSURE_BUFFER = 64;

    private final ConcurrentHashMap<String, CopyOnWriteArraySet<Sinks.Many<NotificationResponse>>> connections =
            new ConcurrentHashMap<>();

    /**
     * Register a new SSE connection for a user.
     *
     * <p>Returns a {@link Flux} that the controller streams to the client.
     * The sink is automatically removed when the flux terminates (client
     * disconnects, error, or cancel).
     *
     * @param subject the JWT {@code sub} of the authenticated user
     * @return a never-ending flux of notifications for this connection
     */
    public Flux<NotificationResponse> register(String subject) {
        Sinks.Many<NotificationResponse> sink = Sinks.many()
                .multicast()
                .onBackpressureBuffer(BACKPRESSURE_BUFFER);

        connections.compute(subject, (key, sinks) -> {
            CopyOnWriteArraySet<Sinks.Many<NotificationResponse>> set =
                    sinks != null ? sinks : new CopyOnWriteArraySet<>();
            set.add(sink);
            return set;
        });

        log.info("SSE connection registered: subject={} connectionsForUser={}",
                subject, countFor(subject));

        return sink.asFlux()
                .doFinally(signalType -> remove(subject, sink));
    }

    /**
     * Push a notification to all connected sinks for a user.
     *
     * <p>Fire-and-forget — failures on individual sinks are logged but do
     * not propagate to the caller. If no connections exist for the user,
     * this is a silent no-op.
     *
     * @param subject      the JWT {@code sub} of the target user
     * @param notification the notification to push
     */
    public void push(String subject, NotificationResponse notification) {
        CopyOnWriteArraySet<Sinks.Many<NotificationResponse>> sinks = connections.get(subject);
        if (sinks == null || sinks.isEmpty()) {
            log.debug("No SSE connections for subject={}, push skipped: notificationId={}",
                    subject, notification.id());
            return;
        }

        for (Sinks.Many<NotificationResponse> sink : sinks) {
            Sinks.EmitResult result = sink.tryEmitNext(notification);
            if (result.isFailure()) {
                log.warn("SSE push failed for subject={} notificationId={}: result={}",
                        subject, notification.id(), result);
            }
        }

        log.debug("SSE push complete: subject={} notificationId={} sinks={}",
                subject, notification.id(), sinks.size());
    }

    // ── internal ────────────────────────────────────────────────────────

    private void remove(String subject, Sinks.Many<NotificationResponse> sink) {
        Sinks.EmitResult completeResult = sink.tryEmitComplete();
        if (completeResult.isFailure()) {
            log.debug("SSE sink already completed for subject={}: result={}", subject, completeResult);
        }

        connections.computeIfPresent(subject, (key, sinks) -> {
            sinks.remove(sink);
            if (sinks.isEmpty()) {
                log.info("Last SSE connection removed for subject={}", subject);
                return null; // remove the map entry
            }
            log.info("SSE connection removed: subject={} remaining={}", subject, sinks.size());
            return sinks;
        });
    }

    private int countFor(String subject) {
        CopyOnWriteArraySet<?> sinks = connections.get(subject);
        return sinks == null ? 0 : sinks.size();
    }

    /** Visible for testing and health checks. */
    public int totalConnections() {
        return connections.values().stream()
                .mapToInt(CopyOnWriteArraySet::size)
                .sum();
    }
}
