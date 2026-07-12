# Chat Moderation — Wave 1.1 — Implementation Retrospective

**Date:** 2026-07-12 (updated — post-promotion follow-up appended, §8)
**Status:** **Complete** — backend + frontend shipped and committed; a follow-up dialog refactor (`95eca24`) landed after promotion (§8). Items flagged for the user's environment (perf confirmation, Testcontainers, visual check) remain open.
**Branch:** `feat/chat-moderation-ux` (tip `95eca24`). This session added 5 feature/config/doc commits on top of `bb59184`, plus the retro-promotion (`86b9539`) and the dialog follow-up (`95eca24`).
**Plan:** `~/.claude/plans/zippy-cuddling-dragonfly.md` (approved). Progress mirror: memory `chat-moderation-ux-progress`.

> An earlier revision of this file was an *interim* checkpoint (backend written but
> uncommitted, tests not run). This revision reconciles the finished session:
> all workstreams landed, the backend test blocker was fixed, and the work is
> committed feature-by-feature.

## 0. Session arc

1. **Feedback intake** — 6 items after Wave 1 hands-on: Kafka log noise, slow APIs, ban-drawer UX (names-not-ids, broken duration dropdown, row redesign, unban-confirm), edit-ban-duration-in-place, dead ban-dialog X, `chatBanned` on room metadata.
2. **Blueprint + baseline verify** — surveyed the real FE code; ran the backend suite and caught a red test (interim retro risk G1 materialized).
3. **Pre-flight asks** — three durable principles captured (embedding-first schema, test-env deferral, lightweight testing scope); an unrelated auth-service startup failure investigated and root-caused.
4. **Implementation** — A-fix → Frontend C→D→E→F → docs G, then 5 grouped commits.

## 1. What was implemented (vs the plan)

