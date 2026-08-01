# Phase 6.4 WebSocket Chat Upgrade — Implementation Blueprint

**Date:** 2026-08-01
**Status:** proposed
**Parent:** [IMPLEMENTATION-PLAN.md](../IMPLEMENTATION-PLAN.md) (Phase 6.4)
**ADR:** [ADR-chat-0010](../adr/chat/0010-websocket-real-time-messaging.md)

## Summary

Upgrade the chat receive path from REST polling (3s interval) to WebSocket + Redis Pub/Sub. Sends stay REST POST — idempotency, PBAC, and ban guard work unchanged. The WebSocket handler subscribes to a Redis Pub/Sub channel per room, forwarding messages to all connected sessions across all chat-service instances. The frontend gets a new `ChatWebSocketService` that encapsulates connection lifecycle; `ChatPanelComponent` switches its primary receive path to WebSocket with REST polling as fallback.

## Architecture (reminder)

```
Browser WebSocket ────► /v1/rooms/{roomKey}/ws?access_token=<jwt>
                            │
                      ChatWebSocketHandler
                            │
              onConnect → PBAC check → subscribe Redis Pub/Sub → send history
              onDisconnect → unsubscribe
                            │
                    Redis Pub/Sub: chat:room:{roomKey}:messages
                            │
              ChatService.sendMessage() publishes after persistAndCache()
```

---

## Task 1: Backend — WebSocket Handler + Config

**Estimate**: 4-6 hours
**Files**: 4 new, 2 modified

### Files to Create

| File | What |
|------|------|
| `chat-service/.../api/ws/ChatWebSocketHandler.java` | `ReactiveWebSocketHandler` implementation |
| `chat-service/.../config/WebSocketConfig.java` | Handler mapping + `WebSocketHandlerAdapter` bean |
| `chat-service/.../infrastructure/pubsub/RedisMessagePubSub.java` | Publish + subscribe abstraction over Reactive Redis |
| `chat-service/.../api/ws/WsSessionRegistry.java` | Tracks active sessions per room for cleanup/monitoring |

### Files to Modify

| File | What |
|------|------|
| `chat-service/.../application/ChatService.java` | Add `redisMessagePubSub.publish(roomKey, responseJson)` after `persistAndCache()` |
| `chat-service/.../config/SecurityConfig.java` | Allow `/v1/rooms/**/ws` path in `SecurityWebFilterChain` |
| `chat-service/.../resources/application.yml` | Add `chat.ws.enabled: true` flag |

### ChatWebSocketHandler.java

```java
package com.streaming.chat.api.ws;

import com.streaming.chat.api.dto.MessageResponse;
import com.streaming.chat.application.ChatService;
import com.streaming.chat.infrastructure.pubsub.RedisMessagePubSub;
import com.streaming.chat.security.ChatAuthorization;
import com.streaming.pbac.AuthAction;
import com.streaming.pbac.AuthResourceDomain;
import com.streaming.pbac.AuthResourceKind;
import com.streaming.pbac.RequiredAuthority;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

/**
 * WebSocket handler for real-time chat message delivery.
 *
 * Lifecycle per connection:
 * 1. Extract roomKey from session handshake path
 * 2. Extract and validate JWT from ?access_token= query param
 * 3. PBAC check: VIEW access on the room
 * 4. Subscribe to Redis Pub/Sub channel chat:room:{roomKey}:messages
 * 5. Send last 50 messages as history on the outbound
 * 6. Forward incoming Redis messages to WebSocket outbound
 * 7. On close/error: unsubscribe from Redis, clean up session registry
 */
```

**Key method signatures:**

