# T7: Watch History

**Status**: Ready
**Depends on**: T4 (ended stream watch page) — history items link to watch page which now supports ended streams
**Scope**: DB migration + Backend (stream-service) + Frontend (Angular) — 8 files

## Problem

Users have no way to find streams they previously watched. If they close the browser tab during a stream, they can't easily find it again. The `stream.stream_view_event` table exists but is analytics-only (aggregated for view counts), not user-facing.

## Design

### Database

New table `stream.stream_watch_history`:

```sql
CREATE TABLE IF NOT EXISTS stream.stream_watch_history (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_subject            VARCHAR(128) NOT NULL,
    stream_id               UUID NOT NULL REFERENCES stream.stream_session(id),
    watched_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    watch_duration_seconds  BIGINT DEFAULT 0,
    CONSTRAINT uq_watch_history_user_stream UNIQUE (user_subject, stream_id)
);

CREATE INDEX IF NOT EXISTS ix_watch_history_user
    ON stream.stream_watch_history (user_subject, watched_at DESC);

COMMENT ON TABLE stream.stream_watch_history IS
    'Per-user watch history. One entry per user per stream. Updated on repeat views.';
```

### Backend

#### 1. Entity: `WatchHistoryEntity.java`

```java
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "stream_watch_history", schema = "stream")
public class WatchHistoryEntity {
    @Id
    private UUID id;
    @Column("user_subject")
    private String userSubject;
    @Column("stream_id")
    private UUID streamId;
    @Column("watched_at")
    private OffsetDateTime watchedAt;
    @Column("watch_duration_seconds")
    private Long watchDurationSeconds;
}
```

#### 2. Repository: `WatchHistoryRepository.java`

```java
public interface WatchHistoryRepository extends ReactiveCrudRepository<WatchHistoryEntity, UUID> {
    Mono<WatchHistoryEntity> findByUserSubjectAndStreamId(String userSubject, UUID streamId);
    Flux<WatchHistoryEntity> findAllByUserSubjectOrderByWatchedAtDesc(String userSubject);
}
```

#### 3. DTO: `WatchHistoryResponse.java`

```java
public record WatchHistoryResponse(
        UUID id,
        UUID streamId,
        String title,
        String status,
        String category,
        String thumbnailUrl,
        String broadcasterUsername,
        Long views,
        OffsetDateTime watchedAt,
        Long watchDurationSeconds
) {}
```

#### 4. Service method: `StreamService.java`

**Record a view:**
```java
public Mono<Void> recordWatchHistory(UUID streamId, Jwt jwt) {
    String subject = jwt.getSubject();
    WatchHistoryEntity entry = new WatchHistoryEntity();
    entry.setId(UUID.randomUUID());
    entry.setUserSubject(subject);
    entry.setStreamId(streamId);
    entry.setWatchedAt(OffsetDateTime.now(ZoneOffset.UTC));
    entry.setWatchDurationSeconds(0L);

    return watchHistoryRepository.findByUserSubjectAndStreamId(subject, streamId)
            .flatMap(existing -> {
                existing.setWatchedAt(OffsetDateTime.now(ZoneOffset.UTC));
                return watchHistoryRepository.save(existing);
            })
            .switchIfEmpty(watchHistoryRepository.save(entry))
            .then();
}
```

**Query history:**
```java
public Flux<WatchHistoryResponse> getWatchHistory(Jwt jwt, int limit) {
    String subject = jwt.getSubject();
    return watchHistoryRepository.findAllByUserSubjectOrderByWatchedAtDesc(subject)
            .take(limit)
            .flatMap(entry -> repository.findById(entry.getStreamId())
                    .map(session -> new WatchHistoryResponse(
                            entry.getId(),
                            entry.getStreamId(),
                            session.getTitle(),
                            session.getStatus().wireValue(),
                            session.getCategory(),
                            session.getThumbnailUrl(),
                            session.getBroadcasterUsername(),
                            session.getViews(),
                            entry.getWatchedAt(),
                            entry.getWatchDurationSeconds()
                    ))
                    .defaultIfEmpty(new WatchHistoryResponse(
                            entry.getId(), entry.getStreamId(),
                            "[Deleted]", "UNKNOWN", null, null, null, 0L,
                            entry.getWatchedAt(), entry.getWatchDurationSeconds()
                    )));
}
```

#### 5. Controller endpoints: `StreamController.java`

