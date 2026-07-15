# T4: Ended Stream Watch Page + Configurable Chat Auto-Archive

**Status**: Ready
**Depends on**: T5 (SSE events) for live status updates on watch page
**Scope**: DB migration + Backend (stream-service, chat-service) + Frontend (Angular) — 12 files

## Problem

When a stream ends, the watch page (`/watch/:id`) becomes completely broken:
- `getWatchData()` throws `StreamNotLiveException` (409) — "This stream is not currently live"
- On reload, the viewer is kicked out with an error
- Chat is immediately archived via Kafka `STREAM_ENDED` event (no delay config)

## What Should Happen

1. **Stream is LIVE** → watch page shows HLS player + live chat (current behavior)
2. **Stream just ended (chat still active)** → HLS player stops, shows poster/thumbnail + "Stream has ended" message. Chat panel remains fully functional.
3. **Stream ended, chat archived** → Shows thumbnail + "This stream has ended and chat has been archived." Chat panel shows read-only history.
4. **Stream ended, chat not archived (reload)** → Same as #2 — chat still works, video shows poster with message.
5. **Streamer can configure**: "Auto-archive chat when stream ends" toggle + delay (minutes) in stream settings.
6. **Auto-archive timing**: On stream end, if `auto_archive_chat=true`, schedule chat archival after `chat_archive_delay_minutes` (default 30). Streamer can also manually archive immediately.

## Design

### Database Changes

New Flyway migration `V14__add_chat_archive_config.sql`:

```sql
-- Add chat archive configuration to stream sessions
ALTER TABLE stream.stream_session
    ADD COLUMN IF NOT EXISTS auto_archive_chat BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS chat_archive_delay_minutes INTEGER NOT NULL DEFAULT 30,
    ADD COLUMN IF NOT EXISTS chat_archived_at TIMESTAMPTZ;

COMMENT ON COLUMN stream.stream_session.auto_archive_chat IS
    'When true, chat room is automatically archived after stream ends (with configurable delay)';
COMMENT ON COLUMN stream.stream_session.chat_archive_delay_minutes IS
    'Delay in minutes before auto-archiving chat after stream end. Default 30.';
COMMENT ON COLUMN stream.stream_session.chat_archived_at IS
    'Timestamp when the chat room was archived; NULL if chat is still active';
```

### Backend Changes

#### 1. `StreamSessionEntity.java` — Add fields

```java
@Column("auto_archive_chat")
private Boolean autoArchiveChat;

@Column("chat_archive_delay_minutes")
private Integer chatArchiveDelayMinutes;

@Column("chat_archived_at")
private OffsetDateTime chatArchivedAt;
```

Add getters/setters (Lombok `@Getter @Setter` already on the class).

#### 2. `StreamService.java` — Modify `getWatchData()`

Instead of rejecting non-LIVE streams with 409:

```java
public Mono<WatchResponse> getWatchData(UUID id) {
    return repository.findById(id)
            .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
            .map(entity -> {
                StreamStatus status = entity.getStatus();
                String playUrl = null;
                boolean isLive = status == StreamStatus.LIVE;

                if (isLive && entity.getSrsName() != null) {
                    playUrl = String.format("%s/live/%s.m3u8",
                            publishTokenProps.srsHlsHost(), entity.getSrsName());
                }

                boolean isChatArchived = entity.getChatArchivedAt() != null;
                // Chat is available if the room exists (externalKey = streamId)
                // and hasn't been archived yet
                String roomKey = id.toString(); // chat room external key = stream ID

                return new WatchResponse(
                        playUrl,           // null when not live
                        roomKey,
                        StreamSummaryResponse.from(entity),
                        isLive,
                        isChatArchived,
                        entity.getThumbnailUrl()
                );
            });
}
```

#### 3. `WatchResponse.java` — Add fields

```java
public record WatchResponse(
        String playUrl,           // null when not live
        String roomKey,
        StreamSummaryResponse stream,
        boolean isLive,           // NEW
        boolean isChatArchived,   // NEW
        String thumbnailUrl       // NEW (already in StreamSummaryResponse but explicit)
) {}
```

#### 4. `UpdateStreamRequest.java` — Add chat archive fields

```java
public record UpdateStreamRequest(
        // ... existing fields ...
        Boolean autoArchiveChat,         // NEW
        Integer chatArchiveDelayMinutes  // NEW
) {}
```

