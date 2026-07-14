package com.streaming.notification.api;

import com.streaming.notification.api.dto.NotificationResponse;
import com.streaming.notification.infrastructure.SseConnectionRegistry;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * SSE endpoint for real-time notification delivery.
 *
 * <p>Returns a {@code text/event-stream} that the client holds open
 * indefinitely. New notifications are pushed as {@code event: notification}
 * frames. A 30-second heartbeat comment keeps the connection alive through
 * proxies and prevents the gateway from idling out.
 *
 * <p>The client MUST authenticate with a Bearer token. On connection, the
 * user's JWT {@code sub} is registered in {@link SseConnectionRegistry}.
 * When the client disconnects (or the token expires mid-stream), the sink
 * is automatically removed.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/v1")
public class NotificationSseController {

    private static final Logger log = LoggerFactory.getLogger(NotificationSseController.class);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final SseConnectionRegistry registry;

    /**
     * Subscribe to real-time notifications via SSE.
     *
     * <p>The client should use {@code @microsoft/fetch-event-source} or the
     * browser {@code EventSource} API. The stream never completes on its own —
     * the client must close the connection to stop receiving events.
     *
     * <p>Event types emitted on the stream:
     * <ul>
     *   <li>{@code event: notification} — a new {@link NotificationResponse} payload</li>
     *   <li>{@code : heartbeat} — SSE comment, emitted every 30s</li>
     * </ul>
     */
    @GetMapping(path = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<NotificationResponse>> stream(
            @AuthenticationPrincipal Jwt jwt) {

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            log.warn("SSE connection rejected: JWT has no sub claim");
            return Flux.error(new IllegalArgumentException(
                    "JWT subject (sub) is required for SSE streaming"));
        }

        Flux<ServerSentEvent<NotificationResponse>> notifications = registry.register(subject)
                .map(dto -> ServerSentEvent.<NotificationResponse>builder()
                        .event("notification")
                        .data(dto)
                        .build());

        Flux<ServerSentEvent<NotificationResponse>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<NotificationResponse>builder()
                        .comment("heartbeat")
                        .build());

        return Flux.merge(notifications, heartbeat)
                .doOnSubscribe(s -> log.info("SSE stream started: subject={}", subject))
                .doOnCancel(() -> log.info("SSE stream cancelled: subject={}", subject));
    }
}
