---
name: dedup-strategy-event-vs-rest
description: "Three idempotency patterns: event guard (SETNX on scoped key), TTL tracking (Hash with fields), REST guard (DB constraint). Don't cargo-cult one into the other."
user-invocable: false
origin: auto-extracted
---

# Dedup Strategy: Redis SETNX for Events, DB Unique Constraint for REST

**Extracted:** 2026-07-16
**Context:** Notification service design session — `StreamControlListener` uses Redis SETNX for Kafka event dedup; the new `SubscriptionService` (REST) needed idempotency. The temptation was to copy the same pattern.

## Problem

A developer sees Redis SETNX dedup working well in one part of the codebase (Kafka consumer) and reaches for the same pattern in a REST API handler. But the two domains have fundamentally different properties:

| Property | Kafka/SQS events | REST API requests |
|----------|-----------------|-------------------|
| Has a stable identity key? | **Yes** — `eventId` / `messageId` is part of the payload | **No** — two double-clicks are two independent HTTP requests with no shared ID |
| Dedup key source | Natural: the event's own ID | Synthetic: must be derived from request body or require client-generated key |
| TTL edge case | Acceptable: a replay of the same event after 24h is a genuine duplicate | **Problematic**: a legitimate re-follow after unfollow within 24h gets blocked |
| Idempotency guarantee | Best-effort (defense-in-depth against at-least-once delivery) | Must be exact (a user clicking Follow twice should not create two rows) |

Using Redis SETNX for REST dedup creates a TTL trap: the Redis key expires after 24h, but the user's legitimate intent (unfollow → refollow 5 minutes later) is blocked because the key hasn't expired yet. Shortening the TTL to minutes weakens the dedup guarantee.

## Solution

### For event-driven consumers (Kafka, SQS, RabbitMQ): Redis SETNX

```java
// Key MUST be scoped to the consumer group — see "Key Scoping" section below.
// Unscoped keys cause cross-service interference when multiple consumers
// share a Redis instance and listen to the same topic.
String dedupKey = "dedup:stream-event:" + consumerGroupId + ":" + event.eventId();
redisTemplate.opsForValue()
        .setIfAbsent(dedupKey, "1", Duration.ofHours(24))
        .flatMap(acquired -> {
            if (Boolean.TRUE.equals(acquired)) {
                return handle(event);
            }
            log.debug("Duplicate event skipped: eventId={}", event.eventId());
            return Mono.empty();
        })
        .onErrorResume(ex -> handle(event));  // Redis down → process anyway
```

**Why it works:** The `eventId` is a natural, stable identity key. A duplicate is truly a duplicate — same event, same content. The 24h TTL is defense-in-depth against at-least-once delivery. If Redis is down, we process anyway (duplicate is better than dropped).

### For REST API endpoints (create/upsert): DB unique constraint

```java
// SubscriptionService.java — correct pattern for REST API
public Mono<SubscriptionResponse> follow(
        String subscriberSubject, String targetType, String targetId) {

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Subscription sub = Subscription.create(subscriberSubject, targetType, targetId, now);

    return subscriptionRepository.save(sub)
            .map(SubscriptionResponse::from)
            .onErrorMap(DataIntegrityViolationException.class,
                ex -> new SubscriptionAlreadyExistsException(
                    subscriberSubject, targetType, targetId));
}
```

Backed by the schema:

```sql
CREATE TABLE notification.subscription (
    -- ...
    CONSTRAINT uq_subscription
        UNIQUE (subscriber_subject, target_type, target_id)
);
```

**Why it works:** The business key `(subscriber_subject, target_type, target_id)` is the true identity of the resource. Two double-clicks are two attempts to create the same resource — the DB constraint catches the second one correctly, regardless of timing. There's no TTL — the constraint is permanent and correct.

### The exception handler

