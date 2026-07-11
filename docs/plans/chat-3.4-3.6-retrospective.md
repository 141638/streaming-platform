# Chat 3.4 (PBAC + Moderation) & 3.6 (Cache Hardening + Tests) — Implementation Retrospective

**Date:** 2026-07-11
**Status:** Complete (backend) — PBAC ships dark; Testcontainers suite unexecuted (no Docker); frontend moderation UI + auth-service grammar reconciliation deferred.

## 1. What was implemented (vs the plan)

| Planned item | Commit(s) | Notes |
|--------------|-----------|-------|
| Phase 0 shared foundation (seams) | `2bd28da` | Error envelope + advice, cache TTL, `SendGuard` seam, evict-on-write-failure |
| Blueprint doc | `f5e23f6` | Locked decisions + file partition |
| 3.4 PBAC capability (Layer 1) | `59435c6` | `security/` package ported, `PBAC-COMMON-CANDIDATE`, flag-gated dark |
| 3.4 Moderation / bans (Layer 2) | `59435c6` | `ChatBan` + repo, `BanSendGuard`, moderation REST API |
| 3.4 Two distinct 403 codes | `59435c6` | `AUTHZ_DENIED` (opaque body) vs `CHAT_USER_BANNED` |
| 3.6 Reconnect eviction | `863ee35` | `RedisReconnectListener` (ADR-0003 mech #2), off-thread subscribe |
| 3.6 Testcontainers cache suite | `863ee35` | 8 planned scenarios + TTL + evict-on-reconnect + evict-on-write-failure |
| ADR-0003 refresh + plan update | `a9090ab` | Scenario C + mechanism #3 + Deferred section |
| Review fixes (H1/M1/M2/Finding-1) | `59435c6`/`863ee35` | Applied before commit |

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking | Reason |
|------|-------------|----------|--------|
| PBAC live enforcement | after grammar fix | [ADR-0004](../adr/chat/0004-two-layer-chat-authorization.md) Risks, IMPLEMENTATION-PLAN §3.4 | auth-service `ent` grammar incompatible with matcher |
| Redis ban cache | scale trigger | `OPT(scale)` marker in `BanSendGuard` | PG indexed lookup sufficient at current scale |
| Periodic reconciliation sweep (Scenario C option 2) | drift metrics | ADR-0003 §Deferred + `TODO(3.x-deferred)` in `ChatService.cacheWrite` | evict-on-write-failure + TTL already bound the window |
| Eviction metrics counters | 6.x observability | ADR-0003 impl table step 5 | observability phase |
| Testcontainers suite execution | Docker host | ADR-0003 caveat, plan §3.6, commit `863ee35` body | no Docker in authoring env |

## 3. Deferred but NOT documented — gaps found during this retro (now tracked here)

| Item | Context | Recommended action |
|------|---------|--------------------|
| **Frontend moderation UI** | Moderation REST API (`/v1/rooms/{k}/bans`) has no consumer; `ChatPanelComponent` has no ban/unban controls | New frontend work item (Phase 4 viewer experience or a 3.x follow-up); backend contract is ready |
| **`CHAT_PBAC_ENABLED` / `CHAT_CACHE_ROOM_TTL` not in env/compose** | Both are `application.yml` env placeholders but no `main/env/*` or compose declares them; only in-code defaults apply | Add to chat env file + compose so operators can flip PBAC / tune TTL without code change |
| **PBAC is service-layer only — `SecurityConfig` unchanged** | Still `anyExchange().authenticated()`; moderation endpoints rely on JWT auth + service-layer `requireAccess`, not method security | Intentional (matches stream-service); noted so it's not mistaken for a gap |
| **No `bannedSubject` format validation beyond length** | `@Size(max=128)` added; not a UUID `@Pattern` (security M3) | Low-risk; accept or add pattern when subject format is finalized platform-wide |

## 4. Architectural decisions made during implementation

1. **Two-layer authorization (PBAC capability + table-backed ban).** Significant, deliberated at length. → **ADR-0004 written** (this session).
2. **Evict-on-write-failure for warm-key staleness (Scenario C).** Extends the cache-staleness decision. → Folded into **ADR-0003** (mechanism #3), not a new ADR (same problem domain).
3. **PBAC package isolation for 6.2 extraction + ships-dark flag.** → Captured in ADR-0004 (Decision + Alt 3).
4. **Phase-0 `SendGuard` seam** (`@ConditionalOnMissingBean` no-op default). → ADR-0004 Alt 2; pattern is simple enough to live in the ADR, not a standalone pattern doc.

No pattern docs warranted — all decisions fit existing ADR domains.

## 5. Documents to update (stale vs current)

| Document | Status | Action taken |
|----------|--------|--------------|
| ADR-0003 | was `proposed`, TTL+reconnect only | ✅ Updated → `accepted`, +Scenario C, +mechanism #3, +Deferred (commit `a9090ab`) |
| ADR chat README | missing 0004, stale 0003 title | ✅ Updated this retro |
| IMPLEMENTATION-PLAN §3.4/3.6 | unchecked, no notes | ✅ Updated (commit `a9090ab`); ADR-0004 reference added this retro |
| ADR-0004 | did not exist ("ADR to be recorded") | ✅ Written this retro |
| Blueprint doc | `In progress` | ✅ Marked Implemented this retro |

## 6. Updated execution order (actual vs planned)

Planned: Phase 0 → (Track A ∥ Track B) → converge. **Actual: matched exactly.** Track B adapted to Track A's `Jwt`-threaded `ChatService`/`ChatController` signatures in place during concurrent execution, so convergence had no merge conflict — only doc reconciliation + review fixes.

## 7. Key risks carried forward

1. **PBAC grammar mismatch blocks `chat.pbac.enabled=true`** — ~~cross-service (auth + chat) decision required~~ **RESOLVED 2026-07-11** via auth migration `V10__flatten_chat_pbac_grammar.sql` (option (a): flatten seed to 3-segment). Also flattened live master data + assigned `viewer` to 3 role-less users, unified message reads under `chat:message`, granted viewers `send`/`read_history` and streamers `chat:moderation:self moderate`. Matcher unchanged. See [ADR-0004](../adr/chat/0004-two-layer-chat-authorization.md) (grammar update + Risks) and [ADR-0005](../adr/chat/0005-moderation-domain-condition-triggered.md). Enforcement still ships dark — flip `CHAT_PBAC_ENABLED` after a token-refresh window.
2. **Testcontainers suite unvalidated** — must run `./gradlew :chat-service:test` on a Docker host before trusting the cache-hardening guarantees.
3. **Test flakiness (busy-wait polling)** — code-review Findings 2/3, test-only; harden with Awaitility when the suite is first run for real.