- `handle(WebSocketSession session)`: Main entry point. Extracts `roomKey` from path segment after `/ws`. Extracts token from `session.getHandshakeInfo().getUri().getQuery()` (parse `access_token=`). Validates JWT via `ReactiveJwtDecoder`. Runs PBAC check. On success, returns a `Mono` that subscribes to Redis Pub/Sub and forwards to `session.send()`.
- JWT extraction: Use `java.net.URI` query parsing — no framework magic. If token is missing or invalid, close the session with close status `4001` (custom "unauthorized").
- History send: On connect, call `chatService.getRecentMessages(jwt, roomKey)` and send each `MessageResponse` as a JSON text frame before the live stream begins.
- Live stream: `redisMessagePubSub.subscribe(roomKey)` returns a `Flux<String>` — map each JSON string to `session.textMessage(json)`, then `session.send(flux)`.
- Cleanup: `session.closeStatus()` → `Mono.doFinally(signalType -> unsubscribe(roomKey, sessionId))`.
- Register/de-register in `WsSessionRegistry` for monitoring (session count per room, graceful shutdown).

### WebSocketConfig.java

```java
package com.streaming.chat.config;

import com.streaming.chat.api.ws.ChatWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

@Configuration
public class WebSocketConfig {

    @Bean
    public HandlerMapping webSocketHandlerMapping(ChatWebSocketHandler handler) {
        var mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/v1/rooms/*/ws", (WebSocketHandler) handler));
        mapping.setOrder(-1); // before annotated controllers
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
```

**Note on path matching**: `SimpleUrlHandlerMapping` with `/v1/rooms/*/ws` matches a single path segment for the room key. Use `"/v1/rooms/{roomKey}/ws"` with a `PathPattern`-based approach if the wildcard proves too greedy in testing. The handler extracts the room key from the URI path segments at runtime.

### RedisMessagePubSub.java

```java
package com.streaming.chat.infrastructure.pubsub;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis Pub/Sub abstraction for per-room message fan-out.
 *
 * publish(): Called by ChatService.sendMessage() after persist + cache.
 * subscribe(): Called by ChatWebSocketHandler on WebSocket connect.
 * unsubscribe(): Called on WebSocket close.
 */

@Component
public class RedisMessagePubSub {

    private final ReactiveRedisMessageListenerContainer container;
    private final ReactiveRedisConnectionFactory connectionFactory;

    // Track active subscriptions to avoid leaking listeners
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Disposable>> subscriptions = new ConcurrentHashMap<>();

    public RedisMessagePubSub(ReactiveRedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        this.container = new ReactiveRedisMessageListenerContainer(connectionFactory);
    }

    /**
     * Publish a message to a room's channel.
     */
    public Mono<Long> publish(String roomKey, String messageJson) {
        var topic = channelFor(roomKey);
        return connectionFactory.getReactiveConnection()
                .pubSubCommands()
                .publish(topic, messageJson);
    }

    /**
     * Subscribe to a room's message stream. Caller must manage the returned Flux lifecycle.
     */
    public Flux<String> subscribe(String roomKey) {
        var topic = new ChannelTopic(ChannelTopic.of(channelFor(roomKey)));
        return container.receive(topic)
                .map(msg -> msg.getMessage());
    }

    private String channelFor(String roomKey) {
        return "chat:room:" + roomKey + ":messages";
    }
}
```

### ChatService.java (modification)

Add `redisMessagePubSub` as a constructor dependency. In `sendMessage()`, after the persist-and-cache step:

```java
return persistAndCache(room, authorSubject, authorUsername, content, now)
        .flatMap(persisted -> {
            var response = MessageResponse.from(persisted);
            return redisMessagePubSub.publish(roomKey, objectMapper.writeValueAsString(response))
                    .thenReturn(response);
        });
```

The publish is fire-and-forget — a publish failure should not block the HTTP response. Wrap in `.onErrorResume()` that logs a warning and returns the response anyway.

### SecurityConfig.java (modification)

Add the WebSocket path to the permitted exchanges:

```java
.authorizeExchange(auth -> auth
        .pathMatchers("/actuator/**").permitAll()
        .pathMatchers("/v1/rooms/**/ws").permitAll()  // JWT extracted in handler
        .anyExchange().authenticated()
)
```

