# Blueprint: Chat 3.4 (Full PBAC + Moderation) ∥ 3.6 (Cache Hardening + Integration Tests)

**Status:** Implemented (commits `2bd28da`, `59435c6`, `863ee35`, `a9090ab`) — see [chat-3.4-3.6-retrospective.md](chat-3.4-3.6-retrospective.md) · **Branch:** `feat/chat-3.4-3.6-pbac-moderation` · **Date:** 2026-07-11

## Summary

Deliver Phase 3.4 (moderation bans **and** distributed PBAC `ent` enforcement) and
Phase 3.6 (Testcontainers cache-aside test suite) as two parallel agent tracks.
A sequential **Phase 0 foundation** (committed) landed the three shared seams so
the two tracks touch disjoint files.

## Locked decisions

1. **Full 3.4**, but PBAC is isolated in a `security/` package mirroring
   stream-service, every file headed `// PBAC-COMMON-CANDIDATE — extract to
   pbac-common in Phase 6.2`. Removal later = delete package + delete
   `requireAccess(...)` call sites.
2. **Two-layer authorization:** Layer 1 = PBAC capability (`ent` in JWT, in-memory);
   Layer 2 = ban (resource-state, `chat_ban` table, checked in `SendGuard`). Bans are
   NOT policies-in-token (JWT can't be revoked mid-session; deny-semantics don't fit
   the allow-only matcher; per-(user×room) cardinality explodes).
3. **Ban rejection = `403` + `code:CHAT_USER_BANNED`** (distinct from PBAC
   `403 AUTHZ_DENIED`). Disambiguation via the envelope `code`, not the status class.
4. **Scenario-2 fix (dropped write to a warm key) = evict-on-write-failure** (Phase 0,
   done). Reconciliation sweep deferred (Option 2) with a marker.
5. **Ban lookup = PG-direct** this phase (indexed, tiny cardinality, same warm
   connection). Redis ban cache deferred with an `OPT(scale)` marker.
6. **PBAC ships behind `chat.pbac.enabled` (default off)** so enforcement lands dark
   until auth-service is confirmed to emit the chat `ent` templates.
7. Matcher stays **owner-identity-only** (`*` / `self` / literal). No CIDR/geo/range —
   YAGNI; a real attribute engine is a separate 6.2 concern.

## PBAC scope model (how `ent` resolves)

`EntitlementMatcher.scopeMatches(scope, ownerSubject, sub)`:
- `*` → always matches (owner ignored). Viewers get this.
- `self` → `ownerSubject.equals(sub)`; developer passes the **resource's** owner at
  enforcement (for chat: `room.getBroadcasterSubject()`). Late-bound — no UID in token.
- literal → `ownerSubject.equals(scope)`; only for deliberate delegated grants.

Policy templates issued by role (no resource enumeration): viewer =
`chat:room:* read`, `chat:message:room:* send`, `chat:message:room:* read_history`;
streamer adds `chat:moderation:room:self moderate`; staff = `chat:moderation:room:* moderate`.

## Endpoint → (domain, kind, action, ownerSubject)

| Endpoint | domain | kind | action | ownerSubject | Layer 2 |
|----------|--------|------|--------|-------------|---------|
| `POST /rooms/{k}/messages` | chat | message | send | `room.broadcasterSubject` | ban check |
| `GET /rooms/{k}` | chat | room | read | `room.broadcasterSubject` | — |
| `GET /rooms/{k}/messages/recent` | chat | room | read | `room.broadcasterSubject` | — |
| `GET /rooms/{k}/messages/recent?before=` | chat | message | read_history | `room.broadcasterSubject` | — |
| `GET/POST/DELETE /rooms/{k}/bans` | chat | moderation | moderate | `room.broadcasterSubject` | — |

## Phase 0 — Foundation (COMMITTED)

- `api/error/ChatApiError` + `ChatExceptionHandler` (404 CHAT_ROOM_NOT_FOUND,
  409 CHAT_ROOM_ARCHIVED). **Track A extends this class** with UserBanned/AccessDenied.
- `config/ChatCacheProperties` (`chat.cache.room-ttl`, 1h) + per-key `EXPIRE` on
  `RedisMessageCache.addToRecent`.
- `application/SendGuard` interface + `config/GuardConfig` no-op default
  (`@ConditionalOnMissingBean`). **Track A adds `@Component BanSendGuard`** → default backs off.
- `ChatService.sendMessage` refactored to `lookup → active → guard → persist → cacheWrite`;
  `cacheWrite` evicts the room key on a failed cache write.

## Track A — Full 3.4 (owns: security/, moderation, ChatBan, ChatService, ChatController, SecurityConfig, ChatExceptionHandler)

- Port `security/` from stream-service: `EntitlementMatcher`, `RequiredAuthority`,
  `AuthAction` (SEND, READ, READ_HISTORY, MODERATE, CREATE), `AuthResourceDomain` (CHAT),
  `AuthResourceKind` (ROOM, MESSAGE, MODERATION), `ChatAuthorization.requireAccess →
  Mono<Void>` throwing `ChatAccessDeniedException`. All `PBAC-COMMON-CANDIDATE`.
  Gate enforcement calls behind `chat.pbac.enabled` (default false).
- `ChatBan` entity (`chat.chat_ban`, V3) + `ReactiveChatBanRepository`
  (`findByRoomIdAndBannedSubject`, `findByRoomId`, `deleteByRoomIdAndBannedSubject`).
- `BanSendGuard implements SendGuard` (`@Component`) → active, unexpired ban →
  `UserBannedException`. Respect `expires_at` (expired = allow). PG-direct + `OPT(scale)` marker.
- `ModerationService` + `ModerationController` (`GET/POST/DELETE /v1/rooms/{k}/bans`) +
  `BanRequest`/`BanResponse`; gated by `ChatAuthorization` (moderate).
- Thread `Jwt` through `ChatController` → `ChatService` for send/read `requireAccess`.
- Add `UserBannedException` → 403 CHAT_USER_BANNED and `ChatAccessDeniedException` →
  403 AUTHZ_DENIED handlers to `ChatExceptionHandler`.
- Verify/extend auth-service policy templates emit the chat `ent` lines.
- Tests: port `EntitlementMatcherTest`; unit-test `BanSendGuard`, `ModerationService`.

## Track B — 3.6 Cache Hardening + Tests (owns: build.gradle.kts, RedisReconnectListener, all test files, application-test.yml)

- Testcontainers deps (junit-jupiter, postgresql, redis) + `application-test.yml`.
- `RedisReconnectListener` (ADR-0003 mech #2): on reconnect, `SCAN chat:room:*:recent`
  → `evictRoom` each.
- `RedisMessageCacheTest`: 1 write, 2 read, 3 retention(150→100), 4 cursor, 5 resilience,
  9 TTL-set-on-write, 11 evict-on-reconnect.
- `ChatServiceTest`: 6 cache-aside orchestration, 7 archived reject, 8 warm-up backfill,
  10 evict-on-write-failure (cache write fails → key rebuilt from PG, no hole).
- `ChatControllerTest` (`@WebFluxTest`): status + `code` assertions (uses Phase 0 advice).

## Convergence

Merge → refresh **ADR-0003** (add mechanism #3 evict-on-write-failure + Scenario 2 row;
Deferred section for reconciliation sweep; status proposed→accepted) → update
IMPLEMENTATION-PLAN (check 3.4/3.6, deferred rows) → code-reviewer + security-reviewer.
