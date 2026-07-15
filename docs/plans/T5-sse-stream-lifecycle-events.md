# T5: SSE Stream Lifecycle Events

**Status**: Ready
**Depends on**: None (infrastructure task; unblocks T4, T6)
**Scope**: Backend (stream-service) + Frontend (Angular) — 6 files

## Problem

No real-time events for stream lifecycle state changes:

1. **Streamer**: When OBS connects and `on_publish` fires (DRAFT→LIVE), the streamer's UI stays in "draft/preparing" mode. They must manually refresh to see the LIVE status.
2. **Viewers**: When a stream ends (LIVE→ENDED via `on_unpublish`), viewers see an infinite loading spinner on the HLS player. They must manually refresh to see the ended state.
3. **Browse page**: No live updates — new streams or ended streams don't appear/disappear in real-time.

## Design

Follow the exact same pattern as `NotificationSseController` from notification-service:

```
notification-service: NotificationSseController → SseConnectionRegistry → Flux<ServerSentEvent>
stream-service:     StreamSseController        → SseConnectionRegistry → Flux<ServerSentEvent>
```

### Event Types

| Event | Trigger | Recipients | Payload |
|-------|---------|-----------|---------|
| `stream:started` | `handlePublish()` DRAFT→LIVE | Broadcaster (streamer) | `{ streamId, status: "LIVE" }` |
| `stream:ended` | `handleUnpublish()` LIVE→ENDED | All viewers of that stream | `{ streamId, status: "ENDED" }` |
| `stream:viewers` | Periodic (every 30s) | Broadcaster + viewers | `{ streamId, viewerCount: N }` |

### API Endpoint

```
GET /v1/streams/events
  - optional query param: streamId (filter to specific stream)
  - JWT authenticated
  - Returns: text/event-stream

Event frames:
  event: stream:started
  data: {"streamId":"...","status":"LIVE"}

  event: stream:ended
  data: {"streamId":"...","status":"ENDED"}

  event: stream:viewers
  data: {"streamId":"...","viewerCount":42}

  : heartbeat   (every 30s)
```

### Backend Architecture

#### 1. `StreamSseController.java` (new)

Follow `NotificationSseController` exactly:

```java
@RestController
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class StreamSseController {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    private final SseConnectionRegistry registry;

    @GetMapping(path = "/streams/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamSseEvent>> stream(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID streamId) {

        String subject = jwt.getSubject();
        // Register for all events (streamer mode) or a specific stream (viewer mode)
        Flux<ServerSentEvent<StreamSseEvent>> events = registry.register(subject, streamId)
                .map(event -> ServerSentEvent.<StreamSseEvent>builder()
                        .event(event.type())
                        .data(event)
                        .build());

        Flux<ServerSentEvent<StreamSseEvent>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<StreamSseEvent>builder()
                        .comment("heartbeat")
                        .build());

        return Flux.merge(events, heartbeat)
                .doOnSubscribe(s -> log.info("SSE stream started: subject={} streamId={}", subject, streamId))
                .doOnCancel(() -> log.info("SSE stream cancelled: subject={}", subject));
    }
}
```

#### 2. `SseConnectionRegistry.java` (new, in stream-service)

Copy the pattern from `notification-service:.../SseConnectionRegistry.java` but keyed differently:

