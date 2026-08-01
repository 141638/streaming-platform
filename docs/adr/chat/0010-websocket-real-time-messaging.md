# ADR-0010: WebSocket Real-Time Messaging

**Date**: 2026-08-01
**Status**: accepted
**Deciders**: 141638, Claude

## Context

Chat currently uses REST polling every 3 seconds. `ChatPanelComponent.startPolling()` calls `GET /rooms/{roomKey}/messages/recent` on a 3s interval via RxJS `interval(POLL_INTERVAL_MS)`. This design has three consequences:

1. **Latency floor**: Messages from another participant can be up to 3 seconds late, which degrades the live-chat experience during active streams.
2. **Wasteful requests**: A room with 100 viewers fires ~2,000 requests/minute against the chat-service even when the room is quiet. Every poll hits `RedisMessageCache.getRecentMessages()` (Redis ZREVRANGE), and on cache miss falls through to PostgreSQL — amplifying read load for zero new data.
3. **Scale tax**: As viewership grows, the polling cost grows linearly with viewer count, not with message activity.

The streaming platform already has SSE (Server-Sent Events) for notification push and viewer presence. WebSocket is the natural next step for bidirectional chat delivery.

Key constraints:
- Spring Boot 3.3.6, WebFlux (reactive), Java 21
- JWT authentication via Spring Security's `oauth2ResourceServer` with `ReactiveJwtDecoder`
- Redis is already clustered (Pub/Sub supported)
- The existing `ChatService.sendMessage()` pipeline (lookup, active-check, guard, persist, cache) must remain intact — the send path's PBAC enforcement, idempotency, and ban guard must not be weakened
- Frontend is Angular 19+ standalone components with RxJS and signals

## Decision

Upgrade chat to **full WebSocket** — both send and receive over a single persistent connection, with Redis Pub/Sub fan-out across all chat-service instances. The REST endpoints (`POST /rooms/{roomKey}/messages`, `GET .../recent`) are kept as the fallback path: if the WebSocket connection fails after exhausting retries, the client reverts to REST polling for receive and REST POST for sending.

This was refined from an initial hybrid proposal (WS receive-only + REST sends) after analysis showed the send path ports cleanly: the frontend's optimistic-send `clientId` already provides request/response correlation, the backend `ChatService.sendMessage()` pipeline is transport-agnostic, and the `ChatApiError` shapes carry over as JSON error frames. The extra implementation cost is ~70 lines across handler and frontend service — a small premium for a fully symmetric architecture.

## Architecture

```
Browser ◄──────────────► chat-service /v1/rooms/{roomKey}/ws
  │                            │
  │  WebSocket frames          ReactiveWebSocketHandler
  │  { type: "send",            │
  │    clientId,         ┌──────┴──────┐
  │    content }         │  onConnect:  │
  │                      │  - validate JWT (query param)
  │  { type: "message",  │  - PBAC check (VIEW + SEND)
  │    id, author,       │  - subscribe Redis Pub/Sub
  │    content, ... }    │  - send last 50 as history
  │                      │              │
  │  { type: "error",    │  onFrame("send"):
  │    code, message }   │  - deserialize frame
  │                      │  - ChatService.sendMessage()
  │                      │    ├─ room lookup
  │                      │    ├─ active check
  │                      │    ├─ PBAC SEND
  │                      │    ├─ ban guard
  │                      │    ├─ PG insert
  │                      │    ├─ Redis ZADD (cache)
  │                      │    └─ Redis PUBLISH (fan-out)
  │                      │  - write { type:"message" }
  │                      │    response frame to sender
  │                      │              │
  │                      │  onDisconnect:
  │                      │  - unsubscribe Pub/Sub
  │                      └──────┬──────┘
  │                             │
  │                     Redis Pub/Sub
  │                     chat:room:{roomKey}:messages
  │                             │
  │               ┌─────────────┴─────────────┐
  │               │  All chat-service instances│
  │               │  forward to their connected│
  │               │  WebSocket sessions        │
  │               └───────────────────────────┘
  │
  │  Fallback (WS failure):
  │  POST /rooms/{roomKey}/messages  →  REST (unchanged)
  │  GET  /rooms/{roomKey}/messages/recent  →  REST polling (unchanged)
```

### Connect flow