```java
@PostMapping("/streams/{id}/watch-history")
public Mono<ResponseEntity<Void>> recordWatchHistory(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id) {
    return streamService.recordWatchHistory(id, jwt)
            .then(Mono.just(ResponseEntity.noContent().build()));
}

@GetMapping("/users/me/watch-history")
public Mono<ResponseEntity<List<WatchHistoryResponse>>> getWatchHistory(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "50") int limit) {
    return streamService.getWatchHistory(jwt, limit)
            .collectList()
            .map(ResponseEntity::ok);
}
```

### Frontend

#### 6. New page: `history.page.ts`

```typescript
@Component({
  selector: 'app-history-page',
  standalone: true,
  imports: [RouterModule, DatePipe, ProgressSpinnerModule, MessageModule, SkeletonModule],
  templateUrl: './history.page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HistoryPage implements OnInit {
  private readonly streamService = inject(StreamService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly placeholder = 'img/stream-placeholder.svg';
  protected readonly entries = signal<WatchHistoryEntryDto[]>([]);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | undefined>(undefined);

  ngOnInit(): void {
    this.streamService.getWatchHistory()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => { this.entries.set(data); this.loading.set(false); },
        error: () => { this.errorMessage.set('Failed to load history.'); this.loading.set(false); },
      });
  }
}
```

#### 7. New route: `app.routes.ts`

```typescript
{
  path: 'history',
  loadComponent: () =>
    import('./pages/history/history.page').then(m => m.HistoryPage),
},
```

#### 8. Menu item: `app-shell.component.ts`

Add a "History" item to the user dropdown menu, alongside "Channel" and "Creator Dashboard":

```html
<!-- In app-shell.component.html, user menu dropdown -->
<li>
  <a routerLink="/history" class="...">
    <i class="pi pi-history"></i> Watch History
  </a>
</li>
```

#### 9. Auto-record: `watch.page.ts`

When `getWatchData()` succeeds, call the record endpoint:
```typescript
// In watch.page.ts, after successful watch data load:
this.streamService.recordWatchHistory(id).subscribe();
```

Alternatively, record server-side in `getWatchData()` (simpler — one less HTTP call from the client). But server-side recording couples the read endpoint with a write side effect. **Use client-side recording** — explicit, testable, no surprises.

#### 10. Angular service + DTOs

Add to `stream.service.ts`:
```typescript
public recordWatchHistory(streamId: string): Observable<void> {
  return this.http.post<void>(`${this.base}/streams/${streamId}/watch-history`, null);
}

public getWatchHistory(limit?: number): Observable<WatchHistoryEntryDto[]> {
  const q = limit ? `?limit=${limit}` : '';
  return this.http.get<WatchHistoryEntryDto[]>(`${this.base}/users/me/watch-history${q}`);
}
```

New DTO:
```typescript
export interface WatchHistoryEntryDto {
  readonly id: string;
  readonly streamId: string;
  readonly title: string;
  readonly status: string;
  readonly category: string | null;
  readonly thumbnailUrl: string | null;
  readonly broadcasterUsername: string | null;
  readonly views: number;
  readonly watchedAt: string;
  readonly watchDurationSeconds: number;
}
```

## Files to Change (Complete List)

| # | File | Action |
|---|------|--------|
| 1 | `V15__create_watch_history.sql` | **Create** — migration |
| 2 | `WatchHistoryEntity.java` | **Create** |
| 3 | `WatchHistoryRepository.java` | **Create** |
| 4 | `WatchHistoryResponse.java` | **Create** (DTO) |
| 5 | `StreamService.java` | Edit — add `recordWatchHistory()`, `getWatchHistory()` |
| 6 | `StreamController.java` | Edit — add 2 endpoints |
| 7 | `stream.service.ts` (Angular) | Edit — add 2 API methods |
| 8 | `watch-history-entry.dto.ts` | **Create** (Angular DTO) |
| 9 | `history.page.ts` + `.html` | **Create** — new page |
| 10 | `app.routes.ts` | Edit — add `/history` route |
| 11 | `app-shell.component.html` | Edit — add menu item |
| 12 | `watch.page.ts` | Edit — call `recordWatchHistory()` on load |

## Verification

1. Watch a stream (live) → history entry created
2. Watch an ended stream (from T4) → history entry created
3. Navigate to `/history` → see the stream in the list
4. Click on a history entry → navigate to `/watch/:id` (shows ended state if applicable)
5. Watch the same stream twice → entry updated with new `watchedAt` (no duplicate)
6. Repeat views update the timestamp, don't create duplicates

## Agent Execution

Two sub-tasks:
1. **Backend** (DB + Java): Migration, entity, repository, service, controller
2. **Frontend** (Angular): Page, routing, menu item, service, DTO, auto-record hook