Rationale: The WebSocket handshake path is permitted at the Spring Security level because JWT extraction from query parameters is handled directly in `ChatWebSocketHandler`. Attempting to wire Spring Security's `ServerBearerTokenAuthenticationConverter` to extract from query params for WebSocket upgrades adds complexity without benefit — the handler is the single entry point and validates the token before any data flows.

### application.yml (modification)

```yaml
chat:
  ws:
    enabled: ${CHAT_WS_ENABLED:true}
```

Conditional bean: wrap the `WebSocketConfig` and `ChatWebSocketHandler` with `@ConditionalOnProperty(name = "chat.ws.enabled", havingValue = "true")`.

### Validation

```bash
# Check compilation
./gradlew :chat-service:compileJava

# Check that handler is registered
./gradlew :chat-service:bootRun
# → "Mapped URL path [/v1/rooms/*/ws] onto handler ..." in startup logs

# Manual WebSocket test
# (requires a running instance + valid JWT)
websocat "ws://localhost:8080/api/chat/v1/rooms/test-room/ws?access_token=<jwt>"
# → Should receive history JSON array, then live messages
```

---

## Task 2: Gateway Route Config

**Estimate**: 30 minutes
**Files**: 1 modified

### Files to Modify

| File | What |
|------|------|
| `gateway-service/.../application.yml` | Add `response-timeout: -1` metadata to the chat WebSocket route |

### Change

Add a new route entry **before** the existing `chat-service` route so the WebSocket-specific metadata takes precedence:

```yaml
- id: chat-service-ws
  uri: lb://chat-service
  predicates:
    - Path=/api/chat/v1/rooms/*/ws
  metadata:
    response-timeout: -1
    connect-timeout: 5000
```

The existing `chat-service` route remains unchanged as the catch-all for REST paths. The gateway processes routes in order — the WebSocket-specific route matches first for ws paths.

**Note**: `SimpleUrlHandlerMapping` with `/v1/rooms/*/ws` uses the same wildcard pattern internally. The gateway predicate `Path=/api/chat/v1/rooms/*/ws` matches this. Confirm during implementation that the gateway's `*` segment matches the room key segment correctly (single-segment wildcard, not recursive).

### Validation

```bash
# Verify gateway configuration parses correctly
./gradlew :gateway-service:bootRun
# → No parse errors; route is registered

# Verify idle WebSocket stays open >10s
# Connect via websocat → wait 15s → still connected
```

---

## Task 3: Frontend — WebSocket Service

**Estimate**: 3-4 hours
**Files**: 1 new

### Files to Create

| File | What |
|------|------|
| `streaming-ui/.../core/services/chat-websocket.service.ts` | WebSocket lifecycle + reconnection + Observable stream |

### ChatWebSocketService