1. Client connects to `ws://.../api/chat/v1/rooms/{roomKey}/ws?access_token=<jwt>`
2. `ChatWebSocketHandler` extracts `roomKey` from the URL path and validates the JWT from the query parameter
3. PBAC check — validates both `VIEW` and `SEND` capabilities on the room, since the WebSocket now handles both directions
4. On success: queries PG (via `getRecentMessages()`) for the last 50 messages, sends them as history frames
5. Calls `roomPubSubService.addSession(roomKey, session)` — if this is the room's first session, a Redis Pub/Sub subscription is created for `chat:room:{roomKey}:messages`. Live messages now flow automatically via Redis fan-out.
6. Session is stored in the `RoomPubSubService` room-keyed registry (see Decision #11) — not user-keyed. Multi-tab: same user, two sessions, both receive messages.

### Send flow (WebSocket)

5. Client sends a JSON frame: `{ "type": "send", "clientId": "client-{ts}-{n}", "content": "hello" }`
6. Handler deserializes the frame, calls `ChatService.sendMessage(roomKey, jwt, content)` — the **exact same method** as the REST controller, with the same PBAC check, ban guard, PG insert, Redis cache write
7. On success: publishes the `MessageResponse` to Redis Pub/Sub `chat:room:{roomKey}:messages`, and writes a response frame `{ "type": "message", "clientId": "...", "id": "...", ... }` back to the sender
8. On error (banned, archived, validation): writes an error frame `{ "type": "error", "clientId": "...", "code": "CHAT_USER_BANNED", "message": "..." }` back to the sender — the same `ChatApiError` shapes used by the REST exception handler
9. Every other connected client receives the message via Redis Pub/Sub as a `{ "type": "message", ... }` frame (without `clientId` — only the sender gets the correlation echo)

### Receive flow (Redis Pub/Sub → WebSocket)

10. `RoomPubSubService`'s Redis subscription on `chat:room:{roomKey}:messages` receives the published message JSON
11. The subscriber iterates all WebSocket sessions in the room's session set (see Decision #10) — O(1) lookup by room key
12. Writes the message as a JSON frame `{ type: "message", id, author, content, createdAt }` to each session's outbound — **including the sender's session** (see Decision #12)
13. The sender's client deduplicates: if the message's server `id` matches a local optimistic-send placeholder, it's a duplicate of the direct response frame and is ignored. The placeholder is replaced by the `clientId`-correlated response frame from step 7.

### Disconnect flow

14. Client closes the WebSocket, browser tab closes, or network drops
15. Spring WebFlux detects the session close — `session.receive()` stream terminates, `doFinally` fires
16. `roomPubSubService.removeSession(roomKey, session)` removes the session from the room's set
17. If this was the room's **last session**: the Redis Pub/Sub subscription is disposed (channel unsubscribed). Room entry removed from the map.
18. If other sessions remain: subscription stays active. Sessions continue receiving messages.
19. Client begins exponential backoff reconnect; on repeated failure (>5 retries), falls back to REST polling + REST POST

The REST endpoints (`ChatController`) remain deployed and functional — they are the fallback path, not dead code.

## Decisions

### 1. Raw WebSocket (WebFlux `ReactiveWebSocketHandler`), not STOMP

STOMP over WebSocket is Servlet-based and an anti-pattern in WebFlux. The Spring WebFlux `ReactiveWebSocketHandler` interface is the idiomatic choice: it provides full control over the handshake, per-session lifecycle, and binary/text frame handling without layering a STOMP broker on top.

### 2. Full WebSocket (send + receive) with REST fallback

Both directions use the WebSocket connection as the primary path. REST endpoints are kept as the fallback — the client reverts to REST polling + REST POST if the WebSocket fails after exhausting retries.

**Why full WebSocket (refined 2026-08-01 from initial hybrid proposal):**

The initial ADR proposed a hybrid (WS receive-only + REST sends) out of caution — we hadn't yet analyzed whether the send path could port cleanly. Analysis confirmed it ports with minimal effort:

- **Request/response correlation**: The frontend's optimistic-send pattern already uses a `clientId` (`client-{ts}-{counter}`). This maps directly to WebSocket frame correlation — the sender includes `clientId` in the send frame, the server echoes it in the response frame.
- **Backend pipeline is transport-agnostic**: `ChatService.sendMessage()` takes `(roomKey, jwt, content)` — it doesn't know or care whether the caller is `ChatController` (HTTP) or `ChatWebSocketHandler` (WebSocket). Same method, same PBAC, same ban guard, same PG + Redis pipeline.
- **Error shapes carry over**: `ChatApiError` (CHAT_USER_BANNED, ROOM_ARCHIVED, validation) already exists as a structured JSON envelope. The WebSocket handler writes the same shapes as error frames — the frontend's error handling code is unchanged.
- **Idempotency**: The `clientId` serves double duty as an idempotency key — if the server sees the same `clientId` within a dedup window (e.g., reconnect replay), it returns the cached response instead of re-persisting.
- **Extra cost**: ~70 lines across handler (incoming frame handler + error mapping) and frontend service (`send()` method). All other WebSocket infrastructure (connection lifecycle, Pub/Sub, session registry) is identical whether the send path is included or not.

The REST endpoints remain deployed — they are the fallback, not dead code. The hybrid approach would have maintained two parallel send paths indefinitely; full WebSocket consolidates on one primary path from the start.

### 3. JWT auth via query parameter

WebSocket handshakes cannot carry custom headers — browsers do not support them in the `WebSocket` API. The standard pattern is `?access_token=` as a query parameter during the WebSocket handshake. Spring Security's WebFlux OAuth2 resource server already supports extracting the JWT from query parameters via `ServerBearerTokenAuthenticationConverter`.

The `SecurityWebFilterChain` in `SecurityConfig` will be updated to allow the `/v1/rooms/**/ws` path, trusting the `ChatWebSocketHandler` to perform its own token extraction and validation from the query string before establishing the session.

### 4. Redis Pub/Sub channel naming

`chat:room:{roomKey}:messages` — one channel per chat room. This is the standard Redis Pub/Sub pattern for per-resource fan-out. The `roomKey` is the same external key used in REST paths (`/v1/rooms/{roomKey}/messages`).

### 5. No ACK/sequence numbers

Redis Pub/Sub is fire-and-forget — there are no message acknowledgments, no consumer groups, no sequence numbers. This is an acceptable trade-off:

- **PostgreSQL is the system of record** — every message is durably stored in PG before being published to Redis
- **On reconnect, the client gap-fills via REST** — the client tracks its oldest local message's `createdAt` cursor and calls `GET /rooms/{roomKey}/messages/recent?before={cursor}&limit=50` to recover any messages it missed during disconnection
- This pattern is simpler than implementing sequence-number tracking at the application layer and matches the "at-most-once delivery over WebSocket, at-least-once delivery via PG" model

### 6. Reconnect strategy

Client-side exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s (cap) with jitter. On repeated failure (>5 consecutive retries), the client emits a fallback signal — the component then falls back to the existing REST polling loop. This prevents a reconnect storm from blackholing message delivery entirely.

### 7. Frontend architecture

A new `ChatWebSocketService` encapsulates the WebSocket connection lifecycle (connect, reconnect, disconnect, message stream, send). It exposes:
- `message$: Observable<MessageResponse>` — live message stream (both received messages and send confirmations)
- `send(content: string): Observable<MessageResponse>` — send a message over WebSocket, returns the correlated server response
- `connectionState$: Observable<'connected' | 'disconnected' | 'fallback'>` — connection health

`ChatService` remains as the REST client for fallback:
- `sendMessage()` (REST POST) — used when WebSocket is in fallback mode
- `getRecentMessages()` — used for reconnect gap-fill and fallback polling
- `getMessagesBefore()` — cursor pagination (unchanged, always REST)

`ChatPanelComponent` uses both:
- **Primary**: `ChatWebSocketService` for send + receive
- **Fallback**: `ChatService` REST methods when `connectionState$` emits `'fallback'`
- **Gap-fill**: On reconnect, calls `ChatService.getMessagesBefore(cursor)` to fill any messages missed during disconnection

The existing `mergeServerMessages()` dedup logic works unchanged — WebSocket-delivered messages have the same `id`/`createdAt` shape as REST-delivered messages. The optimistic-send pattern ports directly: the temporary message is created with a `clientId`, the WebSocket response carries the same `clientId`, and the placeholder is replaced with the confirmed server message on match.

### 8. Gateway route configuration

The WebSocket route shares the existing chat-service gateway route (`lb://chat-service`, predicate `Path=/api/chat/**`). WebSocket upgrade requests pass through Spring Cloud Gateway automatically — the gateway does not terminate WebSocket connections by default. However, the global `response-timeout: 10s` would kill idle WebSocket connections after 10 seconds. Add a route-specific `response-timeout: -1` metadata entry for the chat WebSocket path, same as the existing SSE routes for stream-service and notification-service.

### 9. No sticky sessions

Redis Pub/Sub fans messages to all chat-service instances. Each instance forwards to its own connected WebSocket sessions. This means the client can reconnect to any instance and receive messages — no session affinity required.

### 10. Lazy Redis Pub/Sub subscription via `RoomPubSubService`

Redis Pub/Sub channels don't need to exist before a subscriber connects — publishing to a channel with zero subscribers is a silent no-op. This means we don't need to pre-create subscriptions at room-creation time (unlike Kafka, where topics must exist before consumption). Subscriptions are created **lazily, on first WebSocket join**, and torn down when the last session leaves.

The `RoomPubSubService` manages this lifecycle:

```java
// Per room: the set of WS sessions + the Redis subscription handle
ConcurrentHashMap<String, RoomSub> rooms;

record RoomSub(Set<WebSocketSession> sessions, Disposable redisSub) {}

// Called on WS connect. If first session → subscribe Redis.
void addSession(String roomKey, WebSocketSession session) {
    rooms.compute(roomKey, (key, existing) -> {
        if (existing != null) {
            existing.sessions().add(session);
            return existing;  // reuse existing subscription
        }
        // First session: create Redis subscription
        Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
        sessions.add(session);
        Disposable sub = redisContainer
            .receiveChannel("chat:room:" + roomKey + ":messages")
            .doOnNext(msg -> sessions.forEach(s -> s.send(encodeFrame(msg))))
            .subscribe();
        return new RoomSub(sessions, sub);
    });
}

// Called on WS disconnect. If last session → unsubscribe Redis.
void removeSession(String roomKey, WebSocketSession session) {
    rooms.computeIfPresent(roomKey, (key, existing) -> {
        existing.sessions().remove(session);
        if (existing.sessions().isEmpty()) {
            existing.redisSub().dispose();
            return null;  // remove room entry
        }
        return existing;
    });
}
```

Benefits:
- **Zero waste**: No Redis subscriptions for empty rooms
- **Self-cleaning**: When a room's last viewer leaves, the subscription is disposed
- **No Kafka dependency**: Room lifecycle events (STREAM_CREATED, STREAM_ENDED) don't need to manage Redis subscriptions — the chat-service handles it internally
- **Instance restart**: Restarting chat-service drops all subscriptions; they're recreated as clients reconnect

### 11. Room-keyed session registry (not user-keyed)

The session registry is keyed by room, not by user. This differs from the notification-service SSE registry (`SseConnectionRegistry`), which is keyed by `userId` because notifications push to **one specific user**.

For chat, fan-out goes to **everyone in the room**:

| Registry | Key | Use case |
|----------|-----|----------|
| `SseConnectionRegistry` (notification) | `userId` | Push notification to one user, possibly across multiple tabs |
| `RoomPubSubService` (chat) | `roomKey` | Push message to all sessions in the room |

Why not a composite key `chat.{roomKey}.{userId}`?
- **Multi-tab**: Same user in two browser tabs → two separate WS sessions, both must receive messages
- **Fan-out is O(1)**: Room-keyed means `sessions.get(roomKey)` returns all sessions immediately — no prefix scan needed
- **Sender receives too**: The sender's own session is in the room set; they receive messages via Pub/Sub like everyone else, deduplicating by server `id`

### 12. Sender deduplication strategy

When a message is published to Redis Pub/Sub, every instance receives it — **including the instance that published it**. The sender gets the message twice:

1. **Direct response frame** (from the handler, with `clientId`) — confirms the send
2. **Pub/Sub fan-out frame** (from Redis, without `clientId`) — same message as everyone else

The client deduplicates by server `id` using the existing `mergeServerMessages()` logic — the same dedup that handles the current polling response. The `clientId`-correlated response frame replaces the optimistic placeholder; the Pub/Sub frame (same server `id`) is recognized as a duplicate and ignored.

**Why not skip the sender server-side?** Filtering by author would require tracking `session → userId` mapping. It also wouldn't handle the multi-tab case: a user with two tabs open needs the second tab to receive messages they sent from the first tab. Client-side dedup is simpler and already battle-tested from the polling era.

### 13. Room key extraction from URL path

The `roomKey` is extracted from the WebSocket URL path — no need to include it in the frame payload:

```
ws://.../api/chat/v1/rooms/{roomKey}/ws?access_token=...
                           ^^^^^^^^
                   Handler parses this once at connect
```

The handler stores `roomKey` in the session attribute map. All subsequent operations (frame handling, Pub/Sub, session registry) use this stored value:
- **Send frame**: `{ clientId, content }` — no room key in payload (handler already knows it)
- **Publish**: `pubSub.publish(roomKey, messageJson)` — `roomKey` is already a parameter in `ChatService.sendMessage()`
- **Fan-out on receive**: The Redis channel name IS the room key — `chat:room:{roomKey}:messages`. The subscriber extracts it from `message.getChannel()`.

### 14. Session cleanup on disconnect

WebSocket sessions clean themselves up automatically. Spring WebFlux's `WebSocketSession.handle()` returns a `Mono<Void>` that completes when the session ends (close, browser refresh, network drop, crash):

```java
session.receive()                    // inbound frames
    .doFinally(signal -> {           // fires on ANY terminal event
        roomPubSubService.removeSession(roomKey, session);
    })
    .subscribe();
```

This covers all exit paths: clean close, page refresh, tab close, network loss, service restart. No orphaned sessions accumulate. No heartbeat or TTL-based cleanup needed — the TCP socket close propagates to the reactive stream's terminal signal.

### 15. History on connect for newly joined users

When a user connects via WebSocket, they miss all messages sent before their connection. The handler fills this gap:

1. On successful connect + PBAC check, call `ChatService.getRecentMessages(roomKey, 50)`
2. Send each historical message as a `{ type: "history", messages: [...] }` frame (or individual `{ type: "message" }` frames with a `history: true` flag)
3. After the history batch, live messages flow via Redis Pub/Sub automatically — the session was subscribed to the channel in step 1

The historical query uses the existing `RedisMessageCache.getRecent()` → cold-path fallback to PG — same as the current REST polling endpoint. No new query path needed.

## Alternatives Considered

### Alternative 1: Server-Sent Events (SSE) + REST sends

This was the most seriously considered alternative. SSE is a natural fit for a receive-only channel: unidirectional, lighter than WebSocket (plain HTTP, no upgrade, no framing overhead), and the browser `EventSource` API provides built-in auto-reconnect with `Last-Event-ID` — the server reads that header on reconnect and gap-fills missed messages without any custom protocol.

The project already has proven SSE infrastructure in notification-service (`SseConnectionRegistry`, `Flux<ServerSentEvent>`, gateway `response-timeout: -1` metadata), so the implementation path is well-understood.

**Discussion (2026-08-01)**: SSE is arguably the better choice for the immediate requirement — keep sends as REST POST, receive chat messages via SSE push, same architecture as notifications. Less code, fewer new patterns, built-in reconnect with gap-fill.

**Why SSE was ultimately rejected**: The platform is a live streaming application, and chat is expected to evolve well beyond simple message push:
- **Typing indicators** — requires client→server frames (bidirectional)
- **Read receipts** — requires server→client with per-user delivery confirmation
- **Potentially moving the send path onto the same connection** — the optimistic-send pattern already uses a client-correlation `clientId`, which ports naturally to WebSocket request/response correlation without rebuilding the entire send pipeline
- **Connection consolidation** — one WebSocket could eventually carry chat + presence + notifications, reducing connection count

SSE now would likely mean migrating to WebSocket within 6-12 months. The cost of building WebSocket now versus migrating later favored starting with WebSocket.

**Note on HTTP/2 connection limits**: HTTP/1.1 browsers cap at 6 connections per domain. A viewer with notification SSE (1), chat SSE (1), and presence SSE (1) uses 3 of 6 slots — acceptable but accounts for the remaining headroom. Under HTTP/2 this limit vanishes.

### Alternative 2: STOMP over WebSocket

[...to end of file stays the same...]

- **Pros**: Higher-level abstraction — built-in destination-based routing, user-level subscriptions (`/user/queue/...`), broker relay support, Spring's `@MessageMapping`/`@SendTo` annotation model.
- **Cons**: Servlet-based, not native to WebFlux. Requires `spring-messaging` + STOMP broker configuration that pulls in servlet dependencies. The chat-service is already pure WebFlux — introducing a servlet-based messaging layer would be an architectural regression.
- **Why not**: Raw WebFlux `ReactiveWebSocketHandler` achieves the same goals with less framework overhead and stays within the reactive stack.

### Alternative 3: Full WebSocket (both send and receive) — ✅ ACCEPTED

This was the initial hybrid ADR's "rejected" alternative, upgraded to the chosen approach after analysis (2026-08-01).

- **Pros**: Single connection for everything — no REST calls for sending, lower latency for sends, unified error handling, symmetric model (send and receive flow through the same channel).
- **Initial concerns addressed**:
  - *Idempotency*: The frontend's optimistic-send `clientId` doubles as an idempotency key — the server deduplicates by `clientId` within a TTL window on reconnect replay.
  - *PBAC enforcement*: `ChatService.sendMessage()` is called unchanged from the WebSocket handler — same method, same PBAC check, same ban guard.
  - *Request/response correlation*: Already exists via `clientId` in the optimistic-send pattern — the server echoes `clientId` in the response frame, the frontend matches on it.
  - *Test coverage*: The REST endpoints remain as the fallback — existing tests are not invalidated. WebSocket integration tests cover the new primary path.
- **Extra cost**: ~70 lines across handler + frontend service. REST endpoints are kept as fallback, not deleted.

### Alternative 4: Kafka as the message bus (instead of Redis Pub/Sub)

- **Pros**: At-least-once delivery, consumer groups, message persistence on disk, replay capability.
- **Cons**: Higher latency (Kafka produce + consume is 5-50ms vs Redis Pub/Sub's <1ms), more operational complexity, overkill for in-memory fan-out to WebSocket sessions.
- **Why not**: Redis Pub/Sub is already in the stack and is purpose-built for this use case (low-latency, fire-and-forget fan-out). PG is the durable store; Redis Pub/Sub is the delivery channel. Kafka is the right tool for cross-service events (outbox pattern), not for per-message fan-out to connected WebSocket clients.

## Consequences

### Positive

- **Near-instant message delivery**: Messages arrive in <100ms (Redis Pub/Sub latency + JSON serialization), versus up to 3s with polling.
- **Eliminates polling waste**: A quiet room with 100 viewers generates zero requests instead of ~2,000/minute.
- **Scales with Redis Pub/Sub**: Adding chat-service instances automatically fans out messages — no extra infrastructure.
- **Compatible with existing code**: `ChatService.sendMessage()` is called unchanged from the WebSocket handler — no modification to the PG + Redis pipeline. The REST endpoints remain as fallback. `mergeServerMessages()` dedup logic works identically with WebSocket-delivered messages.

### Negative

- **WebSocket connection management**: Each connected viewer holds a TCP socket open for the duration of their session. At 1,000 concurrent viewers, that is 1,000 open WebSocket connections across the chat-service cluster — acceptable for a streaming platform but requires monitoring connection counts.
- **Redis Pub/Sub message loss on instance restart**: If a chat-service instance restarts, all its connected WebSocket sessions drop and any Redis Pub/Sub messages published during the restart window are lost to those sessions. **Mitigated** by client reconnect with PG gap-fill via REST cursor.
- **chat-service becomes stateful**: The service now holds per-instance WebSocket session state, breaking the pure stateless model of the REST-only design. This is an acceptable trade-off — the state is soft (session references) and lost on restart, with no data durability implications.

### Risks

| Risk | Severity | Mitigation |
|------|----------|-----------|
| Redis Pub/Sub message loss on instance restart | Medium | PG is the system of record; client gap-fills via REST cursor on reconnect |
| Gateway timeout killing idle WebSocket connections | High (blocker) | `response-timeout: -1` route metadata, same pattern as SSE routes already proven in production |
| WebSocket connection overhead at scale | Low | Acceptable for a streaming platform — viewers stay in one room for minutes to hours, not rapid page-hopping |
| chat-service restart drops all sessions | Medium | Client auto-reconnects with exponential backoff; gap-fill on reconnect delivers any missed messages |
| Duplicate message on reconnect replay | Low | `clientId` serves as idempotency key; server deduplicates within a short TTL window (60s), returning cached response for replay |

## Deferred

- **Typing indicators and read receipts**: The bidirectional WebSocket connection already supports these — adding a new frame type (`{ type: "typing" }`, `{ type: "read", messageId }`) requires no infrastructure changes, only a new handler case and frontend UI. Planned post-6.4.
- **Message ACK/sequence numbers**: Gap-fill via REST cursor is sufficient for this phase. Introduce sequence numbers only if PG gap-fill latency becomes a problem (e.g., very large rooms with 100+ messages/minute where the gap window is large).
- **Connection consolidation (chat + presence + notifications over one WS)**: Possible future optimization — one WebSocket carrying all real-time streams instead of WS (chat) + SSE (presence) + SSE (notifications). Requires cross-service routing decisions (each stream is owned by a different service).
