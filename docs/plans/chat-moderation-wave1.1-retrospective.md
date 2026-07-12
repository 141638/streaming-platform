# Chat Moderation — Wave 1.1 — Implementation Retrospective (INTERIM)

**Date:** 2026-07-12
**Status:** **Partial / checkpoint** — backend (Workstreams A + B) written & **compile-green**, but **uncommitted** and the `:chat-service:test` suite **not yet run**; frontend (C–F) and docs (G) not started. Session paused to resume next working day.
**Branch:** `feat/chat-moderation-ux` (tip `bb59184`; this session added **0 commits** — all work is in the working tree).
**Plan:** `~/.claude/plans/zippy-cuddling-dragonfly.md` (approved). Progress mirror: memory `chat-moderation-ux-progress`.

> This is an *interim* retro taken mid-implementation at the user's request, before
> pausing. It deliberately does **not** accept any ADR or mark shipped work — nothing
> is committed or runtime-verified yet. Its value is capturing the session's decisions,
> the backend checkpoint, and (§3) the forward-gaps found while reviewing.

## 0. Session arc

1. **Feedback intake** — user raised 6 items after Wave 1 hands-on: (1) Kafka consumer log noise, (2) slow APIs, (3) ban-drawer UX (names-not-ids, broken duration dropdown, row redesign, unban-confirm), (4) new **edit-ban-duration-in-place** capability, (5) dead ban-dialog X button, (6) `chatBanned` on room metadata.
2. **Investigation** — two parallel read-only agents established root causes (see §4) before any code.
3. **Planning** — a plan was drafted and approved; two decisions were locked via the user (see §4).
4. **Implementation** — backend Workstreams A (schema + moderation API) and B (config hardening) completed and compiled; frontend + docs deferred to next session.

## 1. What was implemented (vs the plan)

| Plan item | Where | State |
|-----------|-------|-------|
| A1 `V5__chat_ban_usernames.sql` (banned_username, banned_by_username) | new migration | ✅ written, compile-green, **uncommitted** |
| A2 `ChatBan` +2 username fields, `create(...)` 8-arg, shared `isActive(now)` | `domain/ChatBan.java` | ✅ |
| A2 `BanSendGuard` delegates to `ban.isActive(now)` (dedupe) | `application/BanSendGuard.java` | ✅ |
| A2 `BanRequest.bannedUsername`, `BanResponse` +2 usernames, `BanDurationRequest` | `api/dto/*` | ✅ |
| A3 `ModerationService.ban(...)` capture usernames + **`updateBanDuration(...)`** + `BanNotFoundException` | `application/ModerationService.java` | ✅ |
| A4 `ModerationController` **`PATCH /rooms/{roomKey}/bans/{subject}`** + `JwtAttr.username(jwt)` | `api/ModerationController.java` | ✅ |
| A5 `RoomResponse.viewerBanned`; `getRoom` `Mono.zip(hasCapability, resolveViewerBanned)` + inject ban repo | `api/dto/RoomResponse.java`, `application/ChatService.java` | ✅ |
| A5 `CHAT_BAN_NOT_FOUND` → 404 | `api/error/ChatExceptionHandler.java` | ✅ |
| A6 tests: 5 files updated for new arities + new coverage (username capture, updateBanDuration re-base/permanent/404, PATCH controller, 4× viewerBanned) | `src/test/...` | ✅ compile-green, **not run** |
| B1 Kafka `listener.auto-startup: ${KAFKA_LISTENER_ENABLED:false}` + `NetworkClient`/`consumer.internals` log level ERROR | `chat-service/application.yml` | ✅ |
| B2 R2DBC pool `validation-query`/`max-acquire-time`/`max-idle-time` | `chat-service/application.yml` | ✅ |
| B3 gateway `httpclient.connect-timeout: 2000`, `response-timeout: 10s` | `gateway-service/application.yml` | ✅ |

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracked in | Reason |
|------|-------------|------------|--------|
| Frontend C (contracts + `ChatModerationService.updateDuration`) | next session | plan + memory | time-box; backend contract had to land first |
| Frontend D (dialog fix: `model()` visibility, drop `dismissableMask`) | next session | plan + memory | — |
| Frontend E (ban-row redesign, inline duration ladder, unban-confirm) | next session | plan + memory | — |
| Frontend F (`viewerBanned` → `bannedState` on room load) | next session | plan + memory | — |
| Docs G (ADR-0008, README row, `ng build` gate, `:chat-service:test` run) | next session | plan + memory | — |
| **Real-time push** of the duration-delta / ban / unban to the banned user | Wave 2 | ADR-0007, Wave-2 plan | infrastructure-gated (Kafka broker + notification-service foundation) |

## 3. Deferred but NOT documented — gaps found during this retro (highest-value sweep)