#### 5. `StreamService.java` — Modify `applyMetadataUpdates()`

```java
if (request.autoArchiveChat() != null) {
    entity.setAutoArchiveChat(request.autoArchiveChat());
}
if (request.chatArchiveDelayMinutes() != null) {
    if (request.chatArchiveDelayMinutes() < 0 || request.chatArchiveDelayMinutes() > 10080) {
        return Mono.error(new IllegalArgumentException(
                "chatArchiveDelayMinutes must be between 0 and 10080 (7 days)"));
    }
    entity.setChatArchiveDelayMinutes(request.chatArchiveDelayMinutes());
}
```

#### 6. `StreamService.java` — Modify `handleUnpublish()`

After `entity.end()`:

```java
// Check if auto-archive is configured
if (Boolean.TRUE.equals(entity.getAutoArchiveChat())) {
    int delayMinutes = entity.getChatArchiveDelayMinutes() != null
            ? entity.getChatArchiveDelayMinutes() : 30;
    if (delayMinutes == 0) {
        // Archive immediately
        entity.setChatArchivedAt(OffsetDateTime.now(ZoneOffset.UTC));
    }
    // If delay > 0, a scheduled task handles it (see #7)
}
```

#### 7. New scheduled task: `ChatArchiveScheduler.java`

A `@Scheduled` task that periodically checks for ended streams where:
- `status = ENDED`
- `auto_archive_chat = true`
- `chat_archived_at IS NULL`
- `ended_at + chat_archive_delay_minutes < now()`

And sets `chat_archived_at = now()` for those streams, then publishes a `STREAM_CHAT_ARCHIVE` event to Kafka so chat-service archives the room.

#### 8. `StreamController.java` — No new endpoints needed

The `PATCH /v1/streams/{id}` and `POST /v1/streams/{id}/end` already exist. Chat archive config is updated via `PATCH`.

### Chat-Service Changes (Kafka Consumer)

#### 9. `StreamControlListener.java` — Handle delayed archive

Currently `STREAM_ENDED` immediately archives. Change to:
- On `STREAM_ENDED`: check if `auto_archive_chat` is true; if false, do nothing (keep room ACTIVE); if true with delay=0, archive immediately.
- New event `STREAM_CHAT_ARCHIVE`: emitted by the scheduled task when delay elapses → archive the room.

This requires the `StreamEvent` to carry `autoArchiveChat` and `chatArchiveDelayMinutes` fields — or the chat-service queries stream-service for the config.

**Simpler approach**: The chat-service checks the stream's auto-archive config when processing `STREAM_ENDED`. If `auto_archive_chat=false`, skip archiving — leave the room ACTIVE. The scheduled task in stream-service will emit a separate event when the delay elapses.

But chat-service can't directly query stream-service's DB. **Alternative**: Add an API endpoint on stream-service that chat-service calls:
```
GET /v1/streams/{id}/chat-archive-config  → { autoArchiveChat, chatArchiveDelayMinutes }
```

Or pass the config in the `STREAM_ENDED` Kafka event payload.

**Final decision**: Extend `StreamEvent` to include `autoArchiveChat` and `chatArchiveDelayMinutes`. The `StreamEvent.ended()` factory already takes `streamId` and `broadcasterSubject`. Add optional fields for the archive config.

### Frontend Changes

#### 10. `WatchResponseDto` — Update interface

```typescript
export interface WatchResponseDto {
  readonly playUrl: string | null;     // null when not live
  readonly roomKey: string;
  readonly stream: StreamSummaryResponseDto;
  readonly isLive: boolean;            // NEW
  readonly isChatArchived: boolean;    // NEW
  readonly thumbnailUrl: string | null; // NEW
}
```

#### 11. `watch.page.ts` — Handle ended states

Restructure to handle three states:
- **LIVE**: current behavior (HLS player + chat)
- **ENDED + chat active**: poster image + "Stream has ended" message + functional chat
- **ENDED + chat archived**: poster image + "Stream ended, chat archived" message + read-only chat

```typescript
protected readonly isLive = computed(() => this.data()?.isLive ?? false);
protected readonly isChatArchived = computed(() => this.data()?.isChatArchived ?? false);
protected readonly isEnded = computed(() => !this.isLive() && this.data() !== null);
```

In the `effect()` for HLS init, guard with `isLive()`:
```typescript
effect(() => {
  const d = this.data();
  const el = this.videoEl();
  if (d && el && !this.hlsStarted && d.isLive && d.playUrl) {
    this.hlsStarted = true;
    this.startHls(d.playUrl, el.nativeElement);
  }
});
```

