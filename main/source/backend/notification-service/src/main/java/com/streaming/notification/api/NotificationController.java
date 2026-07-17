package com.streaming.notification.api;

import com.streaming.notification.api.dto.NotificationResponse;
import com.streaming.notification.api.dto.UnreadCountResponse;
import com.streaming.notification.application.NotificationService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for notification operations.
 *
 * <p>All endpoints are scoped to the authenticated user — the
 * {@code recipientSubject} is always extracted from the JWT {@code sub}
 * claim, never from the request body or path. Users can only see and
 * modify their own notifications.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * Get the current user's notifications, newest first.
     *
     * <p>Supports cursor-based pagination: pass the {@code createdAt} ISO-8601
     * timestamp of the oldest notification currently displayed as {@code cursor}
     * to fetch the next page of older notifications. Omit {@code cursor} for
     * the first page.
     */
    @GetMapping("/notifications")
    public Mono<List<NotificationResponse>> getNotifications(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        String recipientSubject = jwt.getSubject();
        return notificationService.getNotifications(recipientSubject, cursor, limit)
                .map(list -> list.stream()
                        .map(NotificationResponse::from)
                        .toList());
    }

    /**
     * Get the count of unread notifications for the bell badge.
     */
    @GetMapping("/notifications/unread-count")
    public Mono<UnreadCountResponse> getUnreadCount(
            @AuthenticationPrincipal Jwt jwt) {
        String recipientSubject = jwt.getSubject();
        return notificationService.getUnreadCount(recipientSubject)
                .map(UnreadCountResponse::of);
    }

    /**
     * Mark a notification as read.
     *
     * <p>Ownership is enforced — the notification must belong to the
     * authenticated user. Returns 404 if the notification does not exist
     * or does not belong to the caller.
     */
    @PostMapping("/notifications/{id}/read")
    public Mono<NotificationResponse> markAsRead(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        String recipientSubject = jwt.getSubject();
        return notificationService.markAsRead(id, recipientSubject)
                .map(NotificationResponse::from);
    }

    /**
     * Mark all unread notifications as read for the authenticated user.
     *
     * <p>Uses a bulk UPDATE under the hood — O(1) query regardless of how
     * many unread notifications the user has. Returns the remaining unread
     * count (always 0 after a successful call).
     */
    @PostMapping("/notifications/read-all")
    public Mono<UnreadCountResponse> markAllAsRead(
            @AuthenticationPrincipal Jwt jwt) {
        String recipientSubject = jwt.getSubject();
        return notificationService.markAllAsRead(recipientSubject)
                .then(Mono.just(UnreadCountResponse.of(0L)));
    }
}