```java
@ExceptionHandler(SubscriptionAlreadyExistsException.class)
@ResponseStatus(HttpStatus.CONFLICT)  // 409
public NotificationApiError handleAlreadyExists(SubscriptionAlreadyExistsException ex) {
    return new NotificationApiError("SUBSCRIPTION_EXISTS", ex.getMessage());
}
```

The client interprets `409 Conflict` as "already following" and updates the button state. This is correct for both a double-click (true duplicate) and a legitimate re-follow attempt (user should see "Following" not an error).

### The hybrid case: when you have Phase 6.1 idempotency keys

When the platform has general REST idempotency (client-generated `Idempotency-Key` header), the strategy becomes two-layered:

1. **Gate layer**: Redis SETNX on `idempotency:{key}` (TTL: 24h) — catches duplicate POSTs
2. **Safety net**: DB unique constraint — catches anything that slips past the gate

The DB constraint remains the ultimate safety net. Redis is the performance optimization.

## Two Redis Patterns, One Label: SETNX ≠ Hash

The codebase has two Redis patterns that both got called "dedup." They solve fundamentally different problems, and using the wrong one breaks things.

### Event Idempotency Guard (SETNX)

| Property | Detail |
|----------|--------|
| Redis structure | `String` + `SETNX` |
| Key format | `dedup:{topic}:{consumerGroupId}:{eventId}` |
| Value | Placeholder `"1"` — never read back |
| Question | "Have I seen this eventId before?" |
| Dedup is... | **The purpose** — prevents duplicate processing |
| Read back | Never — existence test only |
| Code location | `notification-service/.../StreamControlListener.java` |
| ADR | [common/0003](../../../docs/adr/common/0003-cross-service-event-dedup-key-scoping.md), [notification/0000](../../../docs/adr/notification/0000-architecture-foundation.md), [stream/0009](../../../docs/adr/stream/0009-outbox-pattern.md) |

```java
// Event idempotency guard: SETNX, value is meaningless
String dedupKey = "dedup:" + topic + ":" + consumerGroupId + ":" + event.eventId();
redisTemplate.opsForValue()
    .setIfAbsent(dedupKey, "1", Duration.ofHours(24))
    .flatMap(acquired -> {
        if (Boolean.TRUE.equals(acquired)) return handle(event);
        return Mono.empty();  // duplicate, skip
    });
```

### TTL-Based Tracking (Hash)

| Property | Detail |
|----------|--------|
| Redis structure | `Hash` + `HSET` + `EXPIRE` |
| Key format | `stream:view:{streamId}:{viewerId}` |
| Value | Meaningful fields: `user_id`, `first_seen_at`, `last_seen_at` |
| Question | "Has this viewer watched this stream in this TTL window?" |
| Dedup is... | **A side effect** — a return viewer within TTL doesn't double-count |
| Read back | Regularly — `HGETALL` to flush computed metrics to database |
| Code location | `stream-service/.../StreamService.java` |
| ADR | [stream/0008](../../../docs/adr/stream/0008-view-count-analytics-pipeline.md) |

```java
// TTL-based tracking: Hash with meaningful fields, read back for flush
String viewKey = "stream:view:" + streamId + ":" + viewerId;
redisTemplate.opsForHash()
    .putAll(viewKey, Map.of(
        "user_id", viewerId,
        "first_seen_at", now.toString()
    ))
    .then(redisTemplate.expire(viewKey, Duration.ofHours(24)));
```

### The Confusion

A developer sees both called "dedup" in code comments and ADRs. They reach for Hash when they need idempotency ("I'll store the eventId in a Hash field"), or SETNX when they need tracking ("I just need to know if the key exists"). Both are wrong:

| Wrong choice | What breaks |
|-------------|-------------|
| Hash for event dedup | Wasted fields you never read; no atomic check-and-set — race condition between `EXISTS` and `HSET` where two threads both see "no key" and both process |
| SETNX for tracking | Lost `first_seen_at`/`last_seen_at` state — the flush pipeline needs these fields to compute metrics; SETNX stores a meaningless `"1"` |

### Decision Rule for Redis Patterns

