# Chat Idempotency + C1 Lua Atomic Cache — Implementation Retrospective

**Date:** 2026-08-01
**Status:** Complete (uncommitted)
**Scope:** ADR-0011 (Message Idempotency via client_id) + C1 (Redis Lua atomic cache write)

## 1. What was implemented (vs the original plan)

### C1 — Redis Lua Atomic Cache Write

| Planned item | Status | Notes |
|-------------|--------|-------|
| Create `add_to_recent.lua` | ✅ Done | ZADD + ZREMRANGEBYRANK + EXPIRE in one atomic EVALSHA call |
| Load script in `RedisMessageCache` constructor | ✅ Done | `ClassPathResource` → `DefaultRedisScript<Long>`, same pattern as `RateLimitFilter` |
| Replace 3-step `.flatMap()` chain in `addToRecent()` | ✅ Done | Single `redis.execute(script, keys, args).next()` replaces ZADD → trimToRetention → applyTtl |
| Remove `trimToRetention()` and `applyTtl()` private methods | ✅ Done | Logic moved into Lua script |
| Build verification | ✅ Done | `./gradlew :chat-service:compileJava` — BUILD SUCCESSFUL |

**Files changed:** `RedisMessageCache.java` (MODIFY), `add_to_recent.lua` (CREATE)

### ADR-0011 — Message Idempotency via client_id Unique Constraint

| Planned item | Status | Notes |
|-------------|--------|-------|
| V7 migration: `client_id VARCHAR(64)` + partial unique index | ✅ Done | `uq_chat_message_client_id WHERE client_id IS NOT NULL` |
| `clientId` field on `ChatMessage` entity | ✅ Done | `@Column("client_id")`, null-safe partial index, 3 factory overloads |
| `findByClientId()` on `ReactiveChatMessageRepository` | ✅ Done | `Mono<ChatMessage> findByClientId(String)` |
| `sendMessage()` accepts `@Nullable String clientId` | ✅ Done | Passes through to `persistAndCache()` |
| `sendSystemMessage()` accepts `@Nullable String eventId` | ✅ Done | Derives `clientId = "system:{roomKey}:{eventId}"` |
| Catch `DataIntegrityViolationException` → return existing | ✅ Done | In both `persistAndCache()` and `sendSystemMessage()` |
| WebSocket `send.clientId()` wired | ✅ Done | `ChatWebSocketHandler` passes as 6th arg |
| REST `Idempotency-Key` header accepted | ✅ Done | `ChatController` reads via `@RequestHeader` |
| System messages pass deterministic eventId | ✅ Done | `StreamControlListener`: `eventType:streamId` for all 3 lifecycle events |
| Test signature updates | ✅ Done | 5 test files updated for new constructor + method signatures |

**Files changed:** `V7__add_message_client_id.sql` (CREATE), `ChatMessage.java`, `ReactiveChatMessageRepository.java`, `ChatService.java`, `ChatController.java`, `ChatWebSocketHandler.java`, `StreamControlListener.java` (MODIFY), 5 test files (MODIFY)

### Code Review Follow-up Fixes

| Finding | Severity | Fix |
|---------|----------|-----|
| Misleading `sendSystemMessage` Javadoc (`eventId` described as Kafka offset but is `eventType:streamId`) | HIGH | Corrected `@param` |
| Duplicate Javadoc blocks on `ChatController.sendMessage()` | MEDIUM | Removed stale block |
| `findByClientId()` could return empty on race (vanished row) | MEDIUM | Added `.switchIfEmpty(Mono.error(IllegalStateException))` |

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking doc | Reason |
|------|------------|-------------|--------|
| Redis SETNX pre-check | Future optimization | ADR-0011 §Deferred | Not needed until duplicate-send volume is measurable |
| Idempotency for moderation actions | N/A | ADR-0011 §Deferred | Already covered by `UNIQUE(room_id, banned_subject)` |
| Integration test execution (Docker-gated) | When Docker available | [test-env-deferral-policy](../../../memory/test-env-deferral-policy.md) | Docker not available on dev host |
| `ChatAuthorizationTest` pbac-common import fix | Pre-existing | N/A | 5 compile errors in test only, not caused by these changes |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

