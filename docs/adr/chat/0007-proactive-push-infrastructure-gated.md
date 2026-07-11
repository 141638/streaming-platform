# ADR-0007: Proactive Moderation Push is Infrastructure-Gated — Kafka Broker Ownership + Notification-Service Foundation are Hard Prerequisites

**Date**: 2026-07-12
**Status**: accepted
**Deciders**: hieuht, Claude

## Context

[ADR-0006](0006-moderation-ux-capability-and-push-split.md) designed the chat moderation UX as two tiers: a shipped **enforcement floor** (a banned user's send returns `403 CHAT_USER_BANNED`; the client disables input) and a future **proactive push layer** ("Wave 2") that tells the user *before* they try — via Kafka domain events → `notification-service` → SSE → a bell + in-stream popup + proactive input disable. The full design lives in [chat-moderation-wave2-proactive-push.md](../../plans/chat-moderation-wave2-proactive-push.md).

Building that layer is **not** primarily a coding task — it is blocked on infrastructure and a missing service foundation this team does not yet own:

1. **Kafka broker runtime.** The KRaft compose exists in-repo (`main/docker/kafka/single-broker/docker-compose.yaml`, external listener `:9094`, matching `notification-service`'s `KAFKA_BOOTSTRAP_SERVERS`), but this team does not operate a docker-native (or managed) runtime for it the way the stream team owns theirs. Without a reachable broker, the event backbone cannot be exercised end-to-end.
2. **notification-service foundation.** The service is a **scaffold**: Kafka *consumer* config, R2DBC/Flyway (`notification` schema with `notification_outbox` + `channel_subscription`), JWT, and Eureka are wired, but the only code is a `PingController` and a `StreamControlListener` that logs. There is no consume→persist, no REST, and no SSE.

A third, smaller dependency sits *inside* the wave (not a pre-gate): chat-service is **consume-only** today and must gain a Kafka producer.

Crucially, the Wave-1 enforcement floor already removes the poor "type → raw 403 → infer you're banned" UX. So the proactive layer is **additive and non-urgent** — its absence degrades gracefully to the reactive floor.

## Decision

**Treat Wave 2 as dependency-gated, not phase-scheduled** — the same posture as [ADR-0005](0005-moderation-domain-condition-triggered.md). We do **not** put it on a calendar and we adopt a deliberate *bias against* starting it until **both** hard prerequisites hold:

### Start-triggers (both required)
1. **Kafka is owned & operable by this team** — a broker reachable at the configured bootstrap in this team's environment, with topic create/inspect access (mirroring the stream team's setup).
2. **notification-service has a real foundation** — its own ADR-0000 plus consume→persist→REST, per the Wave-2 plan.

### Supporting decisions
- **Build notification-service as a general platform notification hub**, not a moderation one-off. Its scaffolded schema (`notification_outbox` + `channel_subscription`) already models reliable, multi-channel delivery; moderation is its *first client*, not its only shape.
- The proactive layer is **strictly additive** over the shipped floor. Wave 2 slipping (or never shipping) leaves a correct, complete baseline.
- Cross-service specifics (event contract, at-least-once + `eventId` dedup, fetch-based Bearer SSE, per-instance SSE fanout) are pinned in the Wave-2 plan and inherited from ADR-0006; this ADR only governs *whether/when* to start.

## Alternatives Considered

- **Build push now with an embedded/in-memory broker, or a direct chat-service→client WebSocket.** Rejected: an embedded broker is throwaway and doesn't prove the real topology; a direct chat push reintroduces exactly the coupling ADR-0006 §A3 rejected and bypasses the platform hub the scaffold already commits to.
- **Schedule Wave 2 for a fixed upcoming phase.** Rejected on the [ADR-0005](0005-moderation-domain-condition-triggered.md) principle: absent the triggers, "we'll want it eventually" is not sufficient reason to build. No trigger → don't build.
- **Skip the hub; give chat-service its own SSE endpoint for room ban events.** Rejected: duplicates the notification substrate that is already scaffolded, and splits user-facing notifications across two channels.

## Consequences

### Positive
- No speculative infrastructure or half-built service is created ahead of need.
- The Wave-1 floor is acknowledged as a complete baseline; the gate makes resumption a known, bounded build (the Wave-2 plan), not a surprise.
- Forces the notification-service to be designed once, as a reusable hub.

### Negative
- The banned user's real-time / rich experience (live disable, reason, countdown pushed) waits until the gate opens; two UX tiers (reactive floor now, proactive later) coexist in the meantime.
- When triggers fire, Wave 2 still carries real cross-service cost (producer, foundation, SSE, gateway config).

### Risks
- **Trigger creep** — a future moderation feature reaches for a bespoke push before the hub exists. Mitigation: the supporting decision + the plan mandate the general hub; the triggers are written narrowly (infra ownership + foundation), nothing softer.
- **Infra ownership never lands** — Wave 2 stalls indefinitely. Accepted: the floor is the baseline; this is a product/ops prioritization call, not a code gap.

## References
- [ADR-0006](0006-moderation-ux-capability-and-push-split.md) — moderation UX; enforcement-floor / proactive-push split
- [ADR-0005](0005-moderation-domain-condition-triggered.md) — condition-triggered (not phase-deferred) posture, reused here
- [chat-moderation-wave2-proactive-push.md](../../plans/chat-moderation-wave2-proactive-push.md) — the gated build plan
- `notification-service` scaffold (`PingController`, `V1` outbox + `channel_subscription`, `StreamControlListener`)
- `main/docker/kafka/single-broker/docker-compose.yaml` — the (unowned-by-this-team) broker definition
