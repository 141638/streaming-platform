package com.streaming.notification.application;

import com.streaming.notification.api.dto.SubscriptionResponse;
import com.streaming.notification.domain.Subscription;
import com.streaming.notification.infrastructure.persistence.ReactiveSubscriptionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Application service for subscription (follow/unfollow) operations.
 *
 * <p>Owns the lifecycle of {@link Subscription} entities — creating new follows,
 * soft-deleting unfollows, and querying subscribers for fan-out. All mutations
 * are scoped to the calling user via {@code subscriberSubject}.
 *
 * <p>Idempotency is enforced by the database unique constraint on
 * {@code (subscriber_subject, target_type, target_id)}. A double-click on the
 * Follow button produces a {@link DataIntegrityViolationException} which is
 * mapped to {@link SubscriptionAlreadyExistsException} → HTTP 409 Conflict.
 * See ADR-0002 §5.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final ReactiveSubscriptionRepository subscriptionRepository;

    // ── Follow / Unfollow ────────────────────────────────────────────────

    /**
     * Follow a target (streamer, chat room, etc.).
     *
     * <p>Idempotent — if the subscription already exists (same subscriber +
     * target_type + target_id), the existing row is returned. The database
     * unique constraint catches races between concurrent requests.
     *
     * @param subscriberSubject the JWT {@code sub} of the following user
     * @param targetType        the kind of target ({@code "CHANNEL"}, etc.)
     * @param targetId          the target identifier
     * @return the created or existing subscription as a response DTO
     */
    public Mono<SubscriptionResponse> follow(
            String subscriberSubject, String targetType, String targetId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return subscriptionRepository
                .findBySubscriberSubjectAndTargetTypeAndTargetId(
                        subscriberSubject, targetType, targetId)
                .flatMap(existing -> {
                    if (existing.isActive()) {
                        log.debug("Subscription already active: subscriber={} target={}:{}",
                                subscriberSubject, targetType, targetId);
                        return Mono.just(SubscriptionResponse.from(existing));
                    }
                    // Re-activate a previously unfollowed subscription
                    existing.setActive(true);
                    return subscriptionRepository.save(existing)
                            .doOnSuccess(saved -> log.info(
                                    "Subscription re-activated: id={} subscriber={} target={}:{}",
                                    saved.getId(), subscriberSubject, targetType, targetId))
                            .map(SubscriptionResponse::from);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    Subscription sub = Subscription.create(
                            subscriberSubject, targetType, targetId, now);
                    return subscriptionRepository.save(sub)
                            .doOnSuccess(saved -> log.info(
                                    "Subscription created: id={} subscriber={} target={}:{}",
                                    saved.getId(), subscriberSubject, targetType, targetId))
                            .map(SubscriptionResponse::from);
                }))
                .onErrorMap(DataIntegrityViolationException.class,
                        ex -> new SubscriptionAlreadyExistsException(
                                subscriberSubject, targetType, targetId));
    }

    /**
     * Unfollow a target — soft delete.
     *
     * <p>Ownership is enforced: the subscription's {@code subscriberSubject}
     * must match the calling user.
     *
     * @param id                the subscription ID to deactivate
     * @param subscriberSubject the JWT {@code sub} of the calling user
     * @return empty Mono on success
     */
    public Mono<Void> unfollow(UUID id, String subscriberSubject) {
        return subscriptionRepository.findById(id)
                .switchIfEmpty(Mono.error(
                        new SubscriptionNotFoundException(id, subscriberSubject)))
                .flatMap(sub -> {
                    if (!sub.getSubscriberSubject().equals(subscriberSubject)) {
                        log.warn("Unfollow denied — ownership mismatch: id={} owner={} caller={}",
                                id, sub.getSubscriberSubject(), subscriberSubject);
                        return Mono.error(
                                new SubscriptionNotFoundException(id, subscriberSubject));
                    }
                    if (!sub.isActive()) {
                        log.debug("Subscription already inactive: id={}", id);
                        return Mono.empty();
                    }
                    sub.deactivate();
                    return subscriptionRepository.save(sub)
                            .doOnSuccess(saved -> log.info(
                                    "Subscription deactivated: id={} subscriber={}",
                                    saved.getId(), subscriberSubject))
                            .then();
                });
    }

    // ── Queries ───────────────────────────────────────────────────────────

    /**
     * Get all subscriptions for the calling user (active and inactive), newest first.
     *
     * <p>Returns inactive (unfollowed) subscriptions as well so the UI can
     * show full follow history. The {@code active} flag on the response
     * distinguishes current follows from historical ones.
     *
     * @param subscriberSubject the JWT {@code sub} of the calling user
     * @return the user's subscriptions (active and inactive)
     */
    public Flux<SubscriptionResponse> getSubscriptions(String subscriberSubject) {
        return subscriptionRepository.findBySubscriberSubject(subscriberSubject)
                .map(SubscriptionResponse::from);
    }

    /**
     * Get active subscriptions filtered by target type.
     *
     * @param subscriberSubject the JWT {@code sub} of the calling user
     * @param targetType        the target type to filter by
     * @return the user's active subscriptions of that type
     */
    public Flux<SubscriptionResponse> getSubscriptionsByType(
            String subscriberSubject, String targetType) {
        return subscriptionRepository.findBySubscriberSubjectAndTargetType(
                        subscriberSubject, targetType)
                .map(SubscriptionResponse::from);
    }

    /**
     * Get all active subscribers for a target — used by fan-out.
     *
     * @param targetType the kind of target
     * @param targetId   the target identifier
     * @return all active subscriptions for that target
     */
    public Flux<Subscription> getSubscribers(String targetType, String targetId) {
        return subscriptionRepository.findByTargetTypeAndTargetIdAndActiveTrue(
                targetType, targetId);
    }

    /**
     * Check if the calling user is following a target — read-only.
     *
     * @param subscriberSubject the JWT {@code sub} of the calling user
     * @param targetType        the kind of target
     * @param targetId          the target identifier
     * @return the subscription if it exists and is active, or error if not
     */
    public Mono<SubscriptionResponse> checkSubscription(
            String subscriberSubject, String targetType, String targetId) {
        return subscriptionRepository
                .findBySubscriberSubjectAndTargetTypeAndTargetId(
                        subscriberSubject, targetType, targetId)
                .switchIfEmpty(Mono.error(
                        new SubscriptionNotFoundException(subscriberSubject, targetType, targetId)))
                .map(SubscriptionResponse::from);
    }

    // ── exceptions ────────────────────────────────────────────────────────

    public static class SubscriptionAlreadyExistsException extends RuntimeException {
        public SubscriptionAlreadyExistsException(
                String subscriberSubject, String targetType, String targetId) {
            super("Subscription already exists: subscriber=" + subscriberSubject
                    + " target=" + targetType + ":" + targetId);
        }
    }

    public static class SubscriptionNotFoundException extends RuntimeException {
        public SubscriptionNotFoundException(UUID id, String subscriberSubject) {
            super("Subscription not found: id=" + id + " subscriber=" + subscriberSubject);
        }

        public SubscriptionNotFoundException(
                String subscriberSubject, String targetType, String targetId) {
            super("Subscription not found: subscriber=" + subscriberSubject
                    + " target=" + targetType + ":" + targetId);
        }
    }
}