```java
@Component
public class SseConnectionRegistry {
    // Key: JWT subject (user)
    // Value: set of active sinks (multiple browser tabs)
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<Sinks.Many<StreamSseEvent>>> connections
            = new ConcurrentHashMap<>();

    /**
     * Register a new SSE connection.
     * @param subject JWT subject of the connected user
     * @param streamId optional — if present, only events for this stream are delivered
     */
    public Flux<StreamSseEvent> register(String subject, UUID streamId) {
        Sinks.Many<StreamSseEvent> sink = Sinks.many().multicast()
                .onBackpressureBuffer(64);
        connections.computeIfAbsent(subject, k -> new CopyOnWriteArraySet<>()).add(sink);

        Flux<StreamSseEvent> flux = sink.asFlux();
        if (streamId != null) {
            flux = flux.filter(event -> streamId.equals(event.streamId()));
        }
        return flux.doFinally(signalType -> {
            Set<Sinks.Many<StreamSseEvent>> sinks = connections.get(subject);
            if (sinks != null) {
                sinks.remove(sink);
                if (sinks.isEmpty()) {
                    connections.remove(subject);
                }
            }
        });
    }

    /** Push an event to all connections for a given user. */
    public void push(String subject, StreamSseEvent event) {
        Set<Sinks.Many<StreamSseEvent>> sinks = connections.get(subject);
        if (sinks != null) {
            sinks.forEach(sink -> {
                Sinks.EmitResult result = sink.tryEmitNext(event);
                if (result.isFailure()) {
                    // sink is dead — will be cleaned up on next register
                }
            });
        }
    }
}
```

#### 3. `StreamSseEvent.java` (new record)

```java
public record StreamSseEvent(
        String type,        // "stream:started", "stream:ended", "stream:viewers"
        UUID streamId,
        String status,      // "LIVE", "ENDED"
        Long viewerCount    // non-null for stream:viewers
) {}
```

#### 4. `StreamService.java` — Emit events on state transitions

In `handlePublish()`, after successful DRAFT→LIVE transition:
```java
// After entity.goLive() + repository.save() + outbox write:
registry.push(entity.getBroadcasterSubject(),
    new StreamSseEvent("stream:started", entity.getId(), "LIVE", null));
```

In `handleUnpublish()`, after successful LIVE→ENDED transition:
```java
// Need to push to all viewers, not just the broadcaster.
// This requires tracking which users are connected for a specific stream.
// Alternative: push to broadcaster, then viewers subscribe to broadcaster's events.
```

**Viewer notification challenge**: The `SseConnectionRegistry` is keyed by user subject, not by stream. When a stream ends, we need to notify all viewers of that stream. Solutions:

1. **Track stream→viewers mapping**: Add a second map `ConcurrentHashMap<UUID, Set<String>>` (streamId → set of viewer subjects). Update on SSE connect/disconnect. Push to all viewer subjects when stream ends.
2. **Viewers subscribe directly**: The viewer connects with `streamId` filter param. The registry internally tracks which streams each user is watching.
3. **Poll for now**: Viewers don't get real-time end events via SSE — just poll `GET /v1/streams/{id}/watch` every 15s. When `isLive` becomes false, transition UI.

**Decision**: Use approach #1 (stream→viewers mapping) for viewer notifications. It's the simplest:

```java
// In SseConnectionRegistry:
private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<String>> streamViewers
        = new ConcurrentHashMap<>();

public void registerViewer(UUID streamId, String subject) {
    streamViewers.computeIfAbsent(streamId, k -> new CopyOnWriteArraySet<>()).add(subject);
}

public void pushToStreamViewers(UUID streamId, StreamSseEvent event) {
    Set<String> viewers = streamViewers.get(streamId);
    if (viewers != null) {
        viewers.forEach(subject -> push(subject, event));
    }
}
```

Cleanup on disconnect:
```java
// In register()'s doFinally:
if (streamId != null) {
    Set<String> viewers = streamViewers.get(streamId);
    if (viewers != null) {
        viewers.remove(subject);
        if (viewers.isEmpty()) {
            streamViewers.remove(streamId);
        }
    }
}
```

#### 5. `StreamService.java` — Emit on end

```java
// In handleUnpublish(), after entity.end() + save:
registry.pushToStreamViewers(entity.getId(),
    new StreamSseEvent("stream:ended", entity.getId(), "ENDED", null));
```

#### 6. Security — Whitelist in `SecurityConfig`

Add `/v1/streams/events` to the permitted authenticated paths (it requires JWT but no specific PBAC permission beyond being logged in).

### Frontend Changes

#### 7. New Angular service: `stream-sse.service.ts`

Follow `notification.service.ts` pattern:

