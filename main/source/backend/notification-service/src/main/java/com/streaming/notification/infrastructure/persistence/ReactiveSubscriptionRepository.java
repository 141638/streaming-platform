package com.streaming.notification.infrastructure.persistence;

import com.streaming.notification.domain.Subscription;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R2DBC repository for {@link Subscription} entities.
 *
 * <p>Supports three query patterns:
 * <ol>
 *   <li><b>User-facing</b> — "show my follows" (scoped to subscriber)</li>
 *   <li><b>Fan-out</b> — "who follows this target?" (scoped to target)</li>
 *   <li><b>Idempotency check</b> — "am I already following?"</li>
 * </ol>
 */
public interface ReactiveSubscriptionRepository
        extends ReactiveCrudRepository<Subscription, UUID> {

    /** All subscriptions for a user (active and inactive). */
    Flux<Subscription> findBySubscriberSubject(String subscriberSubject);

    /**
     * All active subscribers for a given target — used by fan-out.
     * Backed by the partial index {@code ix_subscription_target}.
     */
    Flux<Subscription> findByTargetTypeAndTargetIdAndActiveTrue(
            String targetType, String targetId);

    /**
     * Ownership-scoped lookup for a specific subscription.
     * Used by unfollow and existence checks.
     */
    Mono<Subscription> findBySubscriberSubjectAndTargetTypeAndTargetId(
            String subscriberSubject, String targetType, String targetId);

    /** Check if a subscription exists (for idempotency guard before INSERT). */
    Mono<Boolean> existsBySubscriberSubjectAndTargetTypeAndTargetId(
            String subscriberSubject, String targetType, String targetId);

    /** Filter subscriptions by type (e.g., "show my CHANNEL follows"). */
    Flux<Subscription> findBySubscriberSubjectAndTargetType(
            String subscriberSubject, String targetType);
}
