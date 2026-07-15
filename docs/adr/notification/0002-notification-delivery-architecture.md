# ADR-0002: Notification Delivery Architecture — Dispatcher, Outbox Channels, and Fan-Out Strategy

**Date**: 2026-07-16
**Status**: proposed
**Deciders**: hieuht, Claude

## Context

The notification service delivers notifications through multiple channels: in-app
(SSE push to connected clients), email (SMTP), and future channels (push
notifications). These channels are **additive** — a single notification may need
persist + SSE + email, not persist *or* SSE *or* email.

The current `NotificationService.createFromStreamEvent()` does persist + SSE push
inline, duplicated for `STREAM_STARTED` and `STREAM_ENDED`. Adding email to this
pattern would add a third copy-paste block and couple the fast notification path
(HTTP response, SSE push) to SMTP latency (500ms–2s round-trip).

Beyond single-recipient delivery, fan-out — one stream event → N notifications for
N followers — presents a scaling challenge. For a broadcaster with millions of
followers, inline fan-out would block the Kafka consumer thread for an unacceptable
duration.

The stream-service has a proven outbox pattern (`OutboxPoller` with `FOR UPDATE
SKIP LOCKED`, scheduled polling, retries with max attempts). The V1 migration
already created a `notification_outbox` table for this purpose.

## Decision

### 1. Concrete dispatcher, not an interface

`NotificationDispatcher` is a concrete `@Service` class, not an interface. Delivery
channels (persist, SSE, outbox) are composed as sequential steps within the
dispatcher. They are additive pipeline stages, not alternative strategies.

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
     * <p>Step 1 — persist (required, in-chain): writes to PostgreSQL.
     * <p>Step 2 — SSE push (fire-and-forget): pushes to connected clients.
     * <p>Step 3 — outbox enqueue (fire-and-forget): writes to outbox for
     * non-in_app channels (email, future push). The outbox poller handles
     * dispatch asynchronously.
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

    /** Fan-out to multiple recipients (inline for MVP). */
    public Mono<Void> deliverToMany(List<Notification> notifications, int concurrency) {
        return Flux.fromIterable(notifications)
                .flatMap(this::deliver, concurrency)
                .then();
    }
}
```

A concrete class is the right choice because:

- Channels are always additive, never alternative. There is no use case for "email
  only, skip persist" or "SSE only, skip email." An interface implies swappable
  strategies; this is a composed pipeline.
- Single place to modify delivery behavior. If a new step is added (e.g., Kafka
  event emission for analytics), it goes in one method, not N implementations.
- Simpler testing. One class with mocked dependencies vs an interface + N
  implementations to test.
- YAGNI. If alternative dispatch strategies emerge (priority vs batch, sync vs
  async), extract the interface then.

### 2. Dispatcher pipeline ordering

```
deliver(notification)
  ├─ Step 1: Persist to PostgreSQL           [required, synchronous]
  ├─ Step 2: SSE push to connected clients    [fire-and-forget, best-effort]
  └─ Step 3: Enqueue to outbox               [for non-in_app channels]
                 │
                 ▼
           OutboxPoller (scheduled, async)
                 │
                 ├─ EmailAdapter.send()
                 └─ (future) PushAdapter.send()