```typescript
import { inject, Injectable } from '@angular/core';
import { Observable, Subject, timer, EMPTY } from 'rxjs';
import { catchError, retryWhen, delayWhen, scan, tap } from 'rxjs/operators';
import { ChatMessageResponseDto } from '../contracts/chat-message-response.dto';
import { AuthService } from './auth.service';

/**
 * Manages a WebSocket connection to the chat service for real-time message delivery.
 *
 * Lifecycle:
 * 1. connect(roomKey) — opens WebSocket to /v1/rooms/{roomKey}/ws?access_token=...
 * 2. On open: receives history burst (last 50 messages), then live stream
 * 3. On message: parses JSON, emits ChatMessageResponseDto to the message$ stream
 * 4. On close/error: auto-reconnect with exponential backoff
 * 5. On repeated failure (>5 consecutive retries): emits fallback signal
 * 6. disconnect() — closes the WebSocket, cleans up
 *
 * Each message from the server is a single JSON text frame containing a ChatMessageResponseDto.
 * The history burst is the same format — the server sends each historical message as a separate
 * frame. The consumer sees a flat stream of ChatMessageResponseDto objects.
 */

@Injectable({ providedIn: 'root' })
export class ChatWebSocketService {

  private readonly authService = inject(AuthService);

  private readonly messageSubject = new Subject<ChatMessageResponseDto>();
  private readonly fallbackSubject = new Subject<void>();

  /** Live message stream — history + live messages merged. */
  public readonly message$: Observable<ChatMessageResponseDto> = this.messageSubject.asObservable();

  /** Emits when the WebSocket connection fails repeatedly — consumer should fall back to REST polling. */
  public readonly fallback$: Observable<void> = this.fallbackSubject.asObservable();

  private ws: WebSocket | null = null;
  private reconnectAttempts = 0;
  private currentRoomKey: string | null = null;
  private intentionalClose = false;

  private readonly MAX_BACKOFF_MS = 30_000;
  private readonly BASE_BACKOFF_MS = 1_000;
  private readonly MAX_RETRIES_BEFORE_FALLBACK = 5;

  /**
   * Open a WebSocket connection to a room.
   * Automatically disconnects any previous connection.
   */
  public connect(roomKey: string): void {
    this.disconnect();
    this.intentionalClose = false;
    this.reconnectAttempts = 0;
    this.currentRoomKey = roomKey;
    this.openWebSocket(roomKey);
  }

  /** Close the current connection without auto-reconnect. */
  public disconnect(): void {
    this.intentionalClose = true;
    this.currentRoomKey = null;
    if (this.ws !== null) {
      this.ws.close(1000, 'Client disconnect');
      this.ws = null;
    }
  }

  private openWebSocket(roomKey: string): void {
    const token = this.authService.tokenSync();
    if (token === null) {
      console.warn('[ChatWS] No token available, deferring connect');
      this.scheduleReconnect();
      return;
    }

    // Construct URL — origin derived from current page
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${location.host}/api/chat/v1/rooms/${encodeURIComponent(roomKey)}/ws?access_token=${encodeURIComponent(token)}`;

    const socket = new WebSocket(wsUrl);
    this.ws = socket;

    socket.onopen = () => {
      this.reconnectAttempts = 0;
    };

    socket.onmessage = (event: MessageEvent) => {
      try {
        const msg: ChatMessageResponseDto = JSON.parse(event.data as string);
        this.messageSubject.next(msg);
      } catch {
        // Skip non-JSON frames (e.g., ping/pong if any)
      }
    };

    socket.onclose = (event: CloseEvent) => {
      if (!this.intentionalClose && event.code !== 1000) {
        this.scheduleReconnect();
      }
    };

    socket.onerror = () => {
      // onclose always fires after onerror — reconnect logic lives in onclose
    };
  }

  private scheduleReconnect(): void {
    this.reconnectAttempts++;

    if (this.reconnectAttempts > this.MAX_RETRIES_BEFORE_FALLBACK) {
      this.fallbackSubject.next();
      return;
    }

    const delay = Math.min(
      this.BASE_BACKOFF_MS * Math.pow(2, this.reconnectAttempts - 1),
      this.MAX_BACKOFF_MS,
    ) + Math.random() * 1000; // jitter: 0-1000ms

    setTimeout(() => {
      if (this.currentRoomKey !== null && !this.intentionalClose) {
        this.openWebSocket(this.currentRoomKey);
      }
    }, delay);
  }
}
```

**Key design decisions:**

- **No RxJS WebSocket subject**: The browser `WebSocket` API is used directly because Angular's `webSocket` from `rxjs/webSocket` does not support custom query parameters well and adds an RxJS dependency layer over a simple event-driven API.
- **Token sync**: `AuthService.tokenSync()` returns the current JWT synchronously (from memory/store). If no token is available at connect time, defer with a 1s retry — the auth service may still be initializing.
- **Flat message stream**: History and live messages arrive as the same JSON format on the same subject. The consumer (`ChatPanelComponent`) uses the existing `mergeServerMessages()` which deduplicates by `id` — historical messages that overlap with locally cached messages are simply skipped.
- **No history/live marker**: The server does not send a separator frame between history and live messages. History messages arrive first on connect, live messages follow — but the consumer does not need to distinguish them. `mergeServerMessages()` handles both identically.

### Validation

```bash
# Type check
npx tsc --noEmit

