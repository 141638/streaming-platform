# ADR-0003: Cross-Service Event Dedup Key Scoping

**Date**: 2026-07-16
**Status**: proposed
**Deciders**: streaming-platform team

## Context

The platform uses Kafka for event-driven integration between services. Multiple services consume the same topic (e.g., both notification-service and chat-service consume `stream.control`). Each service uses a distinct Kafka consumer group — Kafka delivers every message to every group exactly once.

For deduplication, the notification-service `StreamControlListener` uses Redis `SETNX` with a key derived from the event ID:

```java
// Current (notification-service StreamControlListener.java:35,67)
private static final String DEDUP_PREFIX = "dedup:stream-event:";
String dedupKey = DEDUP_PREFIX + event.eventId();
```

This key is **unscoped** — it contains no consumer group identity. If a second service (e.g., chat-service) adds Redis `SETNX` dedup for the same topic, the two services would share the same key namespace on the same Redis instance. The first service to acquire the key would block the second from processing the event — a silent cross-service interference bug.

Today, this is safe by accident: chat-service's `StreamControlListener` does not use Redis `SETNX` (its operations — `getOrCreate`, `archive` — are naturally idempotent). But the pattern is fragile. Any future consumer that adds Redis dedup for `stream.control` will collide.

**Constraints:**
- All services share a single Redis instance (Docker Compose, password-protected, 256MB maxmemory)
- Kafka consumer groups are the platform's unit of event delivery isolation
- The `StreamEvent.eventId` is a UUID generated at the producer (stream-service)
- Redis `SETNX` with 24h TTL is the established dedup mechanism (see ADR stream/0009 §Consumer Idempotency)
- The platform convention is reactive Spring Boot 3.3.6 with `ReactiveRedisTemplate`

## Decision

**All Kafka event dedup keys MUST include the consumer group identity.** The prescribed format is:

```
dedup:{topic}:{consumerGroupId}:{eventId}
```

For the notification-service `StreamControlListener`:

```java
// After refactoring
private static final String DEDUP_NS = "dedup";
private final String consumerGroupId;  // injected via @Value

private String dedupKey(String eventId) {
    return DEDUP_NS + ":" + topic + ":" + this.consumerGroupId + ":" + eventId;
}
// → dedup:stream.control:notification-service:{eventId}
```

**Why `{topic}` in the key:** A service may consume multiple Kafka topics. Including the topic name prevents hypothetical collisions if two topics produce events with the same eventId (e.g., if both use a shared sequence generator). With UUID eventIds this is a theoretical concern, but the topic name is a cheap, static addition that future-proofs the pattern.

**Why `{consumerGroupId}` is the correct scoping dimension:** Each consumer group is an independent logical subscriber. Kafka guarantees at-least-once delivery per consumer group. The dedup is a per-group concern — group A processing event X should never prevent group B from also processing event X. The consumer group ID matches Kafka's own delivery contract.

**Property source:** Use `@Value("${spring.kafka.consumer.group-id}")` for the consumer group ID. This is already configured for `@KafkaListener(groupId = ...)` and is a stable, deployment-aware identity.

**Resilience unchanged:** If Redis is unreachable, process the event anyway (`.onErrorResume()`). Duplicate processing is better than dropped events. This pattern is already implemented correctly in `StreamControlListener`.

## Alternatives Considered

### Alternative 1: Unscoped key (current implementation)

```
dedup:stream-event:{eventId}
```

- **Pros**: Simplest possible key; works correctly when only one service dedups a topic
- **Cons**: Cross-service interference when multiple services use Redis SETNX for the same topic. The first service to acquire the key blocks all others — a silent failure that produces no errors, only missing notifications/messages.
- **Why not**: The platform already has two services consuming `stream.control` and will add more consumers for future topics (e.g., `chat.moderation`). The unscoped key is correct only in single-consumer deployments, which this platform is not.

### Alternative 2: Service-name-scoped key (no topic)

```
dedup:stream-event:{consumerGroupId}:{eventId}
```

