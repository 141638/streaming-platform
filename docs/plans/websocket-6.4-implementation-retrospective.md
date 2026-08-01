# WebSocket 6.4 — Implementation Retrospective

**Date:** 2026-08-01
**Status:** Complete (Tasks 1-5 implemented; Task 6 integration tests deferred)

## 1. What was implemented (vs the original plan)

| Planned item | Commit(s) | Notes |
|-------------|-----------|-------|
| Task 1: Frame protocol + PubSub service | `37ab6c8` | `WebSocketFrame.java` (sealed type hierarchy), `RoomPubSubService.java` (lazy subscription lifecycle), `ChatService.java` modified |
| Task 2: WS handler + config | `37ab6c8` | `ChatWebSocketHandler.java` (218 lines), `WebSocketConfig.java` (feature-flagged), `SecurityConfig.java`, `application.yml` |
| Task 3: Gateway route config | `dd896ad` | `chat-service-ws` route with `response-timeout: -1`, listed before catch-all |
| Task 4: Frontend WS service | `fa16ae9` | `ChatWebsocketService` (251 lines): full lifecycle, exponential backoff, clientId correlation, 30s send timeout |
| Task 5: Wire into ChatPanelComponent | `fa16ae9` | WS as primary send+receive path; REST polling preserved as fallback on `fallback$` emission |
| Task 6: Integration test | **Deferred** | See §2 |

**Implementation followed the full-duplex design from ADR-0010** (Decision #2):
send + receive over one WebSocket connection with Redis Pub/Sub cross-instance fan-out.
REST endpoints are kept as fallback (Decision #2, §"REST endpoints remain deployed").

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking doc | Reason |
|------|------------|-------------|--------|
| Integration tests (Task 6) | Post-6.4 | `phase-6.4-websocket-chat-blueprint.md` §Task 5 | Requires Docker (Redis Testcontainer); deferred per `[[test-env-deferral-policy]]` |
| Idempotency via `clientId` (server-side dedup window) | Post-6.4 | ADR-0010 §Deferred | Requires TTL cache for dedup window |
| Typing indicators | Post-6.4 | ADR-0010 §Deferred | New frame type `{ type: "typing" }`, no infrastructure changes needed |
| Read receipts | Post-6.4 | ADR-0010 §Deferred | New frame type `{ type: "read", messageId }`, requires per-user `lastReadId` tracking |
| Connection consolidation | Future | ADR-0010 §Deferred | WS carrying chat + presence + notifications; cross-service routing complexity |

## 3. What was deferred but NOT yet documented

None. All deferrals are documented in the original blueprint and ADR-0010 §Deferred.

## 4. Architectural decisions made during implementation

No new decisions beyond what ADR-0010 already captured. Implementation confirmed the following design choices were sound:

1. **Sealed type + Jackson polymorphic deserialization** (`WebSocketFrame.java`): Java 21 sealed types with `@JsonTypeInfo(property = "type")` provided exhaustive frame dispatch via pattern-matching `instanceof`. The three subtypes (`Send`, `Message`, `Error`) cover all protocol needs without a default branch.

2. **RoomPubSubService as a single component** (not split `WsSessionRegistry` + `RedisMessagePubSub`): Consolidating session registry and Pub/Sub lifecycle into one component simplified the handler — one call to register, one to unregister. The `ConcurrentHashMap.compute()` pattern handles the "first join → create sub, last leave → dispose sub" transition atomically.

3. **ByteBuffer-based Redis publish** (not `ReactiveRedisTemplate.convertAndSend`): Using `ReactiveRedisConnection.pubSubCommands().publish(ByteBuffer, ByteBuffer)` avoids the template's serialization layer and gives direct control over the byte-level channel + message format.

4. **`ChatService.sendMessage()` called unchanged**: The method signature `(Jwt, roomKey, authorSubject, authorUsername, body)` was already transport-agnostic — the WS handler calls it identically to the REST controller. No modification to the authorization or persistence pipeline was needed.

5. **`@ConditionalOnProperty` feature flag**: The `chat.ws.enabled` flag on both `WebSocketConfig` and `ChatWebSocketHandler` means setting `CHAT_WS_ENABLED=false` cleanly disables WebSocket at the bean level — the REST endpoints continue to work, and the publish calls in `ChatService` become no-ops (publish fails silently in `RoomPubSubService` which returns `Mono.just(0L)` on error).

## 5. Documents to update

| Document | Current status | What's stale | Action |
|----------|---------------|-------------|--------|
| `phase-6.4-websocket-chat-blueprint.md` | Status: "proposed" | Blueprint was implemented | Mark "implemented", add commit hashes |
| `IMPLEMENTATION-PLAN.md` | Already shows 6.4 ✅ | Not stale — updated by `cc2dd51` | None needed |
| `adr/chat/0010-websocket-real-time-messaging.md` | Already "accepted" | Not stale — accepted by `cc2dd51` | None needed |
| `adr/chat/0000-architecture-foundation.md` | Already updated | Not stale — integration catalog refreshed by `cc2dd51` | None needed |
| `ARCHITECTURE.md` | Already updated | Not stale — refreshed by `c121b26` | None needed |

## 6. Updated execution order (actual vs planned)

| Planned (blueprint) | Actual commit | Status |
|---------------------|---------------|--------|
| Task 1: Frame protocol + PubSub | `37ab6c8` feat(chat) | ✅ |
| Task 2: Handler + config | `37ab6c8` feat(chat) | ✅ Combined with Task 1 |
| Task 3: Gateway route | `dd896ad` feat(gateway) | ✅ |
| Task 4: Frontend WS service | `fa16ae9` feat(ui) | ✅ Combined with Task 5 |
| Task 5: Wire into ChatPanel | `fa16ae9` feat(ui) | ✅ Combined with Task 4 |
| Task 6: Integration test | — | 🔵 Deferred |

**Actual commit order:** foundation → gateway → frontend → docs (4 commits vs planned 6 tasks).
Backend tasks (1+2) combined because of atomic dependency — handler won't compile without
frame types. Frontend tasks (4+5) combined because component wiring is a thin layer on the service.

## 7. Key risks carried forward

| Risk | Severity | Mitigation reference |
|------|----------|---------------------|
| Gateway timeout killing idle WebSocket | Mitigated | `response-timeout: -1` on `chat-service-ws` route (same pattern as SSE) |
| Redis Pub/Sub message loss on instance restart | Accepted | PG is system of record; reconnect re-sends history |
| chat-service restart drops all sessions | Accepted | Client exponential backoff reconnect; history on fresh connect |
| No integration tests | Accepted | Deferred per `[[test-env-deferral-policy]]`; manual verification via `websocat` |
| Token expiry mid-connection | Accepted | JWT validated at connect time only; reconnect after token refresh |