# Build
ng build

# Manual test (browser console, after connecting to a room)
ws = new WebSocket('ws://localhost:8080/api/chat/v1/rooms/test-room/ws?access_token=...')
ws.onmessage = e => console.log(JSON.parse(e.data))
# → Should see history messages, then live messages as they arrive
```

---

## Task 4: Frontend — Wire into ChatPanelComponent

**Estimate**: 2-3 hours
**Files**: 1 modified

### Files to Modify

| File | What |
|------|------|
| `streaming-ui/.../chat-panel/chat-panel.component.ts` | Replace polling with WebSocket; add fallback |

### Changes to ChatPanelComponent

1. **Inject `ChatWebSocketService`**:

```typescript
private readonly wsService = inject(ChatWebSocketService);
```

2. **Replace `startPolling()` with `startWebSocket()`**:

```typescript
private startWebSocket(): void {
  this.wsService.connect(this.roomKey);

  this.wsService.message$
    .pipe(takeUntilDestroyed(this.destroyRef))
    .subscribe((msg) => {
      const wasNearBottom = this.isNearBottom;
      this.mergeServerMessages([msg], wasNearBottom);
    });

  this.wsService.fallback$
    .pipe(takeUntilDestroyed(this.destroyRef))
    .subscribe(() => {
      console.warn('[ChatPanel] WebSocket fallback — switching to REST polling');
      this.startPolling();
    });
}
```

3. **Modify `checkRoomStatus()` success path**: Replace `this.startPolling()` with `this.startWebSocket()`.

4. **Keep `startPolling()` as-is**: It is now the fallback path, activated when `ChatWebSocketService` emits `fallback$`.

5. **Gap-fill on reconnect**: The `ChatWebSocketService` handles reconnect internally (new WebSocket → server sends history again). `mergeServerMessages()` deduplicates by `id` — messages the client already has are filtered out. No additional gap-fill logic needed in the component because the server re-sends history on every fresh connect. The client's existing dedup handles overlap.

6. **Cleanup in `ngOnDestroy`**: Add `this.wsService.disconnect()`.

### What stays the same

- `ChatService` import and usage (still used for `sendMessage()`, `getRoom()`)
- `mergeServerMessages()` dedup logic
- `send()`, `retry()`, optimistic send, `replaceTempMessage()`
- All moderation and scroll logic
- Input handling, emoji picker, @mention autocomplete

### Validation

```bash
ng build  # must pass

# Manual: open chat in browser
# 1. Messages arrive in near-real-time (<500ms)
# 2. Send a message via REST → appears in both sender and another browser tab
# 3. Kill chat-service → client reconnects when service comes back
# 4. Force 6 consecutive reconnect failures → polling fallback activates
```

---

## Task 5: Integration Test

**Estimate**: 2-3 hours
**Files**: 1 new

### Files to Create

| File | What |
|------|------|
| `chat-service/src/test/.../api/ws/ChatWebSocketIntegrationTest.java` | WebSocket integration test with Redis Testcontainer |

### ChatWebSocketIntegrationTest.java

```java
package com.streaming.chat.api.ws;

