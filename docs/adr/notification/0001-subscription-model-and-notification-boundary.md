# ADR-0001: Subscription Model and Notification Service Boundary

**Date**: 2026-07-16
**Status**: accepted
**Deciders**: hieuht, Claude

## Context

The V1 migration (`V1__bootstrap_notification_schema.sql`) created the
`channel_subscription` table with columns `subscriber_subject`, `channel`,
`topic_glob`, and `active`. This table conflates two distinct concerns:

1. **Delivery preference** — *how* to notify a user: via in_app, email, push; filtered
   by category pattern (e.g. `STREAM_*`)
2. **Follow target** — *what* a user wants notifications about: a specific streamer,
   a chat room, a stream session

Adding a `channel_id` column (as the original blueprint proposed) would further
entangle these concerns. A user following 50 streamers who wants to switch all
notifications from "in_app" to "email" would need 50 row updates instead of 1
preference change.

Meanwhile, the platform's channel page UI (`channel.page.html`) has two distinct
buttons — both currently disabled shells:

| Button | Icon | Intent | Future benefits |
|--------|------|--------|-----------------|
| **Follow** | Heart (outlined) | Get notifications when the streamer goes live | None beyond notifications |
| **Subscribe** | Star | Get notifications + premium benefits | Private streams, special emotes, chat badge, ad-free |

Both actions result in the user wanting notifications about a streamer. The
distinction (free vs paid, which benefits) is a **stream-service** / future
channel-service concern, not a notification-service concern.

## Decision

### 1. Split the table

The V4 migration will:

**Rename** `channel_subscription` → `notification_preference` (its columns —
`subscriber_subject`, `channel`, `topic_glob`, `active` — already model delivery
preferences exactly). Add a unique constraint on `(subscriber_subject, channel)`
and an `updated_at` column for preference mutations.

**Create** a new `subscription` table for follow targets:

```sql
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

The two tables answer exactly two questions:

| Table | Question | Example row |
|-------|----------|-------------|
| `notification_preference` | *How* to deliver? | "Send my STREAM_* notifications via in_app and email" |
| `subscription` | *What* to notify about? | "I want to know when broadcaster `hieuht` goes live" |

### 2. Polymorphic subscription target

`subscription` uses `(target_type, target_id)` rather than separate columns per
target kind. This is extensible without schema changes:

| `target_type` | `target_id` | Meaning |
|---------------|-------------|---------|
| `CHANNEL` | `<broadcaster_subject>` | Follow a streamer |
| `CHAT_ROOM` | `<room_key>` | Get notifications for a chat room |
| `STREAM_SESSION` | `<stream_id>` | Get notifications for a specific stream |

The notification service does not interpret `target_id` — it only queries it
during fan-out lookups: `WHERE target_type = ? AND target_id = ? AND active = true`.

### 3. Notification projection, not system of record

The `subscription` table stores the **notification intent** — "user X wants
notifications about target Y." Whether that intent originates from a free Follow
or a paid Subscribe is invisible to notification-service.

```
┌──────────────────────────────────────────────────────────┐
│                   stream-service                          │
│  Owns: Follow vs Subscribe distinction                    │
│  Owns: Subscriber benefits (badges, private streams...)   │
│  Tells notification-service: "these users want notifs"    │
└──────────────────────┬───────────────────────────────────┘
                       │  REST call to notification-service
                       ▼
┌──────────────────────────────────────────────────────────┐
│                notification-service                        │
│  Knows: user X wants notifications about target Y         │
│  Knows: deliver via channels [in_app, email, ...]         │
│  Does NOT know: whether X is a follower or subscriber     │
│  Does NOT know: what benefits X has                       │
└──────────────────────────────────────────────────────────┘
```

This is the same pattern as `broadcaster_username` on `stream_session` — a
denormalized projection scoped to what this service needs, populated at write time
from the authoritative source.

**In practice:**

- **Follow button** → calls notification-service directly:
  `POST /v1/subscriptions { target_type: "CHANNEL", target_id: "<broadcasterSubject>" }`
- **Subscribe button** → calls stream-service first (for payment/benefits), which
  then calls notification-service as part of its workflow to create the subscription
  row for the notification intent.

Notification-service does not need to distinguish these paths.

### 4. Fan-out lookup uses both tables

When fan-out is implemented (designed now, inline for MVP per
[ADR-0002](0002-notification-delivery-architecture.md)):

```
StreamEvent(broadcasterSubject="hieuht")
  → SELECT subscriber_subject FROM subscription
    WHERE target_type = 'CHANNEL' AND target_id = 'hieuht' AND active = true
  → For each subscriber: check notification_preference for delivery channels
  → For each active channel: dispatch notification
