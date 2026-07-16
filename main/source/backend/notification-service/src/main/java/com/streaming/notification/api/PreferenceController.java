package com.streaming.notification.api;

import com.streaming.notification.api.dto.PreferenceRequest;
import com.streaming.notification.api.dto.PreferenceResponse;
import com.streaming.notification.application.PreferenceService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for notification preference operations.
 *
 * <p>All endpoints are scoped to the authenticated user — the
 * {@code subscriberSubject} is always extracted from the JWT {@code sub}
 * claim. Users manage their own delivery channel preferences.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class PreferenceController {

    private final PreferenceService preferenceService;

    /**
     * Create or update a delivery preference for a channel.
     * Idempotent — one row per (user, channel) enforced by DB constraint.
     */
    @PutMapping("/preferences")
    public Mono<PreferenceResponse> upsertPreference(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody PreferenceRequest request) {
        String subscriberSubject = jwt.getSubject();
        return preferenceService.upsertPreference(
                subscriberSubject, request.channel(), request.topicGlob());
    }

    /** Get all preferences for the current user. */
    @GetMapping("/preferences")
    public Mono<List<PreferenceResponse>> getPreferences(
            @AuthenticationPrincipal Jwt jwt) {
        String subscriberSubject = jwt.getSubject();
        return preferenceService.getPreferences(subscriberSubject)
                .collectList();
    }

    /**
     * Update a specific preference — ownership-scoped.
     * Body: {@code {"active": true, "topicGlob": "STREAM_*"}} — both fields optional.
     */
    @PatchMapping("/preferences/{id}")
    public Mono<PreferenceResponse> updatePreference(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        String subscriberSubject = jwt.getSubject();
        Boolean active = body.containsKey("active")
                ? (Boolean) body.get("active") : null;
        String topicGlob = body.containsKey("topicGlob")
                ? (String) body.get("topicGlob") : null;
        return preferenceService.updatePreference(id, subscriberSubject, active, topicGlob);
    }

    /** Delete a preference — ownership-scoped. */
    @DeleteMapping("/preferences/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deletePreference(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        String subscriberSubject = jwt.getSubject();
        return preferenceService.deletePreference(id, subscriberSubject);
    }
}