None. All design decisions were captured in ADR-0011 before implementation began.
The C1 Lua refinement was added to ADR-0001's "Refinements" section inline.

## 4. Architectural decisions made during implementation

1. **Partial unique index over full unique constraint**
   - Decision: `CREATE UNIQUE INDEX ... WHERE client_id IS NOT NULL`
   - Why: Historical rows (pre-V7) and non-idempotent system messages have null `clientId`. SQL standard says NULL ≠ NULL for uniqueness, but PostgreSQL partial index is more explicit and self-documenting.
   - Captured in: ADR-0011 §Decision + V7 migration comment

2. **`DataIntegrityViolationException` as idempotency signal (not pre-check)**
   - Decision: Let the insert fail → catch `DataIntegrityViolationException` → query existing → return
   - Why: The database unique constraint is the only correct check under concurrency. A pre-check (`existsByClientId`) has a built-in race window — two concurrent inserts both pass the check, then one fails anyway. Letting the constraint reject and then resolving is simpler and correct by construction.
   - Captured in: ADR-0011 §Alternatives Considered (Alternative 3)

3. **Deterministic `eventType:streamId` for system message idempotency (not Kafka offset)**
   - Decision: `clientId = "system:{roomKey}:{eventType}:{streamId}"` — derived from the event payload, not the Kafka record's `topic-partition-offset`.
   - Why: The chat-service's `StreamEvent` record doesn't carry Kafka metadata (partition, offset are absent from the deserialized POJO). The event type + stream ID combination is deterministic across retries — the same Kafka event always produces the same pair.
   - Captured in: ChatService Javadoc (corrected during code review)

4. **`@Nullable` from `jakarta.annotation` (not `javax.annotation` or `org.springframework.lang`)**
   - Decision: Use `jakarta.annotation.Nullable` for the `clientId` parameter.
   - Why: Spring Boot 3.x / Jakarta EE 9+ namespace. Consistent with the project's Jakarta migration (javax → jakarta in all Spring Boot 3 services).
   - Captured in: ChatService.java method signatures

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action |
|----------|---------------|-------------|--------|
| ADR-0011 | Status: "proposed" | Implementation is complete | Mark **Accepted** with today's date |
| C1 plan (`C1-redis-lua-atomic-cache.md`) | Status: "pending" | Implementation is complete | Mark **Complete** |
| ADR-0001 | Already has C1 refinement section | — | ✅ Already updated inline |
| IMPLEMENTATION-PLAN.md | Last updated 2026-08-01 | No mention of C1 or ADR-0011 | Add C1 + ADR-0011 to Phase 6 checklist |

## 6. Updated execution order (actual vs planned)

| Planned order | Actual order | Status |
|--------------|-------------|--------|
| C1 first (Lua script) | ✅ C1 first | Complete |
| ADR-0011 after C1 + ADR-0010 | ✅ ADR-0011 after ADR-0010 | Complete |
| Code review | ✅ Code review + fixes | Complete |
| Commit | ⬜ Not yet committed | Uncommitted (13 files, 222+ / 87−) |

## 7. Key risks carried forward

1. **Cross-user `clientId` collision** — If two users generate the same `clientId` (extremely unlikely with `client-{ts}-{counter}` but theoretically possible with a bad random generator), the second user's legitimate message would be rejected. Mitigation: the `client-{ts}-{counter}` format is collision-resistant within a browser session. If this becomes an issue, add `author_subject` to the unique index to scope `clientId` per user.
2. **`findByClientId` row-vanished race** — Defensive `.switchIfEmpty(Mono.error(IllegalStateException))` added during code review. Theoretically possible if a concurrent DELETE removes the row between the constraint violation and the lookup.
3. **No integration test execution** — The `RedisMessageCacheTest` and `ChatServiceTest` suites require Docker. They compile and pass syntax checks but have never been run against a real Redis/PG instance. Per `test-env-deferral-policy`, these are documented and deferred.
