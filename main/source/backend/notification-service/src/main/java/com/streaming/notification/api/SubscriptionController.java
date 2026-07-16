package com.streaming.notification.api;

import com.streaming.notification.api.dto.SubscriptionRequest;
import com.streaming.notification.api.dto.SubscriptionResponse;
import com.streaming.notification.application.SubscriptionService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for subscription (follow/unfollow) operations.
 *
 * <p>All endpoints are scoped to the authenticated user — the
 * {@code subscriberSubject} is always extracted from the JWT {@code sub}
 * claim, never from the request body.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    /**
     * Follow a target. Idempotent — returns 200 with the existing subscription
     * if already following, 409 if a concurrent request created it first.
     */
    @PutMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<SubscriptionResponse> follow(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody SubscriptionRequest request) {
        String subscriberSubject = jwt.getSubject();
        return subscriptionService.follow(
                subscriberSubject, request.targetType(), request.targetId());
    }

    /**
     * Unfollow a target — soft delete (sets active=false).
     * Ownership-scoped: the subscription must belong to the authenticated user.
     */
    @DeleteMapping("/subscriptions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unfollow(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        String subscriberSubject = jwt.getSubject();
        return subscriptionService.unfollow(id, subscriberSubject);
    }

    /**
     * Get the current user's subscriptions, newest first.
     * Optionally filtered by {@code target_type}.
     */
    @GetMapping("/subscriptions")
    public Mono<List<SubscriptionResponse>> getSubscriptions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "target_type", required = false) String targetType) {
        String subscriberSubject = jwt.getSubject();
        if (targetType != null && !targetType.isBlank()) {
            return subscriptionService.getSubscriptionsByType(subscriberSubject, targetType)
                    .collectList();
        }
        return subscriptionService.getSubscriptions(subscriberSubject)
                .collectList();
    }

    /**
     * Check if the current user is following a specific target — read-only.
     * Returns 200 with the subscription or 404 if not following.
     */
    @GetMapping("/subscriptions/check")
    public Mono<SubscriptionResponse> checkSubscription(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("target_type") String targetType,
            @RequestParam("target_id") String targetId) {
        String subscriberSubject = jwt.getSubject();
        return subscriptionService.checkSubscription(
                subscriberSubject, targetType, targetId);
    }
}