```typescript
@Injectable({ providedIn: 'root' })
export class StreamSseService {
  private readonly authService = inject(AuthService);
  private readonly basePath = '/api/streams/v1';
  private sseAbortController: AbortController | null = null;

  // Event emitters
  private readonly streamStartedSubject = new Subject<StreamSseEventDto>();
  readonly streamStarted$ = this.streamStartedSubject.asObservable();

  private readonly streamEndedSubject = new Subject<StreamSseEventDto>();
  readonly streamEnded$ = this.streamEndedSubject.asObservable();

  connect(streamId?: string): void {
    this.disconnect();
    const token = this.authService.accessToken();
    if (!token) return;

    this.sseAbortController = new AbortController();
    const url = streamId
      ? `${this.basePath}/streams/events?streamId=${streamId}`
      : `${this.basePath}/streams/events`;

    fetchEventSource(url, {
      headers: { Authorization: `Bearer ${token}` },
      signal: this.sseAbortController.signal,
      onmessage: (msg) => {
        if (msg.event === 'stream:started') {
          this.streamStartedSubject.next(JSON.parse(msg.data));
        } else if (msg.event === 'stream:ended') {
          this.streamEndedSubject.next(JSON.parse(msg.data));
        }
      },
      onerror: (err) => {
        if (err instanceof Error && err.message.includes('401')) throw err;
        // transient — retry with backoff
      },
    });
  }

  disconnect(): void {
    this.sseAbortController?.abort();
    this.sseAbortController = null;
  }
}
```

#### 8. `stream-detail.page.ts` — Subscribe to SSE

```typescript
private readonly streamSseService = inject(StreamSseService);

ngOnInit(): void {
  // ... existing init ...
  this.streamSseService.connect(); // no streamId filter — listen for own streams
  this.streamSseService.streamStarted$
    .pipe(takeUntilDestroyed(this.destroyRef))
    .subscribe(event => {
      if (event.streamId === this.id()) {
        // Stream went LIVE — refresh data
        this.loadStream();
      }
    });
}
```

#### 9. `watch.page.ts` — Subscribe to SSE

```typescript
ngOnInit(): void {
  const id = this.route.snapshot.paramMap.get('id');
  // ... existing init ...

  // Subscribe to SSE for this stream
  this.streamSseService.connect(id!);
  this.streamSseService.streamEnded$
    .pipe(takeUntilDestroyed(this.destroyRef))
    .subscribe(event => {
      if (event.streamId === id) {
        // Stream ended — update UI without page reload
        this.data.update(d => d ? { ...d, isLive: false } : null);
        this.destroyHls();
      }
    });
}
```

#### 10. `StreamStageComponent` — Watch SSE in dashboard

Optionally subscribe in the dashboard's stream stage so the streamer sees live status changes there too. But the stream detail page (`channel/:id`) is the primary streamer interface.

## Files to Change (Complete List)

| # | File | Action |
|---|------|--------|
| 1 | `StreamSseController.java` | **Create** — SSE endpoint |
| 2 | `SseConnectionRegistry.java` | **Create** — connection tracking (in stream-service) |
| 3 | `StreamSseEvent.java` | **Create** — event record |
| 4 | `StreamService.java` | Edit — emit events on publish/unpublish |
| 5 | `SecurityConfig.java` (stream) | Edit — permit `/v1/streams/events` |
| 6 | `stream-sse.service.ts` | **Create** — Angular SSE client |

## Verification

1. Open stream detail page as streamer → connect to SSE
2. Start OBS → `on_publish` fires → stream detail shows LIVE without manual refresh
3. Open watch page as viewer → connect to SSE with streamId filter
4. Stop OBS → `on_unpublish` fires → watch page shows "Stream ended" overlay without refresh
5. Check browser DevTools Network tab → see the SSE event stream with `text/event-stream` content type

## Agent Execution

Two sub-tasks:
1. **Backend SSE infra**: Spawn agent for StreamSseController, SseConnectionRegistry, StreamSseEvent, StreamService changes, SecurityConfig
2. **Frontend SSE client**: Spawn agent for stream-sse.service.ts, stream-detail.page.ts, watch.page.ts