| Plan item | Commit | Notes |
|-----------|--------|-------|
| A backend: `V5` usernames, `ChatBan.isActive`, `updateBanDuration` (PATCH), `viewerBanned`, `BanNotFoundException`→404 | `543309b` | from prior session; **A-fix** this session made the suite green |
| A-fix: `reBasesExpiry` test modelled a DB-hydrated row (`setNew(false)`) + `isSameAs` identity assertion | `543309b` | closes interim gap G1 (test asserted R2DBC hydration a Mockito fixture can't reproduce) |
| B config: Kafka `auto-startup` gate + log levels; R2DBC pool liveness; gateway timeouts | `064b8ee` | config-only, reversible |
| C frontend: contracts (+`viewerBanned`, +ban usernames) + `ChatModerationService.updateDuration` | `fd56eee` | |
| D frontend: dialog two-way `model()` + drop `dismissableMask` (fixes dropdown/reason-wipe/X); forward `bannedUsername` | `fd56eee` | one root cause, three symptoms |
| E frontend: ban-row redesign (names, unlock icon, raw expires-in, chevron ladder), unban-confirm dialog, wider drawer | `fd56eee` | |
| F frontend: `viewerBanned` → `bannedState` on room load | `fd56eee` | floor stays backstop |
| G: FE specs updated, `ng build` gate, ADR-0008, README row, Wave-2 emit-note | `fd56eee` (specs), `dfc2b53` (docs) | |
| Unrelated: auth-service ecj type-inference test errors | `2824c11` | split out — not chat |

**Verification run:** chat-service Mockito tests green (incl. A-fix); `ng build` green; spec `tsc` green. (Testcontainers not run — see §3.)

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracked in | Reason |
|------|-------------|------------|--------|
| Real-time push of the duration-delta / ban / unban | Wave 2 | ADR-0007, Wave-2 plan (`// Wave 2` emit-marker in `updateBanDuration`) | infrastructure-gated (Kafka broker + notification-service foundation) |
| Duration-change audit trail | condition-triggered | ADR-0008 (G5) | not needed until an audit requirement appears |
| Uploaded-avatar embedding on `chat_ban` | condition-triggered | ADR-0008 (D3) | avatars are derived (DiceBear) today; embed only when real uploads exist |

## 3. Deferred but not fully resolved — gap sweep (updated)

| # | Gap | Status |
|---|-----|--------|
| G1 | Backend tests compiled but never run | **Resolved** — ran the suite, found + fixed `reBasesExpiry`; Mockito set green (`543309b`) |
| G2 | Gateway `response-timeout: 10s` will sever Wave-2 SSE | **Documented** — risk row in Wave-2 plan + ADR-0008; exclude `/api/notifications/stream` when Wave 2 lands |
| G3 | Perf fix not empirically confirmed | **Open (flagged to user)** — run the direct-vs-gateway comparison before declaring the slowness closed; noted as an ADR-0008 risk |
| G4 | Denormalized usernames go stale on rename | **Documented** — accepted tradeoff in ADR-0008 (D3); `subject` stays the identity |
| G5 | `updateBanDuration` overwrites `expiresAt` with no history | **Documented** — ADR-0008; condition-deferred |
| G6 | `auto-startup:false` makes `StreamControlListener` inert locally | **Documented** — yaml comment + ADR-0008 |
| G7 | CRLF/LF churn on touched files | **Ongoing (cosmetic)** — git normalizes on commit; `.gitattributes` LF would remove the noise |
| G8 | Testcontainers tests (`ChatServiceTest`, `RedisMessageCache`, `RedisReconnectListener`) not run — no Docker locally | **Flagged to user** per `test-env-deferral-policy`; run when Docker is up |
| G9 | FE specs can't run locally (Karma needs Chrome) | **Flagged** — gate was `ng build` + `tsc -p tsconfig.spec.json`; run Karma in CI |

## 4. Architectural decisions made this session

1. **ADR-0008 written & Accepted** (`dfc2b53`): modify-ban-duration in place, `viewerBanned` room metadata, the **embed-cross-service-display-fields** convention, and config-only perf/log hardening. Supersedes the interim "write next session" note.
2. **Embed-cross-service reference data** promoted from the `chat_message.author_username` precedent to a **standing convention** (ADR-0008 D3 + memory `embed-cross-service-reference-data`): store a denormalized copy of another service's display fields at write time; never id-only + read-time cross-service lookup.
3. **Test-environment deferral** (memory `test-env-deferral-policy`): tests needing Docker/Redis/Kafka/MCP are written + flagged, not run locally.
4. **Lightweight testing scope for this pet project** (memory `lightweight-testing-scope`): relax TDD/E2E/80%-coverage; a few simple tests + cheap gates (build/type-check). Overrides `.claude/rules/common/testing.md` defaults here.
5. **VS Code launch needs `processResources`** (memory `vscode-launch-needs-processresources`): a backend service launched from VS Code runs with stale/missing `application.yml` unless its launch config has a `processResources` preLaunchTask → surfaces as "datasource url not specified." Diagnosed for auth-service; the user fixed via a VS Code cache refresh (the launch-config edit was reverted).

## 5. Documents updated / created this session

| Document | State | Action taken |
|----------|-------|--------------|
| `docs/adr/chat/0008-*.md` | created, **Accepted** | modify-duration + viewerBanned + embed convention + config hardening (`dfc2b53`) |
| `docs/adr/chat/README.md` | current | ADR-0008 row added |
| `docs/plans/chat-moderation-wave2-proactive-push.md` | current | `updateBanDuration` emit-point + response-timeout-vs-SSE risk row |
| this retrospective | promoted to **Complete** | interim → final with commit hashes |
| `docs/IMPLEMENTATION-PLAN.md` | current | no change — it doesn't track at Wave-1.1 granularity (the dedicated plan + ADRs do) |
| memory (`chat-moderation-ux-progress`, +4 new) | current | progress + 4 new principles recorded |

## 6. Execution order (planned vs actual)

| Planned | Actual | Commit |
|---------|--------|--------|
| A-fix → confirm green | done | `543309b` |
| C → D → E → F | done in order | `fd56eee` |
| G tests + docs | done | `fd56eee` / `dfc2b53` |
| commit feature-by-feature | 5 commits (auth split out, chat-be, config, fe, docs) | `2824c11`…`dfc2b53` |

On-plan; the only addition was the auth-service side-quests (ecj fix committed; startup diagnosis → memory).

## 7. Risks carried forward

1. **Perf fix may target the wrong cause** — confirm with the direct-vs-gateway test (G3).
2. **Gateway timeout vs future SSE** — must exclude the notifications stream in Wave 2 (G2).
3. **Testcontainers + Karma unrun locally** — CI (with Docker/Chrome) is the real gate (G8/G9).
4. **Denormalized-username staleness** — accepted; documented in ADR-0008 (G4).
5. **Wave 2 remains dependency-gated** — Kafka broker ownership + notification-service foundation (ADR-0007); nothing here depends on it (the enforcement floor is complete).

## 8. Post-promotion follow-up (appended this session)

After this retrospective was promoted to Complete (`86b9539`), one more change landed and a second retro pass swept the delta.

### 8.1 Dialog rendered on demand (`95eca24`)
The Wave-1.1 dialog fix (`fd56eee`: two-way `model()` + drop `dismissableMask`) treated the *symptom*. Hands-on use still wiped the ban dialog's in-progress reason. Root cause, found this session: the dialog was **rendered eagerly for every viewer** and reset its form inside a **`visible`-watching `effect()`**, which re-fires on any re-touch of the two-way binding. **Fix:** render on demand — `@if (banDialogOpen())` in `chat-panel` — so a fresh instance initializes the form clean from its field initializer; the reset `effect()` + empty constructor were deleted; `<form [formGroup]>` → `<div [formGroup]>`; `p-select` got `appendTo="body"`; the spec asserts a fresh instance starts clean. `ng build` + spec `tsc` green. Not converted to PrimeNG `DialogService` (the lighter `@if` lazy-render satisfied "render on demand"). This is the **only Wave-1.1 fix that needed a second pass** — the session's key learning.

### 8.2 Pattern captured globally
The generalized anti-pattern → fix was extracted to a global learned skill: `~/.claude/skills/learned/angular-dialog-reset-via-render-on-demand.md` (`/learn-eval`, verdict Save/Global). Reusable in any Angular 17+ project; no ADR (a pattern, not an architectural decision).

### 8.3 Team-retro insight — D2 narrowed Wave 2's remaining scope
`viewerBanned` (ADR-0008 D2) delivered a **load-time proactive disable without the push pipeline**, via room metadata. ADR-0006 had parked "disable before you try" in Wave 2. Net effect: **Wave 2's remaining value is now only live *mid-session* push + rich reason/countdown** — the "banned on load" case is already solved by the floor + D2. Wave 2 is less urgent than ADR-0006 originally framed; a scope note for whoever resumes it.

### 8.4 Fresh gap sweep (delta since promotion)
| # | Gap | Status |
|---|-----|--------|
| G10 | Dialog needed two fix passes (symptom, then root cause) | **Resolved + generalized** — `95eca24` + global skill (§8.2) |
| G11 | Repo hygiene: stray root `package.json`/`package-lock.json` (npx/prettier-hook junk); a transient `typescript-eslint` devDep churn in `streaming-ui/package.json` | **Resolved** — root junk removed; the devDep reverted (working tree matches HEAD, clean) |
| G12 | Meta north-star drift: memory `multi-agent-orchestration-goal` says full-pipeline multi-agent orchestration, but Wave 1/1.1 ran mostly single-threaded | **Open (surfaced to user)** — hold, scope down, or retire the goal |

### 8.5 Open follow-ups (carried forward — the session task list was closed; these live in docs so a new session can pick them up)

- **Perf confirmation (G3):** run the direct-vs-gateway measurement before declaring the Wave-1.1 slowness fixed. Tracked in §3 G3, §7, and ADR-0008 risks.
- **Testcontainers + Karma in CI (G8/G9):** unrun locally (no Docker / no Chrome); CI is the real gate. Tracked in §3.
- **Rebase + SSE-timeout exclusion (G2):** rebase `feat/chat-moderation-ux` onto develop once the PBAC branch (`feat/chat-3.4-3.6-pbac-moderation`) merges; when Wave 2 lands, exclude `/api/notifications/stream` from the gateway `response-timeout`. Tracked in memory `chat-moderation-ux-progress` + Wave-2 plan (G2).
- **Wave 2 (gated):** proactive push stays dependency-gated (Kafka broker ownership + notification-service foundation). Design lives in [ADR-0007](../adr/chat/0007-proactive-push-infrastructure-gated.md) + `chat-moderation-wave2-proactive-push.md` — now including the **notification cadence / de-spam** design (below).

### 8.6 Further chat-UI refinements (this session, after the retro promotion)

On top of the dialog refactor (§8.1), a `/consult` on the banned-user Wave-2 experience surfaced a notification-spam risk in the duration editor, which drove a UX pass over the moderation surface:

| Commit | Change |
|--------|--------|
| `32d0ff1` | **Commit-once duration editor** — chevrons stage a local pending rung; one `durationChange` emits on apply. This is *layer 1* of the Wave-2 de-spam design (Wave-2 plan → *Notification cadence & de-spam*). |
| `5bd4c33` | **Stacked ban-row card** — identity header + divider + action footer; unban became a filled button; duration wrapped in a bordered group. |
| `951b5b6` | **Row polish** — full-width divider + full-wrap reason (length-capped at source, so no clamp needed). |
| `89add3f` | **Unban-confirm fixes** — the drawer's unban dialog X-close was broken (one-way `[visible]` + post-animation `onHide` reasserted `visible=true`); fixed with `(visibleChange)` (same desync class as the ban dialog). The per-message row unban now confirms too, built with the same pattern. |

The Wave-2 server/presentation layers from that consult (latest-wins coalescing + semantic notification tiering) are recorded in the Wave-2 plan (D6 + the cadence section), not built.
