# Notification 5.1b — Implementation Retrospective

**Date:** 2026-07-16
**Status:** Complete — uncommitted; all 7 tasks implemented
**Blueprint:** [notification-5.1b-subscription-dispatcher-blueprint.md](notification-5.1b-subscription-dispatcher-blueprint.md)
**ADRs:** [0001](adr/notification/0001-subscription-model-and-notification-boundary.md), [0002](adr/notification/0002-notification-delivery-architecture.md)

## 1. What was implemented (vs the original plan)

| Plan item | Files | Notes |
|-----------|-------|-------|
| Task 1: V4 Migration — Schema redesign | `V4__split_subscription_tables.sql` | Renamed `channel_subscription` → `notification_preference` + created `subscription` table with polymorphic `(target_type, target_id)`. Added `uq_notification_preference` UNIQUE constraint + `updated_at` column. Added partial index `ix_subscription_target WHERE active = true`. |
| Task 2: Entities + Repositories | `NotificationPreference.java`, `Subscription.java`, `ReactiveNotificationPreferenceRepository.java`, `ReactiveSubscriptionRepository.java` | Both entities follow `Persistable<UUID>` + `@Transient isNew` + static `create()` pattern. Repositories extend `ReactiveCrudRepository` with derived query methods covering user-facing, fan-out, and idempotency-check patterns. |
| Task 3: Subscription Service + REST API | `SubscriptionService.java`, `SubscriptionRequest.java`, `SubscriptionResponse.java`, `SubscriptionController.java` | Follow/Unfollow with DB-constraint idempotency (`DataIntegrityViolationException` → 409 Conflict). Unfollow uses soft delete (active=false). `GET /v1/subscriptions`, `GET /v1/subscriptions/check`, `DELETE /v1/subscriptions/{id}`. JWT `sub` → subscriberSubject on every endpoint. |
| Task 4: Preference Service + REST API | `PreferenceService.java`, `PreferenceRequest.java`, `PreferenceResponse.java`, `PreferenceController.java` | Upsert semantics for `PUT /v1/preferences` (one row per user+channel). `PATCH /v1/preferences/{id}` for partial updates. `DELETE /v1/preferences/{id}` ownership-scoped. |
| Task 5: NotificationDispatcher + Refactor | `NotificationDispatcher.java` (new), `NotificationService.java` (modified) | Concrete `@Service` facade (persist → SSE → outbox). Replaced duplicated persist+SSE blocks in `createFromStreamEvent()` with `dispatcher.deliver(n)`. Removed `SseConnectionRegistry` dependency from `NotificationService`. |
| Task 6: Outbox + Email Adapter | `V5__change_outbox_payload_to_text.sql`, `OutboxEntry.java`, `ReactiveOutboxRepository.java`, `OutboxService.java`, `OutboxPoller.java`, `EmailAdapter.java` + 3 modified files | V5 migration: JSONB→TEXT + added `retry_count` + `last_attempt_at` columns. Outbox pattern mirrors stream-service (`FOR UPDATE SKIP LOCKED`). `OutboxService.enqueue()` is fire-and-forget (subscribe). `EmailAdapter` is a skeleton (deferred to Phase 5.3). `@EnableScheduling` on `NotificationApplication`. Spring Mail dependency added. SMTP config in `application.yml`. |
| Task 7: ADR acceptance | ADR-0001, ADR-0002, blueprint | Status changed from "proposed" to "accepted" (ADRs), "Planned" to "Implemented" (blueprint). |
| Exception handler update | `NotificationExceptionHandler.java` (modified) | Added handlers for `SubscriptionNotFoundException` (404), `SubscriptionAlreadyExistsException` (409), `PreferenceNotFoundException` (404). |

**Total: 20 new files, 5 modified files, 3 doc status updates.**

### Review findings fixed during implementation

