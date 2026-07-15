# Blueprint: Notification Hub Completion — 5.1b Subscription + Dispatcher + Wave 2 Path

**Date:** 2026-07-15 (revised 2026-07-16)
**Status:** Planned
**Depends on:** 5.0b (Kafka consumer), 5.1a (Notification core), 5.2a (StreamControlListener wiring), 5.2b (SSE delivery)
**Unlocks:** 5.2b follower fan-out, Wave 2 chat moderation proactive push
**ADRs:** [0001](adr/notification/0001-subscription-model-and-notification-boundary.md), [0002](adr/notification/0002-notification-delivery-architecture.md)

## Summary

The notification-service foundation is built: domain entity (`Notification`), persistence
(`ReactiveNotificationRepository`), REST API (`GET/POST /v1/notifications`), SSE delivery
(`SseConnectionRegistry` + `NotificationSseController`), and Kafka consumer wiring
(`StreamControlListener` → `NotificationService.createFromStreamEvent()`).

What's missing is the **subscription layer** — the answer to "who should be notified?".
The `channel_subscription` table exists (V1 migration) but has **no entity, no repository,
no service, no REST API**. Without it, notifications only reach the broadcaster (hardcoded
`event.broadcasterSubject()`). Follower fan-out, category preferences, and per-channel
toggles are all blocked.

This plan covers:
- **Schema redesign:** Split `channel_subscription` → `notification_preference` (delivery
  preferences: channel, topic_glob, active) + `subscription` (follow targets: polymorphic
  `target_type` + `target_id`). See [ADR-0001](adr/notification/0001-subscription-model-and-notification-boundary.md).
- **Subscription CRUD:** Follow/unfollow REST API with DB-constraint idempotency, Redis
  SETNX at entry points for double-click guard
- **Preference CRUD:** Per-channel delivery preferences (in_app, email, push) with
  topic_glob category filters
- **NotificationDispatcher:** Concrete `@Service` facade — persist → SSE push → outbox
  enqueue in a single pipeline. See [ADR-0002](adr/notification/0002-notification-delivery-architecture.md).
- **Outbox + email:** V5 migration (JSONB → TEXT), outbox entity/repo/service/poller
  mirroring stream-service pattern, `EmailAdapter` via Spring Mail
- **Fan-out architecture:** Designed (outbox-driven `FanOutJob` + poller), inline for MVP.
  Fan-out wiring deferred — subscription table + follow API built now.
- **Wave 2 path:** What the subscription layer unlocks — chat moderation push via new
  `chat.moderation` Kafka topic

## Architecture Overview

```
                       ┌─────────────────────────────┐
                       │    NotificationDispatcher    │  ← Concrete @Service facade
                       │    (persist → SSE → outbox)  │
                       └─────────────┬───────────────┘
                                     │
           ┌─────────────────────────┼──────────────────────┐
           │                         │                      │
     Step 1: persist           Step 2: SSE push       Step 3: outbox enqueue
           │                         │                      │
           ▼                         ▼                      ▼
      PostgreSQL              SseConnectionRegistry    notification_outbox
      (required, sync)        (fire-and-forget)              │
                                                            ▼
                                                     OutboxPoller (@Scheduled)
                                                            │
                                                     ┌──────┴──────┐
                                                     ▼              ▼
                                               EmailAdapter    (future)
                                               (Spring Mail)   PushAdapter
```

```
Kafka stream.control ──► StreamControlListener ──► SubscriptionService.getSubscribers()
                                                         │
                                                         ▼
                                             List<recipientSubject>
                                                         │
                                                         ▼
                                             NotificationDispatcher.deliverToMany()
                                                         │
                                             ┌───────────┼───────────┐
                                             ▼           ▼           ▼
                                         persist()   ssePush()   outboxEnqueue()
                                         (per user)  (per user)  (per user)
```

**Fan-out for MVP**: inline in the reactive chain, offloaded via `subscribeOn(Schedulers.boundedElastic())`.
**Fan-out at scale**: outbox-driven — write one `FanOutJob` row, poller processes in chunks (designed in [ADR-0002](adr/notification/0002-notification-delivery-architecture.md), implemented when subscriber counts demand it).

## Patterns to Mirror

| Category | Source File | Pattern |
|----------|------------|---------|
| Entity | `notification-service:Notification.java` | `Persistable<UUID>` + `@Transient isNew` + `@Table("notification.channel_subscription")` + static `create()` factory |
| Repository | `notification-service:ReactiveNotificationRepository.java` | `ReactiveCrudRepository` + derived query methods, return `Flux`/`Mono` |
| Service | `notification-service:NotificationService.java` | `@Service` + `@RequiredArgsConstructor` + `private static final Logger log` + Javadoc on every public method |
| Controller | `notification-service:NotificationController.java` | `@RestController` + `@RequestMapping("/v1")` + `@AuthenticationPrincipal Jwt jwt` → extract `jwt.getSubject()` |
| DTO | `notification-service:NotificationResponse.java` | Java `record` + static `from(Entity)` factory + `@JsonProperty` where needed |
| Error | `notification-service:NotificationApiError.java` + `NotificationExceptionHandler.java` | `record(String code, String message)` + `@RestControllerAdvice` |
| Outbox | `stream-service:OutboxPoller.java` | `FOR UPDATE SKIP LOCKED` + `@Scheduled` fixed-delay + `OutboxEntity` with `state` field |
| Enum converter | `notification-service:StringToCategoryConverter.java` | `@ReadingConverter` + `@WritingConverter` pair registered in `R2dbcConfig` |
| SSE push | `notification-service:SseConnectionRegistry.push()` | `tryEmitNext()` fire-and-forget, log failures, never throw |

## Confirmed Findings (Deep-Dive Verification)

These details were verified by reading the actual source files on 2026-07-16:

| Finding | Source | Detail |
|---------|--------|--------|
| `channel_subscription` conflates delivery preference + follow target | V1 migration | Split into `notification_preference` + `subscription` in V4 — see [ADR-0001](adr/notification/0001-subscription-model-and-notification-boundary.md) |
| `notification_outbox.payload` is `JSONB` | V1 migration | Will cause R2DBC wire-type mismatch — V5 migration (mirror V3) |
| `ModerationService.updateBanDuration` Wave 2 marker | `ModerationService.java:84` | Exact line: `// Wave 2 (ADR-0007): emit a chat.moderation duration-delta event` |
| chat-service has NO producer config | `application.yml:35-46` | Consumer-only; only `dlqPublisher` KafkaTemplate exists (for DLQ) |
| chat-service local `StreamEvent` record | `chat-service:messaging/StreamEvent.java` | Subset of common `StreamEvent` with `@JsonIgnoreProperties(ignoreUnknown = true)` |
| Stream-service outbox uses `FOR UPDATE SKIP LOCKED` | `OutboxEventRepository.java:24-31` | Pattern to mirror; polls `published = false`, deletes on success, retries on failure |
| `NotificationService.createFromStreamEvent` does persist+SSE inline | `NotificationService.java:54-117` | Duplicated for STREAM_STARTED/ENDED — dispatcher extract removes duplication |
| `SseConnectionRegistry` is single-instance only | `SseConnectionRegistry.java:18` | Comment: "Single-instance only. Redis Pub/Sub fan-out (Phase 6.x) addresses this" |
| Channel header has Follow + Subscribe buttons, both disabled | `channel.page.html` lines 16-30 | Follow (heart, outlined), Subscribe (star) — shells waiting for backend |
| Platform has two-tier subscription model | User intent | Follow = notifications only. Subscribe = notifications + benefits (private streams, badges, emotes, ad-free). Notification-service only cares about the notification intent. |
| `NotificationDispatcher` is a concrete facade, not an interface | [ADR-0002](adr/notification/0002-notification-delivery-architecture.md) | Pipeline composition: persist → SSE → outbox. Channels are additive, never alternative. |
| Subscription idempotency via DB unique constraint | [ADR-0002 §5](adr/notification/0002-notification-delivery-architecture.md#5-subscription-idempotency-via-database-constraint) | Not Redis SETNX — REST requests lack a stable dedup key until Phase 6.1 idempotency keys exist |
| Email adapter is in scope for this phase | User direction | Spring Mail + outbox-driven dispatch (not inline in the reactive chain) |

## Revised Schema Design

### `notification.notification_preference` (V4: renamed from `channel_subscription`)

```sql
-- V1 columns preserved + updated_at added
CREATE TABLE notification.notification_preference (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscriber_subject  VARCHAR(128) NOT NULL,
    channel             VARCHAR(64) NOT NULL,     -- "in_app", "email", "push"
    topic_glob          VARCHAR(256),             -- category filter, null = all
    active              BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,              -- NEW
    CONSTRAINT uq_notification_preference
        UNIQUE (subscriber_subject, channel)
);
CREATE INDEX ix_notification_preference_subject
    ON notification.notification_preference (subscriber_subject);
```

### `notification.subscription` (V4: new)

```sql
CREATE TABLE notification.subscription (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscriber_subject  VARCHAR(128) NOT NULL,
    target_id           VARCHAR(128) NOT NULL,    -- broadcaster subject, room key, session ID
    target_type         VARCHAR(64) NOT NULL,     -- "CHANNEL", "CHAT_ROOM", "STREAM_SESSION"
    active              BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_subscription
        UNIQUE (subscriber_subject, target_type, target_id)
);
CREATE INDEX ix_subscription_target
    ON notification.subscription (target_type, target_id)
    WHERE active = true;
```

## Task Breakdown

---

### Task 1: V4 Migration — Schema Redesign

**Why first:** The V1 `channel_subscription` table conflates delivery preferences with follow
targets. Before any entity or service code is written, the schema must reflect the split
design from [ADR-0001](adr/notification/0001-subscription-model-and-notification-boundary.md).

#### 1a. Migration: `V4__split_subscription_tables.sql`

**File:** `notification-service/src/main/resources/db/migration/V4__split_subscription_tables.sql`

```sql
-- Rename existing table (columns already match notification_preference)
ALTER TABLE notification.channel_subscription
    RENAME TO notification_preference;

-- Rename index
ALTER INDEX IF EXISTS ix_channel_subscription_subject
    RENAME TO ix_notification_preference_subject;

-- Add unique constraint — one preference row per (user, channel)
ALTER TABLE notification.notification_preference
    ADD CONSTRAINT uq_notification_preference
    UNIQUE (subscriber_subject, channel);

-- Add updated_at for tracking preference mutations
ALTER TABLE notification.notification_preference
    ADD COLUMN updated_at TIMESTAMPTZ;

-- New table: what the user wants notifications about
CREATE TABLE notification.subscription (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscriber_subject  VARCHAR(128) NOT NULL,
    target_id           VARCHAR(128) NOT NULL,
    target_type         VARCHAR(64) NOT NULL,
    active              BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_subscription
        UNIQUE (subscriber_subject, target_type, target_id)
);

CREATE INDEX ix_subscription_target
    ON notification.subscription (target_type, target_id)
    WHERE active = true;
```

**Validate:** `./gradlew :notification-service:compileJava` (Flyway runs automatically, migration must succeed)

---

### Task 2: Entities + Repositories

#### 2a. Entity: `NotificationPreference.java`

**File:** `notification-service/.../domain/NotificationPreference.java`

Follow the exact pattern of `Notification.java` — `Persistable<UUID>` + `@Transient isNew` + `@Table("notification_preference")` + static `create()` factory.

Fields: `id`, `subscriberSubject`, `channel`, `topicGlob`, `active`, `createdAt`, `updatedAt`.
Domain methods: `activate()`, `deactivate()`, `updateTopicGlob(String)`, `touch()`.

#### 2b. Entity: `Subscription.java`

**File:** `notification-service/.../domain/Subscription.java`

Fields: `id`, `subscriberSubject`, `targetId`, `targetType`, `active`, `createdAt`.
Static factory: `Subscription.create(subscriberSubject, targetType, targetId, now)`.
Domain methods: `deactivate()` (unfollow — soft delete, not hard delete).

#### 2c. Repository: `ReactiveNotificationPreferenceRepository.java`

```java
public interface ReactiveNotificationPreferenceRepository
        extends ReactiveCrudRepository<NotificationPreference, UUID> {

    Flux<NotificationPreference> findBySubscriberSubject(String subscriberSubject);
    Flux<NotificationPreference> findBySubscriberSubjectAndActiveTrue(String subscriberSubject);
    Mono<NotificationPreference> findBySubscriberSubjectAndChannel(
            String subscriberSubject, String channel);
}
```

#### 2d. Repository: `ReactiveSubscriptionRepository.java`

```java
public interface ReactiveSubscriptionRepository
        extends ReactiveCrudRepository<Subscription, UUID> {

    Flux<Subscription> findBySubscriberSubject(String subscriberSubject);
    Flux<Subscription> findByTargetTypeAndTargetIdAndActiveTrue(
            String targetType, String targetId);
    Mono<Subscription> findBySubscriberSubjectAndTargetTypeAndTargetId(
            String subscriberSubject, String targetType, String targetId);
    Mono<Boolean> existsBySubscriberSubjectAndTargetTypeAndTargetId(
            String subscriberSubject, String targetType, String targetId);
    Flux<Subscription> findBySubscriberSubjectAndTargetType(
            String subscriberSubject, String targetType);
}
```

**Validate:** `./gradlew :notification-service:compileJava`

---

### Task 3: Subscription Service + REST API (Follow/Unfollow)

**Why:** This is the backend for the Follow button in the channel header UI. Users need to
follow/unfollow targets and see their active follows.

#### 3a. Service: `SubscriptionService.java`

**File:** `notification-service/.../application/SubscriptionService.java`

Follow the exact pattern of `NotificationService.java`:
- `@Service` + `@RequiredArgsConstructor` + `private static final Logger log`
- Javadoc on every public method
- All operations scoped to the calling user via `subscriberSubject`

Methods:

| Method | Returns | Logic |
|--------|---------|-------|
| `follow(subscriberSubject, targetType, targetId)` | `Mono<SubscriptionResponse>` | Check exists → if yes, return existing (idempotent). If no, create `Subscription` with `active=true`, save, return. DB unique constraint catches race; map `DataIntegrityViolationException` to `SubscriptionAlreadyExistsException`. |
| `unfollow(id, subscriberSubject)` | `Mono<Void>` | Ownership-scoped: find by id + subscriberSubject → `deactivate()` → save. 404 if not found. Soft delete (active=false), not hard delete. |
| `getSubscriptions(subscriberSubject)` | `Flux<SubscriptionResponse>` | All active subscriptions for the user, newest first |
| `getSubscriptionsByType(subscriberSubject, targetType)` | `Flux<SubscriptionResponse>` | Filtered by target type (e.g., "show my CHANNEL follows") |
| `getSubscribers(targetType, targetId)` | `Flux<Subscription>` | **Used by fan-out.** All active subscribers for a target. Returns full entities for dispatcher. |

#### 3b. DTOs

**`SubscriptionRequest.java`:**
```java
public record SubscriptionRequest(
        @NotBlank String targetType,
        @NotBlank String targetId
) {}
```

**`SubscriptionResponse.java`:**
```java
public record SubscriptionResponse(
        UUID id,
        String subscriberSubject,
        String targetType,
        String targetId,
        boolean active,
        OffsetDateTime createdAt
) {
    public static SubscriptionResponse from(Subscription sub) { ... }
}
```

#### 3c. Controller: `SubscriptionController.java`

Follow exact patterns from `NotificationController.java` — `@RestController` + `@RequiredArgsConstructor` + `@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)` + extract `jwt.getSubject()` as `subscriberSubject`.

Endpoints:

| Method | Path | Returns | Auth |
|--------|------|---------|------|
| `PUT` | `/v1/subscriptions` | `201 SubscriptionResponse` | JWT `sub` → subscriberSubject. Body: `SubscriptionRequest`. 409 if already exists. |
| `DELETE` | `/v1/subscriptions/{id}` | `204 No Content` | Ownership-scoped (JWT `sub` must match subscription's `subscriber_subject`). Soft delete. |
| `GET` | `/v1/subscriptions` | `200 [SubscriptionResponse]` | Scoped to JWT `sub`. Optional query param: `target_type`. |
| `GET` | `/v1/subscriptions/check?target_type=CHANNEL&target_id={id}` | `200 SubscriptionResponse` or `404` | Check if following. Convenience endpoint for button state. |

#### 3d. Error handling

Add `SubscriptionAlreadyExistsException` (inner class of `SubscriptionService`, mirrors `NotificationNotFoundException`). Map `DataIntegrityViolationException` in `SubscriptionService.follow()` — not in the controller. Add handler in `NotificationExceptionHandler` (409 Conflict).

**Validate:** `./gradlew :notification-service:compileJava`

---

### Task 4: Preference Service + REST API (Notification Settings)

**Why:** Users need to manage delivery preferences — which channels (in_app, email, push)
and which categories (STREAM_*, CHAT_*, etc.). This is the backend for the notification
settings page.

#### 4a. Service: `PreferenceService.java`

Methods:

| Method | Returns | Logic |
|--------|---------|-------|
| `upsertPreference(subscriberSubject, channel, topicGlob)` | `Mono<PreferenceResponse>` | Find existing by (subscriberSubject, channel) → update or create. One row per (user, channel). |
| `getPreferences(subscriberSubject)` | `Flux<PreferenceResponse>` | All preferences for the user |
| `updatePreference(id, subscriberSubject, active, topicGlob)` | `Mono<PreferenceResponse>` | Ownership-scoped load → mutate → save |
| `deletePreference(id, subscriberSubject)` | `Mono<Void>` | Ownership-scoped delete |

#### 4b. DTOs

**`PreferenceRequest.java`:** `record(String channel, String topicGlob)`
**`PreferenceResponse.java`:** `record(UUID id, String channel, String topicGlob, boolean active, OffsetDateTime createdAt, OffsetDateTime updatedAt)` + `from(NotificationPreference)`

#### 4c. Controller: `PreferenceController.java`

| Method | Path | Returns |
|--------|------|---------|
| `PUT` | `/v1/preferences` | `200 PreferenceResponse` (upsert) |
| `GET` | `/v1/preferences` | `200 [PreferenceResponse]` |
| `PATCH` | `/v1/preferences/{id}` | `200 PreferenceResponse` |
| `DELETE` | `/v1/preferences/{id}` | `204 No Content` |

**Validate:** `./gradlew :notification-service:compileJava`

---

### Task 5: `NotificationDispatcher` + Refactor `NotificationService`

**Why:** Extract the duplicated persist+SSE pattern from `NotificationService.createFromStreamEvent()`
into a single, concrete delivery facade. Per [ADR-0002](adr/notification/0002-notification-delivery-architecture.md),
this is a concrete `@Service`, not an interface.

#### 5a. Concrete dispatcher: `NotificationDispatcher.java`

**File:** `notification-service/.../application/NotificationDispatcher.java`

```java
@Service
@RequiredArgsConstructor
public class NotificationDispatcher {
    private static final Logger log = ...;
    private final ReactiveNotificationRepository repository;
    private final SseConnectionRegistry sseRegistry;
    private final OutboxService outboxService;

    /**
     * Deliver a notification through all active channels.
     *
     * <p>Step 1 — persist (required, synchronous).
     * <p>Step 2 — SSE push (fire-and-forget, best-effort).
     * <p>Step 3 — outbox enqueue (fire-and-forget, for non-in_app channels).
     */
    public Mono<Void> deliver(Notification notification) {
        return repository.save(notification)
                .doOnSuccess(saved -> {
                    log.info("Notification delivered: id={} category={} recipient={}",
                            saved.getId(), saved.getCategory(),
                            saved.getRecipientSubject());
                    sseRegistry.push(saved.getRecipientSubject(),
                            NotificationResponse.from(saved));
                    outboxService.enqueue(saved);
                })
                .then();
    }

    /** Fan-out to multiple recipients with bounded concurrency. */
    public Mono<Void> deliverToMany(List<Notification> notifications, int concurrency) {
        return Flux.fromIterable(notifications)
                .flatMap(this::deliver, concurrency)
                .then();
    }
}
```

#### 5b. Refactor `NotificationService.java`

Replace the duplicated `repository.save(...).doOnSuccess(...).then()` blocks in
`createFromStreamEvent` with `dispatcher.deliver(n)`.

Changes:
- Add `private final NotificationDispatcher dispatcher;` to constructor
- Remove `SseConnectionRegistry` dependency (moves to `NotificationDispatcher`)
- In STREAM_STARTED and STREAM_ENDED cases: `Notification n = ...; yield dispatcher.deliver(n);`
- No change to no-op event types (STREAM_CREATED, STREAM_SCHEDULED, STREAM_CANCELLED)

**Validate:** `./gradlew :notification-service:compileJava`
(This is a pure refactor — same behavior, different class.)

---

### Task 6: Outbox + Email Adapter

**Why:** Email delivery is asynchronous via the outbox pattern — decoupled from the hot
notification path. The `notification_outbox` table (V1) already exists. The V5 migration
changes `payload` from JSONB to TEXT (mirroring the V3 pattern for `metadata`).

#### 6a. V5 Migration: `V5__change_outbox_payload_to_text.sql`

```sql
ALTER TABLE notification.notification_outbox
    ALTER COLUMN payload TYPE TEXT;
```

#### 6b. Outbox entity: `OutboxEntry.java`

**File:** `notification-service/.../domain/OutboxEntry.java`

Pattern: `Persistable<UUID>` + `@Transient isNew` + static `create()` factory.

Fields: `id`, `aggregateType` ("notification"), `aggregateId` (notification UUID),
`payload` (TEXT — JSON string), `state` ("PENDING" / "SENT" / "FAILED" / "DEAD"),
`retryCount`, `lastAttemptAt`, `createdAt`.

#### 6c. Repository: `ReactiveOutboxRepository.java`

```java
public interface ReactiveOutboxRepository
        extends ReactiveCrudRepository<OutboxEntry, UUID> {

    @Query("""
        SELECT * FROM notification.notification_outbox
        WHERE state = 'PENDING'
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """)
    Flux<OutboxEntry> pollPending(int limit);

    @Modifying
    @Query("""
        UPDATE notification.notification_outbox
        SET state = :state, retry_count = :retryCount, last_attempt_at = :lastAttemptAt
        WHERE id = :id
        """)
    Mono<Void> updateState(UUID id, String state, int retryCount, OffsetDateTime lastAttemptAt);
}
```

Mirrors stream-service `OutboxEventRepository` with `FOR UPDATE SKIP LOCKED`.

#### 6d. Outbox service: `OutboxService.java`

```java
@Service
@RequiredArgsConstructor
public class OutboxService {
    private final ReactiveOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /** Write to outbox — called by NotificationDispatcher for every delivery. */
    public void enqueue(Notification notification) {
        String payload = objectMapper.writeValueAsString(
                NotificationResponse.from(notification));
        OutboxEntry entry = OutboxEntry.create(
                "notification", notification.getId().toString(),
                payload, OffsetDateTime.now(ZoneOffset.UTC));
        outboxRepository.save(entry)
                .subscribe(
                    saved -> log.debug("Outbox entry created: id={}", saved.getId()),
                    err -> log.warn("Failed to write outbox entry for notification {}: {}",
                            notification.getId(), err.getMessage())
                );
    }
}
```

Fire-and-forget (`subscribe()` not chained) — failures do not roll back persist.

#### 6e. Outbox poller: `OutboxPoller.java`

Scheduled poller mirroring `stream-service:OutboxPoller`:

```java
@Component
public class OutboxPoller {
    @Scheduled(fixedDelayString = "${notification.outbox.poll-interval:5000}")
    public void poll() {
        outboxRepository.pollPending(50)
                .flatMap(entry -> {
                    String channel = determineChannel(entry);  // from notification_preference
                    return switch (channel) {
                        case "email" -> emailAdapter.send(entry);
                        default -> {
                            log.debug("No adapter for channel={}, entry={}", channel, entry.getId());
                            yield outboxRepository.updateState(
                                    entry.getId(), "SENT", 0, OffsetDateTime.now(ZoneOffset.UTC));
                        }
                    };
                })
                .subscribe(...);
    }
}
```

Add `@EnableScheduling` to `NotificationApplication.java`.

#### 6f. Email adapter: `EmailAdapter.java`

```java
@Service
public class EmailAdapter {
    private final JavaMailSender mailSender;
    @Value("${notification.email.from}") private String from;

    public Mono<Void> send(OutboxEntry entry) {
        // Deserialize payload → build MimeMessage → send
        // Mark SENT on success, FAILED on error (retried next poll)
    }
}
```

Add Spring Mail dependency (`spring-boot-starter-mail`) to `build.gradle.kts`.
Add SMTP config to `application.yml` (`spring.mail.*` + `notification.email.from`).

**Validate:** `./gradlew :notification-service:compileJava`

---

### Task 7: Fan-Out Design (ADR Only, No Code)

**Why:** The fan-out architecture is designed now so the schema and service APIs support it.
Implementation uses the inline path for MVP and switches to outbox-driven when subscriber
counts warrant. See [ADR-0002 §4](adr/notification/0002-notification-delivery-architecture.md#4-outbox-driven-fan-out-architecture-designed-now-inline-for-mvp).

**No code changes in this task.** The ADRs capture the design. The `SubscriptionService.getSubscribers()`
method (Task 3a) provides the lookup needed when fan-out is wired.

**Future fan-out wiring (post-MVP, not in this blueprint):**
- `StreamControlListener` calls `NotificationService.createAndFanOut(StreamEvent)` instead of `createFromStreamEvent`
- `createAndFanOut` writes one `FanOutJob` row + returns (enqueue phase)
- `FanOutPoller` processes PENDING jobs in chunks (process phase)
- Inline fan-out is acceptable for MVP: iterate subscribers in the reactive chain, offloaded via `subscribeOn(Schedulers.boundedElastic())`

**Why first:** The `channel_subscription` table has no corresponding entity or repository.
Without these, no other task can query or mutate subscriptions.

#### 1a. Entity: `ChannelSubscription.java`

**File:** `notification-service/src/main/java/.../domain/ChannelSubscription.java`

Follow the exact pattern of `Notification.java`:

```java
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
@Table(name = "channel_subscription")
public class ChannelSubscription implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew;

    @Column("subscriber_subject")
    private String subscriberSubject;

    private String channel;       // "in_app", "email", "push"

    @Column("topic_glob")
    private String topicGlob;     // null = all categories

    private boolean active;

    @Column("created_at")
    private OffsetDateTime createdAt;

    // static factory
    public static ChannelSubscription create(
            String subscriberSubject,
            String channel,
            String topicGlob,
            boolean active,
            OffsetDateTime now) { ... }

    // domain methods
    public void activate()   { this.active = true; }
    public void deactivate() { this.active = false; }
}
```

**Validate:** Compilation only at this stage — `./gradlew :notification-service:compileJava`

#### 1b. Repository: `ReactiveChannelSubscriptionRepository.java`

**File:** `notification-service/src/main/java/.../infrastructure/persistence/ReactiveChannelSubscriptionRepository.java`

```java
public interface ReactiveChannelSubscriptionRepository
        extends ReactiveCrudRepository<ChannelSubscription, UUID> {

    // All subscriptions for a user (for settings page)
    Flux<ChannelSubscription> findBySubscriberSubject(String subscriberSubject);

    // Active subscriptions for a user (for fan-out lookup)
    Flux<ChannelSubscription> findBySubscriberSubjectAndActiveTrue(String subscriberSubject);

    // Specific channel subscription (for update/delete)
    Mono<ChannelSubscription> findByIdAndSubscriberSubject(UUID id, String subscriberSubject);

    // Check if already subscribed (for idempotency)
    Mono<Boolean> existsBySubscriberSubjectAndChannelAndTopicGlob(
            String subscriberSubject, String channel, String topicGlob);
}
```

**Validate:** `./gradlew :notification-service:compileJava`

---

### Task 2: `SubscriptionService` — CRUD Orchestration

**File:** `notification-service/src/main/java/.../application/SubscriptionService.java`

Follow the exact pattern of `NotificationService.java`:
- `@Service` + `@RequiredArgsConstructor`
- `private static final Logger log`
- Javadoc on every public method
- All operations scoped to the calling user via `subscriberSubject`

**Methods:**

| Method | Returns | Logic |
|--------|---------|-------|
| `subscribe(subscriberSubject, channel, topicGlob, now)` | `Mono<SubscriptionResponse>` | Idempotent — check `existsBy` first. Create `ChannelSubscription` with `active=true`. Return response. |
| `unsubscribe(id, subscriberSubject)` | `Mono<Void>` | Ownership-scoped delete — `findByIdAndSubscriberSubject` then `delete`. 404 if not found or not owned. |
| `getSubscriptions(subscriberSubject)` | `Flux<SubscriptionResponse>` | All subscriptions for the calling user, newest first |
| `updateSubscription(id, subscriberSubject, active, topicGlob)` | `Mono<SubscriptionResponse>` | Ownership-scoped load → mutate → save. 404 if not found. |
| `getActiveSubscribers(channel)` | `Flux<ChannelSubscription>` | **Used by fan-out.** All active subscriptions for a channel (e.g., "in_app"). Returns full entities for the dispatcher. |

**Dependencies injected:**
```java
private final ReactiveChannelSubscriptionRepository subscriptionRepository;
```

**Validate:** `./gradlew :notification-service:compileJava`

---

### Task 3: Subscription REST API

**Files:** `SubscriptionController.java`, `SubscriptionRequest.java`, `SubscriptionResponse.java`

#### 3a. DTOs

**`SubscriptionRequest.java`:**
```java
public record SubscriptionRequest(
        @NotBlank String channel,
        String topicGlob   // null = all categories
) {}
```

**`SubscriptionResponse.java`:**
```java
public record SubscriptionResponse(
        UUID id,
        String subscriberSubject,
        String channel,
        String topicGlob,
        boolean active,
        OffsetDateTime createdAt
) {
    public static SubscriptionResponse from(ChannelSubscription sub) { ... }
}
```

#### 3b. Controller: `SubscriptionController.java`

**File:** `notification-service/src/main/java/.../api/SubscriptionController.java`

Follow exact patterns from `NotificationController.java`:
- `@RestController` + `@RequiredArgsConstructor`
- `@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)`
- Extract `jwt.getSubject()` as `subscriberSubject`
- Delegate to `SubscriptionService`

**Endpoints:**

| Method | Path | Returns | Auth |
|--------|------|---------|------|
| `PUT` | `/v1/subscriptions` | `201 SubscriptionResponse` | JWT `sub` → subscriberSubject. Body: `SubscriptionRequest` |
| `DELETE` | `/v1/subscriptions/{id}` | `204 No Content` | Ownership-scoped (JWT `sub` must match subscription's `subscriber_subject`) |
| `GET` | `/v1/subscriptions` | `200 [SubscriptionResponse]` | Scoped to JWT `sub` |
| `PATCH` | `/v1/subscriptions/{id}` | `200 SubscriptionResponse` | Ownership-scoped. Body: `{active: boolean, topicGlob: string?}` |

#### 3c. Error handling

Add `SubscriptionNotFoundException` (inner class of `SubscriptionService`, mirroring `NotificationNotFoundException`).

Add a handler in `NotificationExceptionHandler` (or a new `SubscriptionExceptionHandler` following the same `@RestControllerAdvice` pattern).

**Validate:** `./gradlew :notification-service:compileJava`

---

### Task 4: `NotificationDispatcher` Interface + Default Implementation

**Why:** The dispatcher is the hub seam. Today it does persist + SSE push. Tomorrow it
adds outbox write (for email/push channels). The interface lets us swap implementations
or add decorators without touching callers.

#### 4a. Interface: `NotificationDispatcher.java`

**File:** `notification-service/src/main/java/.../application/NotificationDispatcher.java`

```java
/**
 * Dispatches a notification to all relevant outbound channels.
 *
 * <p>This is the hub seam — every inbound event path
 * (StreamControlListener, future ModerationListener)
 * calls {@code dispatch()} without knowing about persist,
 * SSE, email, or push details.
 */
public interface NotificationDispatcher {

    /**
     * Create and deliver a notification to a single recipient.
     * Persists to PostgreSQL and pushes via SSE if the recipient
     * has an active connection.
     */
    Mono<Void> dispatch(Notification notification);

    /**
     * Create and deliver identical notifications to multiple recipients.
     * Used for follower fan-out — one stream event → N notifications.
     */
    default Mono<Void> dispatchToMany(List<Notification> notifications) {
        return Flux.fromIterable(notifications)
                .flatMap(this::dispatch)
                .then();
    }
}
```

#### 4b. Default Implementation: `DefaultNotificationDispatcher.java`

**File:** `notification-service/src/main/java/.../application/DefaultNotificationDispatcher.java`

```java
@Service
@RequiredArgsConstructor
public class DefaultNotificationDispatcher implements NotificationDispatcher {

    private static final Logger log = ...;
    private final ReactiveNotificationRepository notificationRepository;
    private final SseConnectionRegistry sseConnectionRegistry;

    @Override
    public Mono<Void> dispatch(Notification notification) {
        return notificationRepository.save(notification)
                .doOnSuccess(saved -> {
                    log.info("Notification dispatched: id={} category={} recipient={}",
                            saved.getId(), saved.getCategory(), saved.getRecipientSubject());
                    sseConnectionRegistry.push(
                            saved.getRecipientSubject(),
                            NotificationResponse.from(saved));
                })
                .then();
    }
}
```

**Note:** This extracts the persist+SSE pattern from `NotificationService.createFromStreamEvent()`,
which currently does both inline. After Task 5, `createFromStreamEvent` delegates to the
dispatcher instead — single place for the persist+push pipeline.

**Validate:** `./gradlew :notification-service:compileJava`

---

### Task 5: Refactor `NotificationService` → delegate to Dispatcher

**Why:** `NotificationService.createFromStreamEvent()` currently calls
`notificationRepository.save()` + `sseConnectionRegistry.push()` inline for each
event type. This duplication grows with every new event source. After this task,
`createFromStreamEvent()` builds the `Notification` entity and delegates to
`NotificationDispatcher.dispatch()`.

**Changes to `NotificationService.java`:**
- Add `private final NotificationDispatcher dispatcher;` to constructor
- In `createFromStreamEvent`, replace:
  ```java
  notificationRepository.save(n)
      .doOnSuccess(saved -> {
          log.info(...);
          sseConnectionRegistry.push(saved.getRecipientSubject(),
              NotificationResponse.from(saved));
      })
      .then();
  ```
  with:
  ```java
  dispatcher.dispatch(n);
  ```
- Remove the now-unused `SseConnectionRegistry` dependency (moves to `DefaultNotificationDispatcher`)

**Validate:** `./gradlew :notification-service:compileJava`
(This is a pure refactor — behavior is identical, tests should still pass if any exist.)

---

### Task 6: Outbox Service + Poller Skeleton

**Why:** The `notification_outbox` table (V1) is designed for reliable delivery to
downstream channels (email, push notifications). The outbox pattern — write to outbox
in the same transaction as the notification — guarantees at-least-once delivery even
if the downstream channel is temporarily unavailable. Mirror the stream-service
outbox pattern exactly.

#### 6a. Outbox Entity: `OutboxEntry.java`

**File:** `notification-service/src/main/java/.../domain/OutboxEntry.java`

```java
@Table(name = "notification_outbox")
public class OutboxEntry implements Persistable<UUID> {
    @Id private UUID id;
    @Transient private boolean isNew;

    @Column("aggregate_type") private String aggregateType;   // "notification"
    @Column("aggregate_id")   private String aggregateId;      // notification UUID
    private String payload;   // JSON — stored as TEXT after a future migration
    private String state;     // "PENDING", "SENT", "FAILED"
    @Column("created_at") private OffsetDateTime createdAt;

    public static OutboxEntry create(
            String aggregateType, String aggregateId,
            String payload, OffsetDateTime now) { ... }
}
```

**Note on `payload` column:** V1 has `payload JSONB`. The V3 migration showed that
JSONB causes R2DBC wire-type issues. We'll either:
- Use `io.r2dbc.postgresql.codec.Json` (needs R/W converter pair), OR
- Add a V4 migration to change `payload` to `TEXT` (same pattern as V3 for metadata)

The V4 migration approach is simpler and consistent with the existing V3 pattern.

#### 6b. V4 Migration: `V4__change_outbox_payload_to_text.sql`

```sql
-- Mirror V3 pattern — avoid JSONB converter complexity for the outbox payload.
-- The outbox payload is written as a JSON string but stored as TEXT to prevent
-- R2DBC wire-type mismatches.
ALTER TABLE notification.notification_outbox
    ALTER COLUMN payload TYPE TEXT;
```

#### 6c. Repository: `ReactiveOutboxRepository.java`

```java
public interface ReactiveOutboxRepository
        extends ReactiveCrudRepository<OutboxEntry, UUID> {

    // Poller: claim the oldest PENDING entries with lock
    @Query("""
        SELECT * FROM notification.notification_outbox
        WHERE state = 'PENDING'
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """)
    Flux<OutboxEntry> pollPending(int limit);

    // Update state after processing
    @Modifying
    @Query("""
        UPDATE notification.notification_outbox
        SET state = :state
        WHERE id = :id
        """)
    Mono<Void> updateState(UUID id, String state);
}
```

Pattern mirrors `stream-service`'s `OutboxPoller` with `FOR UPDATE SKIP LOCKED`.

#### 6d. Outbox Service: `OutboxService.java`

```java
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final ReactiveOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    // Write to outbox (called by dispatcher for channels that need
    // reliable delivery — currently a no-op path until email/push exist)
    public Mono<Void> enqueue(Notification notification) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(NotificationResponse.from(notification));
        } catch (Exception e) {
            return Mono.error(new RuntimeException("Failed to serialize outbox payload", e));
        }
        OutboxEntry entry = OutboxEntry.create(
                "notification",
                notification.getId().toString(),
                payload,
                now);
        return outboxRepository.save(entry).then();
    }
}
```

#### 6e. Outbox Poller: `OutboxPoller.java`

Skeleton poller — runs on `@Scheduled` fixed-delay but has no downstream channels yet.
Logs a debug message for each polled entry. Real processing (email dispatch) comes in 5.3.

```java
@Component
public class OutboxPoller {

    private static final Logger log = ...;
    private final ReactiveOutboxRepository outboxRepository;

    @Scheduled(fixedDelayString = "${notification.outbox.poll-interval:5000}")
    public void poll() {
        outboxRepository.pollPending(50)
                .flatMap(entry -> {
                    log.debug("Outbox entry pending: id={} aggregateType={}",
                            entry.getId(), entry.getAggregateType());
                    // TODO(5.3): dispatch to email/push channel
                    return outboxRepository.updateState(entry.getId(), "SENT");
                })
                .subscribe(
                    count -> {},
                    err -> log.error("Outbox poller error", err)
                );
    }
}
```

Add `@EnableScheduling` to `NotificationApplication.java`.

**Validate:** `./gradlew :notification-service:compileJava`

---

### Task 7: Follower Fan-Out (5.2b)

**Why:** Currently, `NotificationService.createFromStreamEvent()` only notifies
`event.broadcasterSubject()`. Followers who subscribed to the streamer should also
get notified. With the subscription layer in place, this is a lookup + fan-out.

**Changes to `NotificationService.java`:**

Add a new method:
```java
/**
 * Create and dispatch stream lifecycle notifications to all followers.
 *
 * <p>Broadcaster gets the notification unconditionally. Followers with an
 * active subscription for the STREAM_LIVE/STREAM_ENDED category get it too.
 * This replaces the single-recipient path in {@link #createFromStreamEvent}.
 */
public Mono<Void> createAndFanOut(StreamEvent event) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    return subscriptionService.getActiveSubscribers("in_app")
            .map(ChannelSubscription::getSubscriberSubject)
            .collectList()
            .flatMapMany(subscribers -> {
                // broadcaster is always included
                if (!subscribers.contains(event.broadcasterSubject())) {
                    subscribers.add(event.broadcasterSubject());
                }
                return Flux.fromIterable(subscribers);
            })
            .flatMap(recipient -> {
                Notification n = buildNotification(event, recipient, now);
                if (n == null) return Mono.empty();
                return dispatcher.dispatch(n);
            })
            .then();
}

private Notification buildNotification(StreamEvent event, String recipient, OffsetDateTime now) {
    return switch (event.eventType()) {
        case "STREAM_STARTED" -> Notification.create(
                recipient, NotificationCategory.STREAM_LIVE,
                "stream.started", "A stream you follow is now live",
                "Stream " + event.streamId() + " is now broadcasting.",
                "{\"streamId\":\"" + event.streamId() + "\"}", now);
        case "STREAM_ENDED" -> Notification.create(
                recipient, NotificationCategory.STREAM_ENDED,
                "stream.ended", "A stream you follow has ended",
                "Stream " + event.streamId() + " has finished broadcasting.",
                "{\"streamId\":\"" + event.streamId() + "\"}", now);
        default -> null;
    };
}
```

**Changes to `StreamControlListener.java`:**
Replace `notificationService.createFromStreamEvent(event)` with
`notificationService.createAndFanOut(event)`.

**Validation note:** This fan-out uses `subscriptionService.getActiveSubscribers("in_app")`,
which returns ALL active in-app subscribers across ALL streamers. For MVP this is acceptable
(small user base). A future optimization would add a per-streamer subscription index
(e.g., `channel_id` column on `channel_subscription`).

However, looking at the current `channel_subscription` schema more carefully:
- `topic_glob VARCHAR(256)` — meant for category filtering (e.g. `STREAM_*`)
- There is NO `channel_id` or `follows_subject` column

This means **follower fan-out requires a schema change** to `channel_subscription`.
We need to decide between:

**Option A: Add `channel_id` column (new migration)**
- `ALTER TABLE notification.channel_subscription ADD COLUMN channel_id VARCHAR(128)`
- Followers subscribe to specific channels (streamers)
- Fan-out: `findByChannelIdAndActiveTrue(channelId)`
- Precise, efficient, matches the domain model

**Option B: Use existing columns with convention**
- Encode the streamer subject in `topic_glob` (e.g., `"STREAM_LIVE:streamerSub"`)
- Fan-out: filter in application layer
- No migration needed, but brittle and slow

**Decision: Option A** — add `channel_id` column. This is a small schema change and
correctly models the domain (a follower subscribes to a specific streamer, with optional
category filters).

#### 7a. V5 Migration: Add `channel_id` to `channel_subscription`

```sql
-- Add channel-level targeting for follower subscriptions.
-- NULL = global subscription (all streamers), non-NULL = specific streamer.
ALTER TABLE notification.channel_subscription
    ADD COLUMN channel_id VARCHAR(128);

-- Composite lookup for fan-out: find all subscribers for a given streamer.
CREATE INDEX IF NOT EXISTS ix_channel_subscription_channel
    ON notification.channel_subscription (channel_id)
    WHERE active = true;
```

#### 7b. Update Entity + Repository + Service

- `ChannelSubscription.java`: add `@Column("channel_id") private String channelId;`
- `ReactiveChannelSubscriptionRepository.java`: add `Flux<ChannelSubscription> findByChannelIdAndActiveTrue(String channelId);`
- `SubscriptionService.java`: add `getFollowers(String channelId)` method
- `SubscriptionRequest.java`: add `String channelId` field

**Validate:** `./gradlew :notification-service:compileJava`

### Wave 2 Path — What This Unlocks

With 5.1b complete, the Wave 2 chat moderation push pipe becomes straightforward:

```
chat-service                           notification-service
────────────                           ────────────────────
ModerationService.ban()                ModerationListener (NEW)
  └─► KafkaTemplate.send(               @KafkaListener("chat.moderation")
        "chat.moderation",                └─► deserialize ChatModerationEvent
        ChatModerationEvent)                    └─► NotificationDispatcher.dispatch()
                                                      ├─► persist (POST /v1/notifications)
                                                      └─► SSE push (GET /v1/notifications/stream)
```

The `NotificationDispatcher` interface designed in Task 4 is the exact seam the
`ModerationListener` will call — no changes needed to the dispatcher when Wave 2 starts.

**Wave 2 now needs only:**
1. **chat-service:** Kafka producer config + `KafkaTemplate<String, String>` bean
2. **chat-service:** `ChatModerationEvent` record + emit in `ModerationService` (wire the existing `// Wave 2 (ADR-0007)` marker)
3. **notification-service:** `ModerationListener` — `@KafkaListener("chat.moderation")` → build `Notification(category=CHAT_MODERATION)` → `dispatcher.deliver()`
4. **frontend:** Handle `CHAT_MODERATION` category in `notification.service.ts` → signal `chat-panel` proactive disable

This is ~6 new files + ~3 modified files across two services. The detailed build plan
already exists at `docs/plans/chat-moderation-wave2-proactive-push.md`.

## Files Summary

| # | File | Action | Task |
|---|------|--------|------|
| 1 | `.../db/migration/V4__split_subscription_tables.sql` | **Create** | 1 |
| 2 | `.../domain/NotificationPreference.java` | **Create** | 2a |
| 3 | `.../domain/Subscription.java` | **Create** | 2b |
| 4 | `.../infrastructure/persistence/ReactiveNotificationPreferenceRepository.java` | **Create** | 2c |
| 5 | `.../infrastructure/persistence/ReactiveSubscriptionRepository.java` | **Create** | 2d |
| 6 | `.../application/SubscriptionService.java` | **Create** | 3a |
| 7 | `.../api/dto/SubscriptionRequest.java` | **Create** | 3b |
| 8 | `.../api/dto/SubscriptionResponse.java` | **Create** | 3b |
| 9 | `.../api/SubscriptionController.java` | **Create** | 3c |
| 10 | `.../application/PreferenceService.java` | **Create** | 4a |
| 11 | `.../api/dto/PreferenceRequest.java` | **Create** | 4b |
| 12 | `.../api/dto/PreferenceResponse.java` | **Create** | 4b |
| 13 | `.../api/PreferenceController.java` | **Create** | 4c |
| 14 | `.../application/NotificationDispatcher.java` | **Create** | 5a |
| 15 | `.../application/NotificationService.java` | **Modify** | 5b |
| 16 | `.../db/migration/V5__change_outbox_payload_to_text.sql` | **Create** | 6a |
| 17 | `.../domain/OutboxEntry.java` | **Create** | 6b |
| 18 | `.../infrastructure/persistence/ReactiveOutboxRepository.java` | **Create** | 6c |
| 19 | `.../application/OutboxService.java` | **Create** | 6d |
| 20 | `.../messaging/OutboxPoller.java` | **Create** | 6e |
| 21 | `.../infrastructure/email/EmailAdapter.java` | **Create** | 6f |
| 22 | `build.gradle.kts` | **Modify** | 6f (add spring-boot-starter-mail) |
| 23 | `.../resources/application.yml` | **Modify** | 6f (SMTP config) |
| 24 | `.../NotificationApplication.java` | **Modify** | 6e (`@EnableScheduling`) |
| 25 | `.../api/error/NotificationExceptionHandler.java` | **Modify** | 3d (add 409 handler) |
| 26 | `docs/adr/notification/0001-...-boundary.md` | **Create** | ADR |
| 27 | `docs/adr/notification/0002-...-architecture.md` | **Create** | ADR |

**Total: 27 files (22 create, 5 modify) + 2 ADRs**

## Dependency Order

```
Task 1 (V4 Migration — schema)
  └─► Task 2 (Entities + Repositories)
        ├─► Task 3 (Subscription Service + REST API — Follow/Unfollow)
        │     └─► (parallel with Task 4)
        └─► Task 4 (Preference Service + REST API — Settings)
              └─► Task 5 (NotificationDispatcher + refactor NotificationService)
                    └─► Task 6 (Outbox + Email Adapter)

Task 7 (Fan-out design — ADR only, no code)
  └─► Independent — can be written any time
```

Tasks 3 and 4 are parallel after Task 2.

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| `channel_subscription` rename breaks Flyway validation | Low | V1 migration checksum is on the original SQL text. V4 uses `ALTER TABLE RENAME` — does not modify V1 checksum. Flyway only validates unmodified migrations; V1 is unchanged on disk. |
| `notification_outbox.payload` is `JSONB` — R2DBC wire-type mismatch at persist time | **Confirmed** | Task 6a: V5 migration mirrors V3 pattern, changes to `TEXT` |
| Subscription dedup race: two concurrent Follow clicks both pass `existsBy` check | Medium | DB unique constraint `(subscriber_subject, target_type, target_id)` is the safety net. Application `existsBy` check is a best-effort optimization. Map `DataIntegrityViolationException` to `409 Conflict`. |
| `@EnableScheduling` conflicts with existing scheduling | Low | No existing `@Scheduled` beans in notification-service |
| Dispatcher refactor breaks existing behavior | Low | Pure extraction — same code, different class. Behavior is identical. |
| Orphaned `subscription` rows when a target is deleted | Low (MVP) | Harmless — fan-out queries find no active stream and become no-ops. Cleanup mechanism (target-deletion events) added when needed. |
| Email adapter not tested (no SMTP server in dev) | Medium | Wire the adapter, configure with env vars, defer integration testing to Phase 5.3. Compile-only validation for now. |
| `OutboxService.enqueue()` is fire-and-forget (`subscribe()`) | Medium | Accepted — outbox write failure does not roll back the notification. The notification is durable in PostgreSQL and delivered via SSE. Email delivery is additive and retryable via the outbox. |

## Validation

```bash
# After each task:
./gradlew :notification-service:compileJava

# After Task 5 (refactor):
./gradlew :notification-service:compileJava      # behavior unchanged

# After Task 6 (outbox + email):
./gradlew :notification-service:compileJava      # verify Spring Mail auto-config

# Full build after all tasks:
./gradlew :notification-service:compileJava :chat-service:compileJava
ng build
```

## References

- [IMPLEMENTATION-PLAN.md](../IMPLEMENTATION-PLAN.md) — Phase 5 checklist
- [ADR notification/0000](adr/notification/0000-architecture-foundation.md) — notification-service architecture
- [ADR notification/0001](adr/notification/0001-subscription-model-and-notification-boundary.md) — subscription model + boundary (proposed alongside this blueprint)
- [ADR notification/0002](adr/notification/0002-notification-delivery-architecture.md) — delivery architecture (proposed alongside this blueprint)
- [ADR chat/0007](adr/chat/0007-proactive-push-infrastructure-gated.md) — Wave 2 prerequisites
- [chat-moderation-wave2-proactive-push.md](chat-moderation-wave2-proactive-push.md) — Wave 2 build plan
- [notification-foundation-5.1-retrospective.md](notification-foundation-5.1-retrospective.md) — what was built in 5.1/5.2
- [Outbox pattern ADR](adr/stream/0009-outbox-pattern.md) — pattern to mirror
