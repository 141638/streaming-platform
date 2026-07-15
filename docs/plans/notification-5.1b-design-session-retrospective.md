# Notification 5.1b — Design Session Retrospective

**Date:** 2026-07-16
**Session type:** Architecture design + blueprint revision (no code written)
**Blueprint:** [notification-5.1b-subscription-dispatcher-blueprint.md](notification-5.1b-subscription-dispatcher-blueprint.md)

## 1. What Was Produced

| Artifact | File | Status |
|----------|------|--------|
| ADR-0001 | `docs/adr/notification/0001-subscription-model-and-notification-boundary.md` | Proposed |
| ADR-0002 | `docs/adr/notification/0002-notification-delivery-architecture.md` | Proposed |
| Revised blueprint | `docs/plans/notification-5.1b-subscription-dispatcher-blueprint.md` | Updated (v2) |

## 2. Key Decisions Made

### 2.1 Table Split (ADR-0001 §1)

`channel_subscription` conflated delivery preferences with follow targets. The V4 migration will:
- **Rename** `channel_subscription` → `notification_preference` (delivery: channel, topic_glob, active)
- **Create** `subscription` table (follow targets: polymorphic `target_type` + `target_id`)

This means a user changing their delivery channel is 1 row update regardless of how many streamers they follow.

### 2.2 Polymorphic Subscription Target (ADR-0001 §2)

`(target_type, target_id)` pattern supports `CHANNEL`, `CHAT_ROOM`, `STREAM_SESSION` without schema changes. Extensible by adding new `target_type` values, not new columns.

### 2.3 Notification Projection Boundary (ADR-0001 §3)

The notification-service stores the **notification intent** — "user X wants notifications about target Y." It does NOT model the Follow vs Subscribe business tier. Follow = free notifications; Subscribe = paid notifications + benefits (private streams, badges, emotes, ad-free). Both create the same `subscription` row. The distinction is stream-service's domain.

This is the same pattern as `broadcaster_username` on `stream_session` — denormalized at write time, scoped to what this service needs.

### 2.4 Concrete Dispatcher, Not Interface (ADR-0002 §1)

`NotificationDispatcher` is a concrete `@Service` facade with a single pipeline: persist → SSE push → outbox enqueue. Channels are additive (never alternative), so an interface implying swappable strategies is the wrong abstraction. YAGNI — extract the interface when a second dispatch strategy exists.

### 2.5 Outbox-Driven Email (ADR-0002 §3)

Email delivery is asynchronous via the outbox pattern. The dispatcher writes to `notification_outbox`; a scheduled `OutboxPoller` (mirroring stream-service `FOR UPDATE SKIP LOCKED`) dispatches via `EmailAdapter`. This decouples the fast path (persist + SSE, ~10ms) from SMTP latency (500ms–2s).

### 2.6 Outbox-Driven Fan-Out (ADR-0002 §4)

Architecture designed now, inline for MVP:
- **Enqueue phase**: Inbound event → write one `FanOutJob` row → return (never blocks)
- **Process phase**: `FanOutPoller` picks up PENDING jobs → chunks subscribers by 100 → dispatches
- **MVP**: Inline fan-out via `deliverToMany()` with bounded concurrency, offloaded from consumer thread

### 2.7 DB Constraint for Subscription Idempotency (ADR-0002 §5)

`UNIQUE (subscriber_subject, target_type, target_id)` on the `subscription` table — not Redis SETNX. REST requests lack a stable dedup key until Phase 6.1 idempotency keys exist. Double-click → `DataIntegrityViolationException` → mapped to `409 Conflict`.

## 3. What the Original Blueprint Had That Was Revised

| Original (v1) | Revised (v2) | Rationale |
|---------------|-------------|-----------|
| Single `channel_subscription` table + `channel_id` column | Split: `notification_preference` + `subscription` | Conflated delivery prefs with follow targets; 50-follow user switching channels = 50 updates vs 1 |
| `NotificationDispatcher` interface + `DefaultNotificationDispatcher` | Concrete `NotificationDispatcher` `@Service` | Channels are additive, not alternative. Interface implies swappable strategies. |
| Outbox skeleton deferred to 5.3 | Outbox + email adapter in scope | Email is simple (Spring Mail), outbox pattern is proven; build it now |
| Fan-out inline only, no batching design | Outbox-driven fan-out designed; inline for MVP | Scaling ceiling documented upfront; avoids rewrite when subscriber count grows |
| Redis SETNX for subscription dedup | DB unique constraint | REST lacks stable dedup key; DB constraint is correct and simpler |
| Task naming: `subscribe`/`unsubscribe` | `follow`/`unfollow` | Matches UI button labels; "subscribe" has platform-specific meaning (paid tier) |
| 18 files, 8 tasks | 27 files, 7 tasks | Table split added entities/repos/controllers; fan-out moved to ADR-only task |

## 4. Leads Created for Future Work

These items were discussed and designed but are NOT in the 5.1b implementation scope:

