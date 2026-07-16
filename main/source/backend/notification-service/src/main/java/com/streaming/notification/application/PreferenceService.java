package com.streaming.notification.application;

import com.streaming.notification.api.dto.PreferenceResponse;
import com.streaming.notification.domain.NotificationPreference;
import com.streaming.notification.infrastructure.persistence.ReactiveNotificationPreferenceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Application service for notification preference operations.
 *
 * <p>Manages delivery channel preferences — which channels (in_app, email,
 * push) a user wants notifications delivered through, optionally filtered
 * by category pattern ({@code topic_glob}). One row per (user, channel) pair.
 *
 * <p>All mutations are scoped to the calling user via {@code subscriberSubject}.
 */
@Service
@RequiredArgsConstructor
public class PreferenceService {

    private static final Logger log = LoggerFactory.getLogger(PreferenceService.class);

    private final ReactiveNotificationPreferenceRepository preferenceRepository;

    // ── Upsert ────────────────────────────────────────────────────────────

    /**
     * Create or update a delivery preference for a (user, channel) pair.
     *
     * <p>If a preference already exists for this (user, channel), it is updated
     * in-place. Otherwise a new preference is created. Only one row per
     * (user, channel) is allowed (enforced by {@code uq_notification_preference}).
     *
     * @param subscriberSubject the JWT {@code sub} of the owning user
     * @param channel           delivery channel ({@code "in_app"}, {@code "email"}, {@code "push"})
     * @param topicGlob         category filter pattern, or {@code null} for all
     * @return the created or updated preference
     */
    public Mono<PreferenceResponse> upsertPreference(
            String subscriberSubject, String channel, String topicGlob) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return preferenceRepository
                .findBySubscriberSubjectAndChannel(subscriberSubject, channel)
                .flatMap(existing -> {
                    existing.setTopicGlob(topicGlob);
                    existing.setActive(true);
                    existing.touch(now);
                    return preferenceRepository.save(existing)
                            .doOnSuccess(saved -> log.info(
                                    "Preference updated: id={} subscriber={} channel={}",
                                    saved.getId(), subscriberSubject, channel));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    NotificationPreference pref = NotificationPreference.create(
                            subscriberSubject, channel, topicGlob, now);
                    return preferenceRepository.save(pref)
                            .doOnSuccess(saved -> log.info(
                                    "Preference created: id={} subscriber={} channel={}",
                                    saved.getId(), subscriberSubject, channel));
                }))
                .map(PreferenceResponse::from);
    }

    // ── Queries ───────────────────────────────────────────────────────────

    /**
     * Get all preferences for the calling user.
     *
     * @param subscriberSubject the JWT {@code sub} of the calling user
     * @return all preferences (active and inactive)
     */
    public Flux<PreferenceResponse> getPreferences(String subscriberSubject) {
        return preferenceRepository.findBySubscriberSubject(subscriberSubject)
                .map(PreferenceResponse::from);
    }

    // ── Mutations ─────────────────────────────────────────────────────────

    /**
     * Update a specific preference — ownership-scoped.
     *
     * @param id                the preference ID
     * @param subscriberSubject the JWT {@code sub} of the calling user
     * @param active            whether the channel is active
     * @param topicGlob         category filter pattern, or {@code null} for all
     * @return the updated preference
     */
    public Mono<PreferenceResponse> updatePreference(
            UUID id, String subscriberSubject, Boolean active, String topicGlob) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return preferenceRepository.findById(id)
                .switchIfEmpty(Mono.error(
                        new PreferenceNotFoundException(id, subscriberSubject)))
                .flatMap(pref -> {
                    if (!pref.getSubscriberSubject().equals(subscriberSubject)) {
                        log.warn("Preference update denied — ownership mismatch: id={} owner={} caller={}",
                                id, pref.getSubscriberSubject(), subscriberSubject);
                        return Mono.error(
                                new PreferenceNotFoundException(id, subscriberSubject));
                    }
                    if (active != null) {
                        if (active) {
                            pref.activate();
                        } else {
                            pref.deactivate();
                        }
                    }
                    if (topicGlob != null) {
                        pref.updateTopicGlob(topicGlob);
                    }
                    pref.touch(now);
                    return preferenceRepository.save(pref)
                            .doOnSuccess(saved -> log.info(
                                    "Preference updated: id={} subscriber={} channel={}",
                                    saved.getId(), subscriberSubject, saved.getChannel()));
                })
                .map(PreferenceResponse::from);
    }

    /**
     * Delete a preference — ownership-scoped.
     *
     * @param id                the preference ID
     * @param subscriberSubject the JWT {@code sub} of the calling user
     * @return empty Mono on success
     */
    public Mono<Void> deletePreference(UUID id, String subscriberSubject) {
        return preferenceRepository.findById(id)
                .switchIfEmpty(Mono.error(
                        new PreferenceNotFoundException(id, subscriberSubject)))
                .flatMap(pref -> {
                    if (!pref.getSubscriberSubject().equals(subscriberSubject)) {
                        log.warn("Preference delete denied — ownership mismatch: id={} owner={} caller={}",
                                id, pref.getSubscriberSubject(), subscriberSubject);
                        return Mono.error(
                                new PreferenceNotFoundException(id, subscriberSubject));
                    }
                    return preferenceRepository.delete(pref)
                            .doOnSuccess(unused -> log.info(
                                    "Preference deleted: id={} subscriber={}",
                                    id, subscriberSubject));
                });
    }

    // ── exceptions ────────────────────────────────────────────────────────

    public static class PreferenceNotFoundException extends RuntimeException {
        public PreferenceNotFoundException(UUID id, String subscriberSubject) {
            super("Preference not found: id=" + id + " subscriber=" + subscriberSubject);
        }
    }
}
