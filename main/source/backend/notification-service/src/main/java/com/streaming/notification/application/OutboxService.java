package com.streaming.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.notification.api.dto.NotificationResponse;
import com.streaming.notification.domain.Notification;
import com.streaming.notification.domain.OutboxEntry;
import com.streaming.notification.infrastructure.persistence.ReactiveOutboxRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes notifications to the outbox for reliable delivery to downstream
 * channels (email, push).
 *
 * <p>Called by {@link NotificationDispatcher} as the final step in the
 * delivery pipeline. The write is fire-and-forget — failures are logged
 * but do not roll back the notification persist or SSE push.
 *
 * <p>The {@link OutboxPoller} picks up PENDING entries on a scheduled
 * interval and dispatches them via the appropriate channel adapter.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final ReactiveOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Write a notification to the outbox for non-in_app channel delivery.
     *
     * <p>Fire-and-forget — uses {@code subscribe()} instead of chaining
     * into the reactive pipeline. Outbox write failure does not roll back
     * the notification persist or SSE push. The notification is durable
     * in PostgreSQL and delivered via SSE; outbox delivery is additive
     * and retryable.
     *
     * @param notification the persisted notification to deliver via outbox channels
     */
    public void enqueue(Notification notification) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(
                    NotificationResponse.from(notification));
        } catch (Exception e) {
            log.warn("Failed to serialize outbox payload for notification {}: {}",
                    notification.getId(), e.getMessage());
            return;
        }

        OutboxEntry entry = OutboxEntry.create(
                "notification",
                notification.getId().toString(),
                payload,
                OffsetDateTime.now(ZoneOffset.UTC));

        outboxRepository.save(entry)
                .subscribe(
                        saved -> log.debug("Outbox entry created: id={} notificationId={}",
                                saved.getId(), notification.getId()),
                        err -> log.warn("Failed to write outbox entry for notification {}: {}",
                                notification.getId(), err.getMessage())
                );
    }
}