| You need to... | Use | Because |
|----------------|-----|---------|
| Prevent duplicate processing of an event | `String` + `SETNX` | Binary membership test, atomic, no state needed |
| Track unique X within a time window with metadata | `Hash` + `HSET` + `EXPIRE` | Meaningful fields survive until flush, `HGETALL` reads them |
| Prevent duplicate REST resource creation | DB unique constraint | No natural stable key; constraint is permanent and correct |

## When to Use

- **Trigger 1:** You're implementing idempotency for a new endpoint, and you see Redis SETNX used elsewhere in the codebase.
- **Trigger 2:** You need to track unique occurrences within a TTL window (views, rate limits per user, daily active users), and you see the view-tracking Hash pattern in `StreamService`.
- **Decision rule:** If the source of truth has a natural stable identity key (event ID, message ID) → Redis `String` + `SETNX` with consumer-group-scoped keys. If you need to store and read back metadata about each unique occurrence (`first_seen_at`, `last_seen_at`, counts) → Redis `Hash` + `HSET`. If the identity must be derived from the request body or a client-generated key → DB unique constraint.
- **Symptom of wrong choice (REST→Redis SETNX):** Users report they can't re-follow a streamer they unfollowed 10 minutes ago. Or: duplicate subscription rows appear in the database despite the "dedup" check.
- **Symptom of wrong choice (Hash for event dedup):** Race condition — two consumer threads both see "key doesn't exist" via `EXISTS`, both call `HSET`, both process the event. Hash has no atomic `SETNX` equivalent.
- **Symptom of wrong choice (SETNX for tracking):** The flush pipeline has no data to flush — every key has value `"1"` instead of `{user_id, first_seen_at, last_seen_at}`.
- **Symptom of wrong choice (Events→no scoping):** Multiple services consuming the same topic silently interfere — one service's dedup key blocks another service from processing the same event.
- **Not for:** Truly ephemeral operations (rate limiting, temporary locks) — Redis is correct for those regardless of event vs REST.

## Additional Constraint: Key Scoping for Event Dedup

When using Redis SETNX for Kafka event dedup, the key MUST be scoped to the consumer group identity. If two services (or two consumer groups within the same service) share a Redis instance, an unscoped key like `dedup:stream-event:{eventId}` creates cross-service interference — the first service to acquire the key blocks all others from processing the event.

### Scoped key pattern

```java
@Component
public class StreamControlListener {

    private static final String DEDUP_NS = "dedup:stream-event";
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final String consumerGroupId;
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public StreamControlListener(
            @Value("${spring.kafka.consumer.group-id}") String consumerGroupId,
            ReactiveRedisTemplate<String, String> redisTemplate) {
        this.consumerGroupId = consumerGroupId;
        this.redisTemplate = redisTemplate;
    }

    // key → dedup:stream-event:notification-service:{eventId}
    private String dedupKey(String eventId) {
        return DEDUP_NS + ":" + this.consumerGroupId + ":" + eventId;
    }
}
```

**Why consumer group ID:** Each consumer group is an independent logical subscriber. Kafka delivers the same message to every group exactly once. The dedup is per-group — group A processing the event should not prevent group B from also processing it. The consumer group ID is the correct scoping dimension because it matches Kafka's own delivery semantics.

**Current code note:** The existing `StreamControlListener` in notification-service uses an unscoped key (`dedup:stream-event:{eventId}`). This is safe today because only notification-service consumes `stream.control` with dedup. If chat-service ever adds Redis SETNX dedup for the same topic, the key must be scoped. A future refactor should add scoping proactively.

## Why This Is Non-Obvious

The codebase has a working Redis SETNX pattern in `StreamControlListener`. It's tested, reviewed, and correct for its domain. The natural instinct is to copy it. But the two domains (event-driven consumers vs REST APIs) have different identity models. The pattern name is the same ("dedup") but the correct mechanism is different. This skill exists to short-circuit that incorrect copy-paste.
