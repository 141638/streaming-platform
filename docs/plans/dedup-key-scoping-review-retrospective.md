# Event Dedup Key Scoping — Design Review Retrospective

**Date:** 2026-07-16
**Session type:** Architecture review (no code written)
**Trigger:** `/review` of scoped Redis dedup key proposal in `dedup-strategy-event-vs-rest` skill

## 1. What Was Reviewed

The proposed scoped key pattern for Kafka event dedup:

```
// BEFORE (current, unscoped):
dedup:stream-event:{eventId}

// AFTER (proposed, consumer-group-scoped):
dedup:stream-event:{consumerGroupId}:{eventId}
```

The review confirmed the pattern is correct and identified a latent bug in the current implementation.

## 2. Key Findings

### 2.1 The scoped key pattern is correct

Kafka delivers each message to every consumer group independently. An unscoped dedup key breaks that model — consumer group A processing an event should not prevent consumer group B from also processing it. The consumer group ID is the correct scoping dimension because it matches Kafka's own delivery contract.

### 2.2 Latent bug in notification-service StreamControlListener

`StreamControlListener.java:35`:
```java
private static final String DEDUP_PREFIX = "dedup:stream-event:";
// ...
String dedupKey = DEDUP_PREFIX + event.eventId();  // ← unscoped
```

This is safe today only because chat-service's `StreamControlListener` does NOT use Redis SETNX dedup (its operations are naturally idempotent: `getOrCreate`, `archive`). If chat-service ever adds Redis SETNX for `stream.control` events, the unscoped keys would silently interfere.

### 2.3 Four ADRs reference Redis SETNX without key scoping

| ADR | Section | What it says | Gap |
|-----|---------|-------------|-----|
| notification/0000 | Third-Party Integration Catalog | `SETNX` on `eventId` with 24h TTL | No consumer group scoping |
| stream/0009 | Consumer Idempotency | Redis `SETNX` on `eventId` with 24h TTL | No consumer group scoping |
| notification/0002 | §5 | Correctly distinguishes event vs REST dedup | Doesn't mention key scoping across consumer groups |
| common/0002 | Use Case catalog | Lists idempotency keys (use case #3) | Event dedup not listed as a use case; no key scoping guidance |

### 2.4 Risks assessed and accepted

| Risk | Severity | Verdict |
|------|----------|---------|
| Migration window (unscoped → scoped) | MEDIUM | One-time during deploy; Kafka offset commits make re-delivery unlikely; worst case = 1 duplicate notification |
| Consumer group rename resets dedup | LOW | Correct behavior — Kafka also treats rename as new subscriber |
| Cross-environment Redis sharing | Pre-existing | Same for scoped and unscoped keys; deployment concern |
| SQS/RabbitMQ portability | LOW | Principle (scope by logical subscriber) is universal; property name differs by broker |

## 3. What Needs to Change

### 3.1 New ADR (common/0003)

Cross-service event dedup key scoping — platform-wide standard. Covers:
- Scoped key pattern: `dedup:{topic}:{consumerGroupId}:{eventId}`
- Rationale per Kafka's per-group delivery model
- Migration path for existing unscoped keys
- Scope dimension for non-Kafka brokers (SQS → queue URL, RabbitMQ → queue name)

### 3.2 ADRs to update

| ADR | Change |
|-----|--------|
| notification/0000 | Third-Party Integration Catalog → reference common/0003 for key scoping |
| stream/0009 | Consumer Idempotency section → note that keys should be consumer-group-scoped per common/0003 |
| notification/0002 | §5 → add forward reference to common/0003 for the event dedup key format |
| common/0002 | Add event dedup as use case #7 with scoped key pattern; reference common/0003 |

### 3.3 Code to refactor

| File | Line | Change |
|------|------|--------|
| `notification-service/.../messaging/StreamControlListener.java` | 35, 67 | Inject `consumerGroupId` via `@Value`, scope dedup key to `dedup:stream-event:{consumerGroupId}:{eventId}` |

### 3.4 Skill file to save

`.claude/skills/learned/dedup-strategy-event-vs-rest.md` — already drafted, includes scoped key pattern + key scoping constraint section.

## 4. Decisions Made

1. **Scoped key pattern adopted** as platform standard for all Kafka event dedup
2. **Migration approach**: Direct cutover (not two-phase) — risk of 1 duplicate notification during deploy window is acceptable
3. **ADR location**: `docs/adr/common/0003` — cross-cutting concern, not notification-specific
4. **Chat service**: No dedup refactoring needed (naturally idempotent operations). If chat-service adds Redis SETNX in the future, it must use scoped keys.

## 5. Documents Touched

| Document | Action |
|----------|--------|
| `docs/adr/common/0003-cross-service-event-dedup-key-scoping.md` | Create (new ADR) |
| `docs/adr/common/README.md` | Update — add row for 0003 |
| `docs/adr/notification/0000-architecture-foundation.md` | Update — reference common/0003 |
| `docs/adr/stream/0009-outbox-pattern.md` | Update — reference common/0003 |
| `docs/adr/notification/0002-notification-delivery-architecture.md` | Update — forward reference to common/0003 |
| `docs/adr/common/0002-redis-ephemeral-data-store.md` | Update — add event dedup use case |
| `.claude/skills/learned/dedup-strategy-event-vs-rest.md` | Save (already drafted) |
| `docs/IMPLEMENTATION-PLAN.md` | Update — add common/0003 to ADR table, note refactoring task |

## 6. Key Risks Carried Forward

1. **Refactoring not yet done** — `StreamControlListener` still has unscoped key. Safe today, must be fixed before any other service adds Redis SETNX for `stream.control`.
2. **No test coverage for dedup** — neither the current unscoped pattern nor the proposed scoped pattern has automated tests. Redis SETNX is simple enough that manual verification is acceptable for MVP.
3. **Chat service future dedup** — if chat-service adds Redis SETNX without scoping, interference occurs. Mitigation: this ADR + code review checklist.
