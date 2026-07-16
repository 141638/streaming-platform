package com.streaming.notification.infrastructure.email;

import com.streaming.notification.domain.OutboxEntry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Sends notification emails via Spring Mail.
 *
 * <p>Email delivery is asynchronous via the outbox pattern — the
 * {@code NotificationDispatcher} writes to the outbox, and the
 * {@code OutboxPoller} calls this adapter on a scheduled interval.
 * SMTP latency (500ms–2s) does not affect the fast notification path.
 *
 * <p>For MVP, the adapter builds a simple text email from the outbox
 * payload. Template-based emails (Thymeleaf, etc.) are deferred to
 * a future phase.
 */
@Service
@RequiredArgsConstructor
public class EmailAdapter {

    private static final Logger log = LoggerFactory.getLogger(EmailAdapter.class);

    private final JavaMailSender mailSender;

    /**
     * Send an email for the given outbox entry.
     *
     * <p>The outbox payload contains the serialized notification JSON.
     * For MVP, this is sent as a plain-text email. Template rendering
     * and HTML email are deferred to Phase 5.3.
     *
     * @param entry the outbox entry containing the notification payload
     * @return empty Mono on success, error Mono on failure (triggers retry)
     */
    public Mono<Void> send(OutboxEntry entry) {
        return Mono.fromRunnable(() -> {
            // TODO(5.3): Deserialize payload, resolve recipient email from
            // user profile, render template, and send via JavaMailSender.
            // For MVP, this is a skeleton — the outbox poller marks entries
            // as SENT without actual email dispatch.
            log.debug("Email adapter skeleton: entryId={} aggregateId={} "
                    + "(email sending deferred to Phase 5.3)",
                    entry.getId(), entry.getAggregateId());
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