| Lead | Where documented | When to act | Owner |
|------|-----------------|-------------|-------|
| **Fan-out wiring** — `StreamControlListener` → `SubscriptionService.getSubscribers()` → `dispatcher.deliverToMany()` | ADR-0002 §4, Blueprint Task 7 | Post-MVP, when subscriber counts demand it | notification-service |
| **Outbox-driven fan-out** — `FanOutJob` table + `FanOutPoller` replacing inline fan-out | ADR-0002 §4 | When a broadcaster has >1K followers | notification-service |
| **Stream-service subscriber infrastructure** — paid Subscribe button, benefits (private streams, badges, emotes) | ADR-0001 §3 | Phase 8 / channel-service extraction | stream-service |
| **`channel_id` / `target_type=CHANNEL` migration to social graph** — when follow relationships get their own service | ADR-0001 §3, blueprint Risks | When channel-service extracts follow domain | architecture |
| **Phase 6.1 idempotency keys** — general REST idempotency for subscription creation | ADR-0002 §5 (alternatives) | Phase 6.1 | gateway + all services |
| **Per-target delivery overrides** — `channel_override` on `subscription` for per-streamer channel preferences | ADR-0001 open question | When users request it | notification-service |
| **Orphaned subscription cleanup** — deactivate subscriptions when target is deleted | ADR-0001 Risks | When account deletion exists | notification-service |
| **Wave 2 chat moderation push** — `ModerationListener` → `dispatcher.deliver()` via `chat.moderation` topic | Blueprint §Wave 2 Path | When both Kafka triggers are met per [ADR chat/0007](adr/chat/0007-proactive-push-infrastructure-gated.md) | chat-service + notification-service |

## 5. Documents to Update

| Document | Current status | What's stale | Action |
|----------|---------------|--------------|--------|
| `docs/IMPLEMENTATION-PLAN.md` | Last updated 2026-07-15 | 5.1b description references old design (single table + `channel_id`, dispatcher "interface", outbox deferred); no reference to ADR-0001/0002; 5.3 email adapter listed separately | Update 5.1b item, merge 5.3 into 5.1b scope, add ADR references, refresh date |
| `docs/adr/notification/README.md` | Lists only ADR-0000 | ADR-0001 and 0002 written but not indexed | Add rows for 0001 and 0002 |
| `docs/plans/notification-5.1b-subscription-dispatcher-blueprint.md` | Already updated (v2) | — | No further changes needed |
| `docs/adr/notification/0001-...-boundary.md` | Proposed | — | Move to Accepted when implementation begins |
| `docs/adr/notification/0002-...-architecture.md` | Proposed | — | Move to Accepted when implementation begins |

## 6. What Was Discussed But NOT Yet Written Down

| Topic | Discussion | Where to capture |
|-------|-----------|-----------------|
| **UI button states** | Follow + Subscribe buttons in `channel.page.html` are disabled (`[disabled]` attribute) — they're shells waiting for 5.1b backend | Noted in ADR-0001 context; frontend wiring is 5.4 |
| **Stream-service zero-change impact for 5.1b** | The Follow button calls notification-service directly; stream-service needs no changes for this phase. Subscribe button (paid) goes through stream-service later | ADR-0001 §3 |
| **Dispatcher pipeline ordering rationale** | Persist first (required) → SSE (best-effort) → outbox (best-effort). If persist fails, nothing else runs. If SSE/outbox fail, notification is still durable | ADR-0002 §2 |
| **`OutboxService.enqueue()` fire-and-forget** | Uses `.subscribe()` not chained — outbox write failure does not roll back notification. Accepted risk | ADR-0002 risks table |
| **Latency budget** | Fan-out at 10M subscribers @ 5s poll + 100 chunk = up to ~17 min for last subscriber. Acceptable for non-real-time notifications | ADR-0002 risks |

## 7. Key Risks Carried Forward

1. **Dispatcher bloat** — if every new channel adds logic to the dispatcher, it becomes a God class. Mitigation: new channels use the outbox; poller handles channel-specific dispatch via pluggable adapters.
2. **Fan-out latency at scale** — outbox polling adds latency vs inline. Mitigation: configurable batch size + concurrency; parallel poller instances enabled by `SKIP LOCKED`.
3. **Orphaned subscriptions** — when a target is deleted, subscription rows become orphaned. Mitigation: harmless for MVP (no-op fan-out); cleanup mechanism needed when account deletion exists.
4. **Email adapter untested** — no SMTP server in dev environment. Mitigation: compile-only validation; defer integration testing to when SMTP is provisioned.
5. **Denormalized projection staleness** — if stream-service builds a subscriber system without telling notification-service, the `subscription` table goes stale. Mitigation: the Follow button calls notification-service directly; Subscribe path is owned by stream-service and includes notification-service in its workflow.

## 8. Database Design Compliance Check

No Flyway migrations or R2DBC entities were written this session (design-only). The designed V4 migration introduces:
- `notification_preference` — all Tier 1 types (`UUID`, `VARCHAR`, `BOOLEAN`, `TIMESTAMPTZ`). No compliance gap.
- `subscription` — all Tier 1 types. No compliance gap.

Compliance check will run fully when migrations and entities are actually written in the implementation session.