```

**Step 1** is the only required step — if persist fails, nothing else happens and the
error propagates to the caller. **Steps 2–3** are best-effort: failures are logged
but do not roll back the persist. If Step 3 fails, the notification is still persisted
and SSE-delivered to connected clients; the outbox retry is handled by the poller on
the next cycle.

This ordering means the fast path (persist + SSE, sub-10ms) is decoupled from the
slow path (SMTP, 500ms–2s). The user gets the in-app notification immediately; the
email arrives seconds later via the outbox.

### 3. Outbox-driven email

Email delivery is asynchronous via the outbox pattern:

1. `Dispatcher.deliver()` Step 3 calls `OutboxService.enqueue(notification)`.
2. `OutboxService` writes a row to `notification_outbox` with the notification JSON
   as the payload, state = `PENDING`.
3. `OutboxPoller` (scheduled, default 5s interval) polls PENDING rows with
   `FOR UPDATE SKIP LOCKED`, dispatches via the appropriate channel adapter
   (`EmailAdapter` for channels where the user has email preference), and marks
   each row `SENT` or `FAILED`.
4. Failed rows are retried on subsequent poll cycles up to `maxRetries` (3), then
   marked `DEAD` for inspection.

This mirrors the stream-service outbox pattern
([ADR stream/0009](../stream/0009-outbox-pattern.md)) exactly — same polling
strategy, same locking, same retry semantics.

### 4. Outbox-driven fan-out (architecture designed now; inline for MVP)

When fan-out is implemented (deferred past MVP per
[ADR-0001](0001-subscription-model-and-notification-boundary.md) §4):

**Enqueue phase** (fast, in Kafka consumer thread):
```
StreamEvent → SubscriptionService.getSubscribers(targetType, targetId)
  → write one FanOutJob row (state=PENDING) with the list of subscribers
  → return (one INSERT, never blocks)
```

**Process phase** (async, in scheduled poller):
```
FanOutPoller picks up PENDING jobs
  → deserialize subscriber list
  → chunk by batchSize (100)
  → for each chunk: call dispatcher.deliverToMany(chunk, concurrency)
  → on success: mark job COMPLETED
  → on partial failure: increment retryCount, re-enqueue failed chunk
  → on total failure after maxRetries: mark job DEAD
```

**For MVP** (small user base): inline fan-out is acceptable. The
`StreamControlListener` calls `dispatcher.deliverToMany(notifications, concurrency)`
directly within the reactive chain, offloaded from the consumer thread via
`subscribeOn(Schedulers.boundedElastic())`. The outbox-based fan-out is designed
and the `FanOutJob` schema is ready, but implementation uses the simpler inline
path until subscriber counts warrant the async approach.

### 5. Subscription idempotency via database constraint

Subscription creation (Follow) uses the database unique constraint on
`(subscriber_subject, target_type, target_id)` for deduplication — not Redis SETNX.

Redis SETNX is appropriate for Kafka event dedup (where the event ID is the
natural dedup key — see `StreamControlListener`) but not for REST API idempotency.
Note: Kafka dedup keys must be consumer-group-scoped per
[ADR common/0003](../common/0003-cross-service-event-dedup-key-scoping.md).
Two double-clicks on the Follow button produce two independent HTTP requests with
no shared identity key. The Redis key would have to be derived from the request
body, creating TTL edge cases (a legitimate re-follow after unfollow within the TTL
window would be blocked).

The DB unique constraint catches the second INSERT and maps it to a client error:

```java
return subscriptionRepository.save(sub)
        .map(SubscriptionResponse::from)
        .onErrorMap(DataIntegrityViolationException.class,
            ex -> new SubscriptionAlreadyExistsException(
                subscriberSubject, targetType, targetId));