- **Pros**: Prevents cross-service interference; simpler than including topic
- **Cons**: If the same service consumes two topics and both produce events with the same eventId, over-dedup occurs. With UUID eventIds this is statistically impossible, but the topic name adds zero runtime cost and makes the key self-documenting.
- **Why not**: No reason to exclude the topic — it's a static string that costs nothing and adds defense-in-depth.

### Alternative 3: No Redis dedup — make all consumers naturally idempotent

- **Pros**: Eliminates Redis dependency for dedup; no key scoping concerns
- **Cons**: Not all operations are naturally idempotent. Creating a notification from a stream event is not idempotent without a dedup layer (same event → duplicate notification row). Chat-service achieves natural idempotency via `getOrCreate` but notification-service cannot.
- **Why not**: Natural idempotency is the ideal but not always achievable. Redis SETNX is the established fallback. Scoping the key correctly makes it safe for multi-consumer deployments.

### Alternative 4: Kafka transactions (exactly-once semantics)

- **Pros**: No application-level dedup needed
- **Cons**: Requires idempotent producers + transactional consumers + broker config changes. Adds latency and complexity. Already evaluated and deferred in ADR stream/0009.
- **Why not**: Outbox pattern + consumer-side dedup provides sufficient at-least-once guarantees for current needs.

## Consequences

### Positive
- **Cross-service safety**: Multiple services can safely use Redis SETNX for the same Kafka topic without interference
- **Self-documenting keys**: The key format `dedup:{topic}:{group}:{eventId}` makes ownership and purpose visible in Redis inspection
- **Matches Kafka semantics**: Consumer group scoping mirrors Kafka's own delivery model — one dedup domain per logical subscriber
- **No new infrastructure**: Same Redis instance, same `SETNX` pattern, same TTL. Only the key format changes.

### Negative
- **One-time migration**: Changing the key format means existing unscoped keys (`dedup:stream-event:{eventId}`) won't block the new scoped keys during the deploy window. A Kafka re-delivery within that ~minute window could produce one duplicate notification.
- **Slightly longer keys**: ~70 characters vs ~50. Negligible for Redis.
- **New consumer group ID dependency**: `StreamControlListener` gains a constructor parameter. The `@Value` injection fails fast at startup if the property is missing.

### Risks
- **Consumer group rename**: If `spring.kafka.consumer.group-id` is changed, dedup starts fresh — events already processed by the old group can be re-processed. This is consistent with Kafka's behavior (rename = new subscriber, re-delivery from earliest offset). Documented, not mitigated in code.
- **Cross-environment Redis sharing**: If dev and staging share a Redis cluster with the same consumer group IDs, keys collide. This is a deployment concern, not a code concern. Mitigation: environment-prefixed keys or separate Redis instances per environment.
- **Non-Kafka brokers**: If the platform adopts SQS or RabbitMQ, the scoping dimension changes (queue URL for SQS, queue name for RabbitMQ). The **principle** (scope by logical subscriber identity) is universal; the **property name** differs. Each broker requires its own scoping decision.

## References

- [ADR stream/0009](../stream/0009-outbox-pattern.md) — outbox pattern + consumer idempotency via Redis SETNX
- [ADR notification/0000](../notification/0000-architecture-foundation.md) — notification-service architecture, Redis SETNX dedup
- [ADR notification/0002](../notification/0002-notification-delivery-architecture.md) — event vs REST dedup strategy distinction
- [ADR common/0002](./0002-redis-ephemeral-data-store.md) — Redis ephemeral data store use cases
- [`StreamControlListener.java` (notification-service)](../../../main/source/backend/notification-service/src/main/java/com/streaming/notification/messaging/StreamControlListener.java) — current unscoped implementation (line 35, 67)
- [`StreamControlListener.java` (chat-service)](../../../main/source/backend/chat-service/src/main/java/com/streaming/chat/messaging/StreamControlListener.java) — naturally idempotent, no Redis dedup
- [Dedup strategy skill](../../../.claude/skills/learned/dedup-strategy-event-vs-rest.md) — event (Redis SETNX) vs REST (DB constraint) dedup pattern