| # | Gap | Recommended action |
|---|-----|--------------------|
| G1 | **Backend tests compile but were never executed.** Runtime/logic risks not yet exercised: R2DBC `save()` issuing UPDATE on a loaded `ChatBan` (`isNew=false`), and the new `Mono.zip` in `getRoom`. | Run `:chat-service:test` **first** next session; treat A as unverified until green. |
| G2 | **Gateway `response-timeout: 10s` will kill Wave-2 SSE.** The notifications SSE route (`/api/notifications/stream`) is long-lived; a 10s global response-timeout would sever it. | When Wave-2 lands, exclude the SSE route from the global timeout (per-route `response-timeout: -1` or a dedicated route). **Added a risk row to the Wave-2 plan** (this retro). |
| G3 | **Perf fix applied but not empirically confirmed.** The gateway/R2DBC config addresses the *most likely* cause; the direct-vs-gateway discriminating test was not run, so we may have treated a plausible-but-wrong cause. | Run the direct-call-vs-gateway comparison next session before claiming the slowness is fixed. |
| G4 | **Denormalized usernames on `chat_ban` can go stale** (a later rename isn't reflected). Same accepted tradeoff as `chat_message.author_username`, but not written down. | Document as a known tradeoff in ADR-0008; acceptable for KISS (display-only, sub is the identity). |
| G5 | **`updateBanDuration` overwrites `expiresAt` with no history/audit** of duration changes. | Note in ADR-0008; a duration-change audit trail is a condition-deferred concern, not needed now. |
| G6 | **`auto-startup: false` makes `StreamControlListener` inert locally** — STREAM_CREATED room-lifecycle won't fire unless `KAFKA_LISTENER_ENABLED=true`. | Behaviour change captured in the yaml comment + memory; call out in ADR-0008 so it isn't a surprise. |
| G7 | **CRLF/LF churn**: git warned LF→CRLF on every touched backend file. | Cosmetic; ensure `.gitattributes`/editor keeps LF so the eventual diff is content-only, not line-ending noise. |

## 4. Architectural decisions made this session (candidates for ADR)

1. **Modify-ban-duration in place** (`PATCH …/bans/{subject}`, re-base `expiresAt` off *now* on the loaded row) instead of unban+re-ban. *Why:* one moderator action, one future notification (vs two), preserves the `(room, subject)` row. → **ADR-0008** (write next session, with the code).
2. **`viewerBanned` room-metadata signal** — the enforcement floor on room load, independent of the (future) push pipeline; mirrors `viewerCanModerate`, reuses `ChatBan.isActive`. → **ADR-0008**.
3. **FE-supplied `bannedUsername` + denormalized storage** (locked decision) over a cross-service auth lookup. *Why:* KISS, client already holds the name, matches `chat_message.author_username` precedent. → note in **ADR-0008** with the staleness tradeoff (G4).
4. **Config-only perf + log-noise hardening** (locked decision: apply now). Kafka `auto-startup` gate, R2DBC pool liveness, gateway timeouts. *Why:* root cause is infra (gateway↔Eureka), not the auth guard; all reversible. → summarise in **ADR-0008** (or a short perf note) with the SSE caveat (G2).
   - **Recommendation:** a single **ADR-0008** ("modify-ban-duration + `viewerBanned` metadata + config hardening") next session, authored alongside the commit. Do **not** back-date or pre-accept it now — nothing is shipped.

## 5. Documents to update (state)

| Document | State | Action |
|----------|-------|--------|
| `docs/adr/chat/0008-*.md` | does not exist | **create next session** (§4), status Accepted at commit time |
| `docs/adr/chat/README.md` | current | add ADR-0008 row **when the file exists** (not before — avoid a dead link) |
| `docs/plans/chat-moderation-wave2-proactive-push.md` | slightly stale | **updated in this retro**: `updateBanDuration` emit-point + SSE-vs-gateway-timeout risk (G2) |
| memory `chat-moderation-ux-progress` | current | already updated with the full checkpoint |
| this retrospective | new | created this session |

## 6. Execution order (planned vs actual)

| Planned | Actual | Status |
|---------|--------|--------|
| A backend schema+API | done, compile-green, uncommitted | ✅ (test run pending) |
| B config hardening | done | ✅ (runtime verify pending) |
| C–F frontend | — | ⏳ next session |
| G tests+docs | — | ⏳ next session |

On-plan; no re-ordering. Nothing committed yet → the first commit(s) happen next session after `:chat-service:test` is green.

## 7. Risks carried forward

1. **Unverified backend** — compile-green ≠ test-green. Run `:chat-service:test` before building on it (G1).
2. **Perf fix may target the wrong cause** — confirm with the direct-vs-gateway test (G3).
3. **Gateway timeout vs future SSE** — must be excluded for the Wave-2 stream (G2, now in the Wave-2 plan).
4. **Uncommitted work** — a large working-tree delta could be lost if the tree is disturbed; state is captured in memory + this doc as insurance. Commit early next session.
5. **Denormalized-username staleness** — accepted, to be documented in ADR-0008 (G4).