```

The HTTP response is `409 Conflict` with a structured error body. The client can
interpret this as "already following" and update the button state accordingly.

Phase 6.1 idempotency keys — when built — will provide general REST idempotency
across all services. At that point, the DB constraint becomes the safety net
behind a Redis-based idempotency-key gate.

## Alternatives Considered

### `NotificationDispatcher` as an interface with multiple implementations

`EmailDispatcher`, `SseDispatcher`, `PersistDispatcher` as separate beans composed
via decorator or composite pattern. Rejected: channels are always additive, never
alternative. An interface implies swappable strategies; this is a composed pipeline.
Extract the interface when a second concrete strategy is needed.

### Inline email (no outbox)

Send email synchronously in the reactive chain via Spring Mail's reactive wrapper.
Rejected: couples the fast notification path (persist + SSE) to SMTP latency and
failures. A down SMTP server would make every notification persist fail. The
outbox pattern is already proven in stream-service and is the platform convention.

### Redis SETNX for subscription dedup

Rejected for REST API dedup. Redis SETNX needs a stable key — the event ID works for
Kafka messages, but REST requests have no equivalent until Phase 6.1 idempotency
keys exist. The DB unique constraint provides the same protection with fewer moving
parts and no TTL edge cases.

### Inline fan-out for all scales

Iterate all subscribers directly in the Kafka consumer thread regardless of count.
Rejected: a broadcaster with millions of followers would block the consumer for
minutes, causing `max.poll.interval.ms` exceedance, partition rebalance, and
consumer group instability. The outbox pattern decouples event arrival from fan-out
execution.

## Consequences

### Positive

- **Single delivery code path.** Every notification — whether from stream events,
  moderation events, or future sources — calls `dispatcher.deliver()`. Adding a new
  delivery channel means adding one step to the dispatcher method, not touching N
  event handlers.
- **Email decoupled from hot path.** SMTP latency does not affect in-app notification
  speed. Persist + SSE complete in ~10ms; email arrives asynchronously via the
  outbox.
- **Fan-out architecture designed upfront.** When fan-out is needed at scale, the
  schema (`FanOutJob` table), poller design, and chunking strategy are already
  planned. Implementation is additive, not a rewrite.
- **Outbox pattern is consistent across services.** Same `FOR UPDATE SKIP LOCKED`
  polling, same retry semantics as stream-service. A developer who knows one
  outbox poller knows both.

### Negative

- **Dispatcher has 3 dependencies** (repository, SSE registry, outbox service) —
  more than the current inline code in `NotificationService.createFromStreamEvent()`
  (2 dependencies). The tradeoff is a single, testable class vs duplicated
  persist+push blocks in every event handler.
- **Outbox poller adds latency.** Email delivery is delayed by up to the poll
  interval (default 5s) plus SMTP round-trip time.
- **Outbox-driven fan-out adds latency vs inline.** The polling interval + chunk
  processing means the last subscriber in a large batch may receive the notification
  minutes after the event. Acceptable for non-real-time notification categories.

### Risks

- **Dispatcher bloat.** If every new delivery channel adds logic directly to the
  dispatcher, it becomes a God class. Mitigation: new channels use the outbox
  (just write a row to `notification_outbox`); the outbox poller handles
  channel-specific dispatch via pluggable adapters (`EmailAdapter`, future
  `PushAdapter`). The dispatcher only knows about persist + SSE + "enqueue for
  other channels."
- **Fan-out latency at scale.** A 5s poll interval + 10M subscribers chunked at
  100 per batch = ~100K batches. At 10ms per dispatch, that's ~17 minutes of
  processing. Mitigation: configurable batch size and concurrency; parallel
  poller instances (enabled by `SKIP LOCKED`); separate poller for fan-out vs
  email to avoid head-of-line blocking.
- **Outbox table growth.** If no downstream channel processing exists, the
  `notification_outbox` table accumulates rows. Mitigation: the poller only
  processes entries for channels that have registered adapters. Until an email
  adapter is active, email-channel entries stay PENDING (or are never written).

## References

- [ADR-0000](0000-architecture-foundation.md) — notification-service architecture foundation
- [ADR-0001](0001-subscription-model-and-notification-boundary.md) — subscription model (proposed alongside this ADR)
- [ADR stream/0009](../stream/0009-outbox-pattern.md) — outbox pattern decision in stream-service
- [`OutboxPoller.java`](../../../main/source/backend/stream-service/src/main/java/com/streaming/stream/messaging/OutboxPoller.java) — pattern to mirror
- [`StreamControlListener.java`](../../../main/source/backend/notification-service/src/main/java/com/streaming/notification/messaging/StreamControlListener.java) — Redis SETNX dedup pattern (appropriate for Kafka events)
- [Blueprint: 5.1b](../../plans/notification-5.1b-subscription-dispatcher-blueprint.md) — original plan (superseded by the ADRs here)
