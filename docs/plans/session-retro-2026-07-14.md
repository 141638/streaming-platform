# 2026-07-14 — Session Retrospective

**Date:** 2026-07-14
**Status:** Complete

## 1. What was implemented

| Item | Description | Tracking |
|------|-------------|----------|
| Telemetry gap diagnosis | Root-caused why agents (planner, architect, tdd-guide, code-reviewer) showed zero telemetry — commands inject context without hitting the Skill tool, and lightweight pet-project policy skips agent delegation | Inline in consult session |
| Telemetry `--command` self-reporting | Expanded `telemetry-track.mjs` with CLI mode; added self-report instruction to all 12 command files so `/blueprint`, `/implement`, `/perform`, etc. are counted | 16 files modified |
| Stream-team doc reconciliation | Updated `IMPLEMENTATION-PLAN.md` (outbox done, Phase 4 partial, Phase 5 consumer infra), `outbox-and-phase4-blueprint.md` (implementation log with commit hashes) | Applied live during session |
| Notification ADR-0000 | Architecture foundation for notification-service: layered reactive, hub pattern, third-party catalog, forward-looking sketch | `docs/adr/notification/0000-architecture-foundation.md` |

## 2. What was deferred (documented)

| Item | Deferred to | Tracking doc |
|------|------------|--------------|
| Notification foundation build (N1–N5) | Next session | ADR-0000 + IMPLEMENTATION-PLAN.md § Phase 5 |
| Chat Wave 2 proactive push | After notification foundation (gate per ADR-0007) | [chat-moderation-wave2-proactive-push.md](chat-moderation-wave2-proactive-push.md) |
| Viewer presence (4.4) | Future Phase 4 session | IMPLEMENTATION-PLAN.md checklist |

## 3. What was deferred but NOT yet documented

None. All known gaps are tracked.

## 4. Architectural decisions made

1. **Command telemetry self-reporting pattern**: Commands (`.claude/commands/*.md`) inject context directly without hitting the Skill tool, making them invisible to PostToolUse hooks. The fix adds a `--command <name>` CLI mode to the telemetry script and a self-report instruction in each command file. This is a **harness pattern** (not a product ADR) — the convention lives in the code (`node .claude/scripts/telemetry-track.mjs --command <name>` at the top of every command).

2. **Notification service as general platform hub** (ADR-0000): The service is designed as a multi-category, multi-channel notification hub from the start. Moderation push (Wave 2) is its first client, not its only shape. The `notification_outbox` + `channel_subscription` schema (V1) already models this generality. This is an **ADR** — captured in `docs/adr/notification/0000-architecture-foundation.md`.

## 5. Documents updated

| Document | What changed |
|----------|-------------|
| `IMPLEMENTATION-PLAN.md` | Header metadata (outbox ✅, Phase 4 in progress), Phase 4 checklist checked off (4.0–4.3), Phase 6 added 6.0c (outbox) checked off, Phase 5 added 5.0b (consumer infra) + ADR table, next-session updated |
| `outbox-and-phase4-blueprint.md` | Status updated, implementation log added (9 commits across Phase A + B) |
| `docs/adr/notification/0000-architecture-foundation.md` | **New** — notification service architecture ADR |
| `docs/adr/notification/README.md` | **New** — ADR index |
| `.claude/scripts/telemetry-track.mjs` | Added `--command <name>` CLI mode alongside stdin/hook mode |
| 12 `.claude/commands/*.md` files | Added self-report telemetry instruction to each command |

## 6. Key risks carried forward

1. **SSE gateway timeout**: Gateway's global 10s `response-timeout` kills long-lived SSE connections. Mitigation: exclude `/api/notifications/stream` with per-route `response-timeout: -1` during Task N4.
2. **`blockOptional()` in Kafka listener**: Pragmatic bridge between reactive Redis and blocking `@KafkaListener`. Acceptable for foundation phase; switch to `ReactiveKafkaConsumerTemplate` if throughput becomes a bottleneck.
3. **Notification outbox table growth**: V1 `notification_outbox` is designed for downstream channels (email) that don't exist yet. No poller runs until a channel producer is active — no immediate risk.