```

## Alternatives Considered

### Single table with `channel_id` column (original blueprint Option A)

`ALTER TABLE channel_subscription ADD COLUMN channel_id VARCHAR(128)`.

Rejected: conflates delivery preference with follow target. A user following 50
streamers who wants to switch delivery channels needs 50 row updates instead of 1.
The table tries to answer two questions with one row, and the result is a schema
that's hard to query and hard to explain.

### Separate `follow` vs `subscribe` tables in notification-service

`notification.follow` and `notification.subscribe` tables, reflecting the UI
distinction in the notification schema.

Rejected: notification-service should not model the business tier. The follow vs
subscribe distinction is stream-service's domain. Notification-service only cares
about "does this user want notifications about this target?"

### No `subscription` table — fan-out queries stream-service directly

Rejected for MVP: stream-service has no follower/subscriber table today. When it
builds one (likely in Phase 8 or the channel-service extraction), notification-service
should consume follow/unfollow events (Kafka) rather than polling stream-service's
REST API. The local `subscription` table is a denormalized cache of "who wants
notifications" — the same pattern as other cross-service reference data in this
platform.

## Consequences

### Positive

- **Clean separation.** Preference changes (1 row) are independent of follow count
  (N rows). A user can change their delivery channel in one mutation regardless of
  how many streamers they follow.
- **Extensible.** Polymorphic target supports future notification sources (chat rooms,
  stream sessions) without migration.
- **Correct boundary.** Notification-service doesn't model business tiers it doesn't
  own. Follow/Subscribe semantics stay in stream-service where they belong.
- **Simple fan-out query.** `WHERE target_type = ? AND target_id = ? AND active = true`
  is a single indexed lookup — no joins, no subqueries.

### Negative

- **Two tables instead of one.** More entities, repositories, and migration files.
  The separation adds surface area for the initial build.
- **Two-step fan-out.** Subscriber lookup (subscription table) + preference lookup
  (notification_preference table) is two queries instead of one.
- **Denormalized projection staleness.** If stream-service builds a subscriber system
  and doesn't inform notification-service, the projection goes stale. Mitigation:
  the Follow button calls notification-service directly; the Subscribe path is
  owned by stream-service and includes notification-service in its workflow.

### Risks

- **Orphaned subscriptions.** If a broadcaster deletes their account, subscription
  rows with `target_id = <their-subject>` become orphaned. For MVP, this is
  harmless — fan-out queries find no active stream and become no-ops. A cleanup
  mechanism (stream-service emits `CHANNEL_DELETED` → notification-service
  deactivates subscriptions) should be added when account deletion exists.
- **`target_id` namespace collisions.** If two different `target_type` values happen
  to use the same `target_id` value, they don't collide because the unique constraint
  includes `target_type`. Each namespace is independent.

## References

- [ADR-0000](0000-architecture-foundation.md) — notification-service architecture foundation
- [ADR-0002](0002-notification-delivery-architecture.md) — delivery architecture (proposed alongside this ADR)
- [ADR stream/0007](../stream/0007-public-channel-read-and-channel-service-seam.md) — pattern for cross-service identity denormalization
- Channel header UI: `channel-header.component.html` — Follow (heart) + Subscribe (star) buttons
- [Blueprint: 5.1b](../../plans/notification-5.1b-subscription-dispatcher-blueprint.md) — original plan (superseded by the ADRs here)