import com.streaming.chat.api.dto.MessageResponse;
import com.streaming.chat.support.AbstractRedisIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the WebSocket real-time messaging path.
 *
 * Uses an embedded Redis (Testcontainer) for Pub/Sub and a running chat-service
 * instance (via @SpringBootTest with WebFlux on a random port).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatWebSocketIntegrationTest extends AbstractRedisIntegrationTest {

    @LocalServerPort
    private int port;

    private final WebSocketClient client = new ReactorNettyWebSocketClient();

    @Test
    @DisplayName("WebSocket receives a REST-sent message within 500ms")
    void restMessageArrivesViaWebSocket() {
        // Arrange: connect WebSocket to test room
        // Arrange: get a valid JWT for test user
        // Act: POST a message via REST to the same room
        // Assert: WebSocket stream emits the message within 500ms
    }

    @Test
    @DisplayName("Message sent by user A is received by user B via WebSocket")
    void messageFansOutToMultipleConnections() {
        // Arrange: two WebSocket connections to the same room (user A, user B)
        // Act: user A sends a message via REST POST
        // Assert: user B's WebSocket stream receives the message
    }

    @Test
    @DisplayName("Reconnect delivers missed messages via gap-fill")
    void reconnectGapFillsMissedMessages() {
        // Arrange: connect WebSocket, send messages during a disconnection window
        // Act: reconnect WebSocket
        // Assert: history delivered on reconnect includes the messages sent during the gap
    }
}
```

**Test infrastructure notes:**

- `AbstractRedisIntegrationTest` already exists in the test support package and provides `@Testcontainers` + `RedisContainer`. Reuse it.
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` starts the full application context including `WebSocketConfig`, `ChatWebSocketHandler`, and Redis Pub/Sub.
- `ReactorNettyWebSocketClient` is the standard WebFlux WebSocket test client. It connects to `ws://localhost:{port}/api/chat/v1/rooms/{roomKey}/ws?access_token={token}`.
- Test JWT: Use a static HMAC key (configured in test `application.yml`) to mint a valid JWT with `sub=test-user`, `attr.username=TestUser`. The `ChatAuthorization` with `chat.pbac.enabled=false` in the test profile skips PBAC checks.
- `StepVerifier` from `reactor-test` verifies the `Flux<MessageResponse>` stream — `expectNextMatches(...)` within `.verify(Duration.ofMillis(500))`.

### Validation

```bash
./gradlew :chat-service:test --tests "*ChatWebSocketIntegrationTest"
# → All 3 tests pass
```

---

## Risks

| Risk | Severity | Mitigation | Task |
|------|----------|-----------|------|
| WebSocket connection overhead per viewer | Low | Acceptable for streaming platform (viewers stay on one room for minutes-hours). Monitor via `WsSessionRegistry.countPerRoom()`. | Task 1 |
| Redis Pub/Sub message loss on instance restart | Medium | PG is system of record; reconnect gap-fill via history re-send on fresh connect. | Tasks 3, 4 |
| Gateway timeout killing idle WebSocket | High (blocker) | `response-timeout: -1` route metadata, same pattern as SSE routes already proven in production. | Task 2 |
| chat-service restart drops all sessions | Medium | Client auto-reconnects with exponential backoff; server re-sends history on fresh connect. | Tasks 3, 4 |
| `SimpleUrlHandlerMapping` wildcard `*` conflicts with annotated controllers | Medium | `setOrder(-1)` ensures WebSocket mapping takes precedence. Test with a real request to `/v1/rooms/test/ws` to confirm the handler intercepts before `ChatController`. | Task 1 |
| Token refresh mid-connection | Low | The JWT is only validated at connect time. If the token expires during a long session, messages continue to flow — the PBAC check at connect time gates room access. Reconnect after token refresh is handled by `ChatWebSocketService`. | Task 3 |

## Estimated Total Effort

| Task | Estimate |
|------|----------|
| Task 1: Backend handler + config | 4-6 hours |
| Task 2: Gateway route config | 0.5 hours |
| Task 3: Frontend WebSocket service | 3-4 hours |
| Task 4: Wire into ChatPanelComponent | 2-3 hours |
| Task 5: Integration test | 2-3 hours |
| **Total** | **~12-17 hours (2-3 days)** |

## Deferred to Later Phase

- WebSocket for sending messages (keep REST POST for idempotency + PBAC + ban guard)
- Message ACK / sequence numbers (history re-send on connect is sufficient)
- Typing indicators via WebSocket
- Read receipts via WebSocket
- Per-message delivery confirmation to the sender