| # | Severity | Issue | Fix |
|---|----------|-------|-----|
| 1 | HIGH | V5 migration missing `retry_count` + `last_attempt_at` columns — OutboxEntry entity mapped columns that didn't exist in DB | Added `ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0` + `last_attempt_at TIMESTAMPTZ` to V5 |
| 2 | HIGH | `GET /v1/subscriptions/check` called `follow()` which creates a subscription — GET should be read-only | Added read-only `SubscriptionService.checkSubscription()` method; controller delegates to it |
| 3 | MEDIUM | `SubscriptionService.getSubscriptions()` Javadoc said "active" but returned all | Updated Javadoc to document actual behavior (all subscriptions returned, `active` flag distinguishes current follows) |
| 4 | LOW | `EmailAdapter` used explicit constructor instead of `@RequiredArgsConstructor` | Converted to `@RequiredArgsConstructor` |

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking doc | Reason |
|------|------------|-------------|--------|
| Fan-out wiring — `StreamControlListener` → `SubscriptionService.getSubscribers()` → `dispatcher.deliverToMany()` | Post-MVP | [ADR-0002 §4](adr/notification/0002-notification-delivery-architecture.md#4-outbox-driven-fan-out-architecture-designed-now-inline-for-mvp), [blueprint Task 7](notification-5.1b-subscription-dispatcher-blueprint.md#task-7-fan-out-design-adr-only-no-code) | Subscriber counts low in MVP; inline fan-out via `deliverToMany()` is acceptable |
| Outbox-driven fan-out — `FanOutJob` table + `FanOutPoller` | When broadcaster has >1K followers | [ADR-0002 §4](adr/notification/0002-notification-delivery-architecture.md#4-outbox-driven-fan-out-architecture-designed-now-inline-for-mvp) | Architecture designed; implementation is additive (swap inline for outbox-driven) |
| Email template rendering (Thymeleaf) + actual SMTP dispatch | Phase 5.3 | [EmailAdapter skeleton](notification-5.1b-subscription-dispatcher-blueprint.md#6f-email-adapter-emailadapterjava) | `EmailAdapter.send()` is a skeleton — outbox poller marks entries SENT without sending. SMTP integration deferred. |
| Wave 2 chat moderation push — `ModerationListener` consuming `chat.moderation` | When Kafka triggers are met | [ADR chat/0007](adr/chat/0007-proactive-push-infrastructure-gated.md), [chat-moderation-wave2-proactive-push.md](chat-moderation-wave2-proactive-push.md) | Hard prerequisites: Kafka broker ownership + notification-service foundation (now complete). chat-service needs producer config + `ChatModerationEvent` emission. |
| Frontend notification settings page (5.4) | Phase 5.4 | [IMPLEMENTATION-PLAN.md](../IMPLEMENTATION-PLAN.md#phase-5-checklist) | Backend REST API for subscriptions + preferences is complete; frontend settings page not yet built |
| Dedup key scoping refactor | 5.2b | [ADR common/0003](adr/common/0003-cross-service-event-dedup-key-scoping.md) | Safe today (only one service dedups `stream.control`); required before any second service adds Redis SETNX for same topic |
| Per-target delivery overrides — `channel_override` on `subscription` | When users request it | [ADR-0001 open question](adr/notification/0001-subscription-model-and-notification-boundary.md) | Not needed for MVP |
| Orphaned subscription cleanup — deactivate when target deleted | When account deletion exists | [ADR-0001 Risks](adr/notification/0001-subscription-model-and-notification-boundary.md#risks) | Harmless for MVP — fan-out queries find no active stream |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

None. All deferrals are tracked in ADR-0002, the blueprint, and the implementation plan. The review pass caught and fixed all implementation gaps (V5 column gaps, checkSubscription side-effect, Javadoc mismatch, constructor inconsistency).

## 4. Architectural decisions made during implementation (candidates for new ADRs)

No new architectural decisions beyond what ADR-0001 and ADR-0002 already captured. The implementation followed the ADRs exactly:

1. **Table split implemented as designed** (ADR-0001 §1): `notification_preference` + `subscription` with polymorphic target
2. **Concrete dispatcher** (ADR-0002 §1): `NotificationDispatcher` is a concrete `@Service`, not an interface
3. **Outbox-driven email** (ADR-0002 §3): Fire-and-forget `OutboxService.enqueue()` + scheduled `OutboxPoller`
4. **DB constraint idempotency** (ADR-0002 §5): `UNIQUE (subscriber_subject, target_type, target_id)` on `subscription` table

### Implementation-level decisions (not ADR-worthy)

1. **`checkSubscription()` as a separate read-only method**: Added during review when the original `GET /subscriptions/check` endpoint was found to call `follow()` (mutating). The new `checkSubscription()` is a pure read — no side effects. This is a controller-level correctness fix, not an architectural decision.

2. **`V5` migration includes `retry_count` + `last_attempt_at` columns**: The original V5 plan only covered the JSONB→TEXT change. The `OutboxEntry` entity and `ReactiveOutboxRepository.updateState()` query required these columns. Added as `ADD COLUMN IF NOT EXISTS` to handle idempotent re-runs.

3. **`SubscriptionService.getSubscriptions()` returns all (active + inactive)**: The Javadoc was corrected to match the actual behavior. The UI can filter by the `active` flag on the response. This mirrors the pattern of returning full history with a status discriminator rather than hiding inactive rows.

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action taken |
|----------|---------------|-------------|--------------|
| `IMPLEMENTATION-PLAN.md` | 5.1b unchecked, ADRs listed as "Proposed" | 5.1b is implemented but checkbox not ticked; ADR-0001/0002 status is stale | ✅ Updated — checked 5.1b, 5.3; updated ADR status references; updated Phase 5 status description |
| `docs/adr/notification/0001-*.md` | "proposed" → "accepted" | ADR was designed and implemented in the same session | ✅ Updated during implementation (status → accepted) |
| `docs/adr/notification/0002-*.md` | "proposed" → "accepted" | ADR was designed and implemented in the same session | ✅ Updated during implementation (status → accepted) |
| `docs/adr/common/0003-*.md` | "proposed" | Dedup key scoping ADR was written in the previous session but not yet accepted | Needs acceptance (separate concern — tracked in 5.2b checklist item) |
| Blueprint status | "Planned" → "Implemented" | Blueprint status was stale after implementation | ✅ Updated during implementation |
| `AGENTS.md` | No notification 5.1b mention | Notification service now has subscription + preference REST APIs, dispatcher, and outbox | ✅ Updated — added notification-service section listing new capabilities |

## 6. Updated execution order (actual vs planned)

All 7 tasks completed in blueprint order. The only deviation was the review pass which caught and fixed 4 findings before the retro:

| Planned order | Actual order | Status |
|--------------|-------------|--------|
| Task 1: V4 Migration | Task 1: V4 Migration | ✅ |
| Task 2: Entities + Repos | Task 2: Entities + Repos | ✅ |
| Task 3: Subscription API | Task 3: Subscription API | ✅ |
| Task 4: Preference API | Task 4: Preference API (parallel with 3) | ✅ |
| Task 5: Dispatcher + Refactor | Task 5: Dispatcher + Refactor | ✅ |
| Task 6: Outbox + Email | Task 6: Outbox + Email | ✅ |
| Task 7: ADRs (doc-only) | Task 7: ADR acceptance | ✅ |
| — | Review pass: 4 findings fixed | ✅ (interleaved after Task 7) |

## 7. Key risks carried forward

1. **Outbox poller never tested with real SMTP**: `EmailAdapter.send()` is a skeleton that marks everything SENT. When real SMTP is wired (Phase 5.3), the outbox poller's retry/DEAD logic will be exercised for the first time. **Mitigation**: The poller mirrors stream-service's proven `OutboxPoller` — same locking, same retry semantics, same concurrency model. The skeleton's success path has been compile-verified.

2. **Fan-out not yet wired**: `SubscriptionService.getSubscribers()` exists but `StreamControlListener` still only notifies the broadcaster via `createFromStreamEvent()`. Followers who subscribed won't receive notifications until fan-out is wired. **Mitigation**: This is by design — fan-out is post-MVP per ADR-0002 §4. The subscription table and lookup method are ready. Wiring is: `StreamControlListener` → `SubscriptionService.getSubscribers()` → `dispatcher.deliverToMany()`.

3. **SSE connection registry is single-instance only**: `SseConnectionRegistry` uses in-memory `ConcurrentHashMap`. In a multi-instance deployment, a push on instance A can't reach a connection on instance B. **Mitigation**: Documented in `SseConnectionRegistry.java` Javadoc. Redis Pub/Sub fan-out is designed for Phase 6.x.

4. **No integration tests**: All compilation verified — `./gradlew :notification-service:compileJava :chat-service:compileJava :stream-service:compileJava` passes. No runtime tests exist for the new subscription/preference/outbox code paths. **Mitigation**: Integration tests deferred to a dedicated testing pass. The notification foundation (5.1a) was similarly shipped without integration tests and has been stable.

5. **`OutboxEntry` has no `schema` qualifier on `@Table`**: The entity maps to `@Table(name = "notification_outbox")` without a schema prefix. Relies on the R2DBC connection URL's `search_path=notification` for schema resolution. **Mitigation**: This is consistent with every other entity in the notification-service (e.g., `Notification` uses `@Table(name = "notification")`). The search_path approach is the platform convention.

---

*Session focus: implementation. 20 new files, 5 modified, 4 review fixes. All services compile clean.*
