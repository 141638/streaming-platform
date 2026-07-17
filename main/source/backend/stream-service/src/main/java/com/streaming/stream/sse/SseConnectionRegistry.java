package com.streaming.stream.sse;

import com.streaming.stream.api.dto.StreamSseEvent;
import java.util.UUID;
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
 * stream event is pushed, it fans out to every connected sink for that user.
 *
 * <p>Additionally tracks which users are watching each stream, enabling
 * broadcast pushes to all viewers of a stream (e.g. stream:ended).
 *
 * <p><b>Single-instance only.</b> In a multi-instance deployment, a push on
 * instance A cannot reach a connection on instance B. Redis Pub/Sub fan-out
 * addresses this by broadcasting pushes across all instances, allowing each
 * to deliver to its locally-connected users.
 */
@Component
public class SseConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseConnectionRegistry.class);
    private static final int BACKPRESSURE_BUFFER = 64;

    private final ConcurrentHashMap<String, CopyOnWriteArraySet<Sinks.Many<StreamSseEvent>>> connections =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<String>> streamViewers =
            new ConcurrentHashMap<>();

    /**
     * Register a new SSE connection for a user, optionally scoped to a stream.
     *
     * <p>Returns a {@link Flux} that the controller streams to the client.
     * The sink is automatically removed when the flux terminates (client
     * disconnects, error, or cancel).
     *
     * <p>When {@code streamId} is non-null, the user is also registered as a
     * viewer of that stream so that stream-level broadcast events
     * (e.g. "stream:ended") reach them.
     *
     * @param subject  the JWT {@code sub} of the authenticated user
     * @param streamId the stream being watched, or {@code null} for global events
     * @return a never-ending flux of stream events for this connection
     */
    public Flux<StreamSseEvent> register(String subject, UUID streamId) {
        Sinks.Many<StreamSseEvent> sink = Sinks.many()
                .multicast()
                .onBackpressureBuffer(BACKPRESSURE_BUFFER);

        connections.compute(subject, (key, sinks) -> {
            CopyOnWriteArraySet<Sinks.Many<StreamSseEvent>> set =
                    sinks != null ? sinks : new CopyOnWriteArraySet<>();
            set.add(sink);
            return set;
        });

        if (streamId != null) {
            registerViewer(streamId, subject);
        }

        log.info("SSE connection registered: subject={} streamId={} connectionsForUser={}",
                subject, streamId, countFor(subject));

        return sink.asFlux()
                .doFinally(signalType -> remove(subject, sink, streamId));
    }

    /**
     * Push a stream event to all connected sinks for a user.
     *
     * <p>Fire-and-forget — failures on individual sinks are logged but do
     * not propagate to the caller. If no connections exist for the user,
     * this is a silent no-op.
     *
     * @param subject the JWT {@code sub} of the target user
     * @param event   the stream event to push
     */
    public void push(String subject, StreamSseEvent event) {
        CopyOnWriteArraySet<Sinks.Many<StreamSseEvent>> sinks = connections.get(subject);
        if (sinks == null || sinks.isEmpty()) {
            log.debug("No SSE connections for subject={}, push skipped: eventType={}",
                    subject, event.type());
            return;
        }

        for (Sinks.Many<StreamSseEvent> sink : sinks) {
            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result.isFailure()) {
                log.warn("SSE push failed for subject={} eventType={} streamId={}: result={}",
                        subject, event.type(), event.streamId(), result);
            }
        }

        log.debug("SSE push complete: subject={} eventType={} streamId={} sinks={}",
                subject, event.type(), event.streamId(), sinks.size());
    }

    /**
     * Push a stream event to every user currently watching the given stream.
     *
     * <p>Fans out to all viewer subjects via {@link #push(String, StreamSseEvent)}.
     * If no viewers are registered for the stream, this is a silent no-op.
     *
     * @param streamId the stream whose viewers should receive the event
     * @param event    the stream event to push
     */
    public void pushToStreamViewers(UUID streamId, StreamSseEvent event) {
        CopyOnWriteArraySet<String> viewers = streamViewers.get(streamId);
        if (viewers == null || viewers.isEmpty()) {
            log.debug("No viewers for streamId={}, push skipped: eventType={}",
                    streamId, event.type());
            return;
        }

        for (String subject : viewers) {
            push(subject, event);
        }

        log.debug("SSE push to stream viewers complete: streamId={} eventType={} viewerCount={}",
                streamId, event.type(), viewers.size());
    }

    // ── internal ────────────────────────────────────────────────────────

    void registerViewer(UUID streamId, String subject) {
        streamViewers.compute(streamId, (key, viewers) -> {
            CopyOnWriteArraySet<String> set =
                    viewers != null ? viewers : new CopyOnWriteArraySet<>();
            set.add(subject);
            return set;
        });
        log.debug("Viewer registered: streamId={} subject={}", streamId, subject);
    }

    private void remove(String subject, Sinks.Many<StreamSseEvent> sink, UUID streamId) {
        Sinks.EmitResult completeResult = sink.tryEmitComplete();
        if (completeResult.isFailure()) {
            log.debug("SSE sink already completed for subject={}: result={}", subject, completeResult);
        }

        connections.computeIfPresent(subject, (key, sinks) -> {
            sinks.remove(sink);
            if (sinks.isEmpty()) {
                log.info("Last SSE connection removed for subject={}", subject);
                return null;
            }
            log.info("SSE connection removed: subject={} remaining={}", subject, sinks.size());
            return sinks;
        });

        if (streamId != null) {
            removeViewer(streamId, subject);
        }
    }

    private void removeViewer(UUID streamId, String subject) {
        streamViewers.computeIfPresent(streamId, (key, viewers) -> {
            viewers.remove(subject);
            if (viewers.isEmpty()) {
                log.debug("Last viewer removed for streamId={}", streamId);
                return null;
            }
            log.debug("Viewer removed: streamId={} subject={} remainingViewers={}",
                    streamId, subject, viewers.size());
            return viewers;
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

    /**
     * Returns the set of stream IDs that have at least one connected viewer.
     * Used by {@code ViewerCountPushService} to know which streams need
     * viewer count scans.
     *
     * @return an unmodifiable view of active stream IDs (empty if none)
     */
    public java.util.Set<UUID> getActiveStreamIds() {
        return java.util.Collections.unmodifiableSet(streamViewers.keySet());
    }
}
