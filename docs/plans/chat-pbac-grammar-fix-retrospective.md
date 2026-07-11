# Chat PBAC Grammar Mismatch — Fix Retrospective

**Date:** 2026-07-11
**Status:** Complete (backend + master data) — enforcement remains dark by choice (`chat.pbac.enabled=false`); commit pending.

Follow-up session to [chat-3.4-3.6-retrospective.md](chat-3.4-3.6-retrospective.md) §7 risk #1. Phase 3.4 shipped PBAC enforcement *dark* because the auth-service `ent` grammar was incompatible with the ported chat matcher. This session resolved that mismatch and the two policy-coverage gaps behind it.

## 1. What was implemented (vs the blueprint decisions)

Decisions were locked interactively (three `AskUserQuestion` rounds): **A1** flatten seed, **B** included, ship dark, plus live master-data revision.

| Item | Change | Location |
|------|--------|----------|
| Grammar flatten (4-seg → 3-seg) | `chat:message:room:*` → `chat:message:*`; `chat:moderation:room:*\|self` → `chat:moderation:*\|self` | `V10__flatten_chat_pbac_grammar.sql` (new) |
| Coverage B — viewers send | `policy.viewer.base` gains `chat:message:* … send read_history` | V10 |
| Coverage B — streamer self-moderate | `policy.streamer.live` gains `chat:moderation:self moderate` | V10 |
| Read consolidation (Proposal 1) | recent-read `ROOM/READ` → `MESSAGE/READ`; `chat:room` = metadata only | `ChatService.getRecentMessages` |
| Live master data | 3 policy UPDATEs applied to running DB; `viewer` assigned to 3 role-less users | `localhost:5432/streaming-platform` |
| Matcher regression tests | +5 cases incl. old 4-segment guard; all green | `EntitlementMatcherTest` |
| ADR-0004 grammar update + Risk resolved | grammar table + resolution note | `adr/chat/0004` |
| ADR-0005 (new) | moderation stays a chat kind; `moderation`-domain is condition-triggered | `adr/chat/0005` |
| PBAC doc reconciliation | §2 resource table, §5.2 example, §6.3 mapping → 3-segment | `PBAC-AUTHORIZATION.md` |

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking | Reason |
|------|-------------|----------|--------|
| Flip `chat.pbac.enabled=true` | operator runtime | ADR-0004 Risks; retro §7 | enable after a token-refresh window (in-flight tokens carry old grammar ≤15 min) |
| `moderation` as first-class domain (M2) | condition-triggered, **not** a phase | [ADR-0005](../adr/chat/0005-moderation-domain-condition-triggered.md) | adopt only if moderation goes cross-resource OR needs granular verbs |
| Per-room (not per-owner) moderator grants | with promotable-moderator feature | ADR-0005 Context | matcher resolves instance scope against `broadcasterSubject`; needs `roomKey` threaded into `RequiredAuthority` |
| `read_history` for viewer/streamer beyond recent | granted this session | — | folded in (viewer + streamer now hold `read_history`); tier-gating deep history is a future ABAC `attr.tier` condition |

## 3. What was deferred but NOT documented — gaps found during this retro

| Item | Context | Action taken |
|------|---------|--------------|
| `CHAT_PBAC_ENABLED` / `CHAT_CACHE_ROOM_TTL` still not in env/compose | Flagged in prior retro §3; operator must be able to flip the flag without a code change | Still open — no compose file in repo; carried forward (see §7) |
| Stale chat ADR table in IMPLEMENTATION-PLAN.md | Lines linked to non-existent files (`0001-layered-reactive-architecture.md`), missing 0004/0005 — predates this session | Fixed inline this retro |

## 4. Architectural decisions made during implementation

1. **Flatten over teach-the-matcher (A1 vs A2).** Keeps the matcher byte-identical to stream-service for the Phase 6.2 `pbac-common` extraction; loses only per-room in-token binding, which is unused (ownership resolved server-side, bans are Layer 2). → Folded into **ADR-0004** (grammar update + Risks resolution), not a new ADR.
2. **Message reads unified under `chat:message`.** `read` = recent/hot, `read_history` = durable page; `chat:room` reserved for metadata. → ADR-0004 grammar table.
3. **Moderation stays a chat kind; `moderation`-domain is condition-triggered.** Deliberate anti-speculation framing. → **ADR-0005 written.**

No new pattern docs warranted.

## 5. Documents updated (stale → current)

| Document | Was | Action |
|----------|-----|--------|
| ADR-0004 | grammar risk open (blocks enabling) | ✅ grammar update section + Risk marked RESOLVED (V10) |
| ADR-0005 | did not exist | ✅ written (condition-triggered moderation domain) |
| chat ADR README | missing 0005 | ✅ 0005 row added |
| PBAC-AUTHORIZATION.md | 4-segment chat grammar (§2/§5/§6.3) | ✅ flattened to 3-segment + grammar note |
| chat-3.4-3.6-retrospective.md | risk #1 open | ✅ risk #1 marked RESOLVED |
| IMPLEMENTATION-PLAN.md | §3.4 "Blocker / Decision needed"; stale ADR table; old date | ✅ resolved note, ADR table fixed, date bumped |

## 6. Execution order (actual vs planned)

Blueprint planned: diagnose → choose A1/B/dark → V10 → live reconcile → code → tests → docs. **Actual matched**, with two design refinements surfacing mid-session via dialogue (read consolidation; moderation-domain framing) — both folded in before implementation, no rework.

## 7. Key risks carried forward

1. **Enforcement still dark** — `chat.pbac.enabled=false`. Flip only after a token-refresh window; until then chat is authenticated but not `ent`-gated. Mitigated by startup WARN ([ChatAuthorization](../../main/source/backend/chat-service/src/main/java/com/streaming/chat/security/ChatAuthorization.java)).
2. **`CHAT_PBAC_ENABLED` not wired into env/compose** — operators cannot flip the flag declaratively yet. Carried from prior retro; still open.
3. **Testcontainers cache suite still unexecuted** — no Docker host in the authoring env; `ChatServiceTest` errors on container init (unchanged by this session).
