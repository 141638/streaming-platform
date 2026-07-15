package com.streaming.stream.api;

import com.streaming.stream.api.dto.StreamSseEvent;
import com.streaming.stream.sse.SseConnectionRegistry;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * SSE endpoint for real-time stream lifecycle events.
 *
 * <p>Returns a {@code text/event-stream} that the client holds open
 * indefinitely. Stream lifecycle events are pushed as typed SSE frames.
 * A 30-second heartbeat comment keeps the connection alive through
 * proxies and prevents the gateway from idling out.
 *
 * <p>The client MUST authenticate with a Bearer token. On connection, the
 * user's JWT {@code sub} is registered in {@link SseConnectionRegistry}.
 * When the client disconnects, the sink is automatically removed.
 *
 * <p>Event types emitted on the stream:
 * <ul>
 *   <li>{@code event: stream:started} — a stream has gone live</li>
 *   <li>{@code event: stream:ended} — a stream has ended</li>
 *   <li>{@code event: stream:viewers} — viewer count update (future)</li>
 *   <li>{@code : heartbeat} — SSE comment, emitted every 30s</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class StreamSseController {

    private static final Logger log = LoggerFactory.getLogger(StreamSseController.class);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final SseConnectionRegistry registry;

    public StreamSseController(SseConnectionRegistry registry) {
        this.registry = registry;
    }

    /**
     * Subscribe to real-time stream events via SSE.
     *
     * <p>The client should use {@code @microsoft/fetch-event-source} or the
     * browser {@code EventSource} API. The stream never completes on its own —
     * the client must close the connection to stop receiving events.
     *
     * @param jwt      the authenticated user's JWT
     * @param streamId optional — when provided, scopes this connection to a
     *                 specific stream's events (e.g. stream:ended)
     */
    @GetMapping(path = "/streams/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamSseEvent>> stream(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID streamId) {

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            log.warn("SSE connection rejected: JWT has no sub claim");
            return Flux.error(new IllegalArgumentException(
                    "JWT subject (sub) is required for SSE streaming"));
        }

        Flux<ServerSentEvent<StreamSseEvent>> events = registry.register(subject, streamId)
                .map(event -> ServerSentEvent.<StreamSseEvent>builder()
                        .event(event.type())
                        .data(event)
                        .build());

        Flux<ServerSentEvent<StreamSseEvent>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<StreamSseEvent>builder()
                        .comment("heartbeat")
                        .build());

        return Flux.merge(events, heartbeat)
                .doOnSubscribe(s -> log.info("SSE stream started: subject={} streamId={}",
                        subject, streamId))
                .doOnCancel(() -> log.info("SSE stream cancelled: subject={}", subject));
    }
}