#### 12. `watch.page.html` — Conditional rendering

```html
@if (data(); as d) {
  <div class="watch-layout flex flex-column lg:flex-row w-full h-full">
    <div class="watch-player-col flex-auto flex flex-column">
      <div class="watch-chassis relative bg-black">
        @if (isLive() && d.playUrl) {
          <!-- HLS player -->
          <video #videoPlayer class="w-full block" controls playsinline
                 [poster]="d.thumbnailUrl || 'img/stream-placeholder.svg'"></video>
        } @else {
          <!-- Static poster for ended/unarchived streams -->
          <img [src]="d.thumbnailUrl || 'img/stream-placeholder.svg'"
               class="w-full block" style="aspect-ratio: 16/9; object-fit: cover;" />
          <div class="absolute top-0 left-0 right-0 bottom-0 flex align-items-center justify-content-center"
               style="background: rgba(0,0,0,0.6);">
            <div class="text-center text-white">
              <i class="pi pi-video text-4xl mb-2"></i>
              <p class="text-lg font-medium m-0">Stream has ended</p>
              @if (isChatArchived()) {
                <p class="text-sm mt-1 m-0">Chat has been archived</p>
              } @else {
                <p class="text-sm mt-1 m-0">Chat is still active — join the conversation!</p>
              }
            </div>
          </div>
        }
        <!-- info bar — same as before -->
      </div>
    </div>
    <div class="watch-chat-col flex-none w-full lg:w-22rem">
      <app-stream-chat-shell
        [live]="isLive() || !isChatArchived()"
        [roomKey]="d.roomKey" />
    </div>
  </div>
}
```

#### 12b. `stream-detail.page.ts` + `.html` — Chat archive toggle

Add to the stream detail page (creator view):
- Toggle/checkbox for "Auto-archive chat when stream ends"
- Number input for "Archive delay (minutes)"
- Send via `PATCH /v1/streams/{id}`

The toggle should only be visible for DRAFT/LIVE streams (not ENDED/CANCELLED).

### `StreamChatShellComponent` — Update placeholder behavior

The `StreamChatShellComponent` already has a `live` input. When `live=false` and `roomKey` is set, the chat panel should show a note that the stream has ended but chat is still open. Currently it shows "Chat will appear when the stream is live." Change the `emptyMessage` computed:

```typescript
protected readonly emptyMessage = computed(() => {
  if (this.roomKey()) return ''; // has room — show chat
  if (this.live()) return 'Live chat is not connected yet.';
  return 'Chat has been archived.';
});
```

## Files to Change (Complete List)

| # | File | Action |
|---|------|--------|
| 1 | `V14__add_chat_archive_config.sql` | **Create** — migration |
| 2 | `StreamSessionEntity.java` | Edit — add 3 fields |
| 3 | `StreamService.java` | Edit — `getWatchData()`, `applyMetadataUpdates()`, `handleUnpublish()` |
| 4 | `WatchResponse.java` | Edit — add `isLive`, `isChatArchived`, `thumbnailUrl` |
| 5 | `UpdateStreamRequest.java` | Edit — add `autoArchiveChat`, `chatArchiveDelayMinutes` |
| 6 | `ChatArchiveScheduler.java` | **Create** — scheduled task |
| 7 | `StreamEvent.java` (common) | Edit — add archive config fields |
| 8 | `StreamControlListener.java` (chat) | Edit — conditional archive on STREAM_ENDED |
| 9 | `WatchResponseDto` (Angular) | Edit |
| 10 | `watch.page.ts` | Edit — handle ended states, computed signals |
| 11 | `watch.page.html` | Edit — conditional player vs poster |
| 12 | `stream-detail.page.ts` + `.html` | Edit — add chat archive toggle UI |

## Verification

1. Start a stream, have viewers in chat
2. End the stream
3. Viewers see: poster image + "Stream has ended" + chat still functional
4. After configured delay → chat becomes read-only (archived)
5. Reload the page → if chat not archived, still functional
6. Toggle auto-archive in stream settings → verify config saves

## Agent Execution

This task should be done in two sub-tasks:
1. **Backend** (DB + Java): Spawn agent for migration, entities, service changes, scheduler
2. **Frontend** (Angular): Spawn agent for watch page, detail page, DTOs
