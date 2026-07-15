# T6: Browse Page — Multi-Rail Layout

**Status**: Ready
**Depends on**: T5 (SSE events) for live updates to rails
**Scope**: Backend (stream-service) + Frontend (Angular) — 4 files

## Problem

The browse page (`/browse`) currently shows only a single grid of live streams with cursor-based pagination and a search bar. There is no:
- "Recently Ended" rail showing streams that ended recently
- "Categories" rail for category-based discovery
- Any visual hierarchy beyond a flat grid

## Design

Restructure the browse page into 3 horizontal rails:

```
┌─────────────────────────────────────────────────────┐
│ Search bar + category chips (filter)                 │
├─────────────────────────────────────────────────────┤
│ ▶ Live Now (sorted by most viewers)                 │
│   [card] [card] [card] [card] [card] →              │
├─────────────────────────────────────────────────────┤
│ ▶ Recently Ended (last 24h)                         │
│   [card] [card] [card] [card] →                     │
├─────────────────────────────────────────────────────┤
│ ▶ Browse by Category                                │
│   [Gaming] [Music] [Talk] [Art] [Tech] ...          │
│   (selecting a category filters the Live Now rail)   │
└─────────────────────────────────────────────────────┘
```

### Backend Changes

#### 1. `GET /v1/streams/live` — Add sort parameter

Already supports `keyword`, `cursor`, `limit`. Add `sort` param:

```
GET /v1/streams/live?sort=views&limit=20
```

In `StreamService.getLiveStreams()`, add a sort option:
```java
// After the WHERE clause and before ORDER BY:
if ("views".equals(sort)) {
    sql.append(" ORDER BY views DESC, started_at DESC");
} else {
    sql.append(" ORDER BY started_at DESC"); // default
}
```

#### 2. New endpoint: `GET /v1/streams/recently-ended`

```
GET /v1/streams/recently-ended?hours=24&limit=20
```

Returns streams that ended within the last N hours (default 24), ordered by `ended_at DESC`.

**In `StreamController.java`:**
```java
@GetMapping("/streams/recently-ended")
public Mono<ResponseEntity<List<StreamSummaryResponse>>> recentlyEnded(
        @RequestParam(defaultValue = "24") int hours,
        @RequestParam(defaultValue = "20") int limit) {
    return streamService.getRecentlyEndedStreams(hours, limit)
            .collectList()
            .map(ResponseEntity::ok);
}
```

**In `StreamService.java`:**
```java
public Flux<StreamSummaryResponse> getRecentlyEndedStreams(int hours, int limit) {
    int effectiveLimit = Math.clamp(limit, 1, 50);
    String sql = """
            SELECT * FROM stream.stream_session
            WHERE status = 'ENDED'
              AND ended_at > NOW() - (:hours || ' hours')::INTERVAL
            ORDER BY ended_at DESC
            LIMIT :limit
            """;
    return databaseClient.sql(sql)
            .bind("hours", hours)
            .bind("limit", effectiveLimit)
            .map(SUMMARY_MAPPER)
            .all();
}
```

### Frontend Changes

#### 3. `browse.page.ts` — Restructure state

Replace single `streams` signal with separate signals per rail:

```typescript
// Live Now rail
protected readonly liveStreams = signal<StreamSummaryResponseDto[]>([]);
protected readonly liveCursor = signal<string | null>(null);
protected readonly liveHasMore = signal(false);

// Recently Ended rail
protected readonly endedStreams = signal<StreamSummaryResponseDto[]>([]);
protected readonly endedLoading = signal(true);

// Categories
protected readonly categoryNames = signal<string[]>([]);
protected readonly selectedCategory = signal<string | null>(null);

// Search
protected readonly searchQuery = signal('');
```

Load both rails on init:
```typescript
ngOnInit(): void {
    this.loadLive();
    this.loadRecentlyEnded();
    // SSE subscription for live updates (from T5)
    this.streamSseService.connect();
}
```

#### 4. `browse.page.html` — Multi-rail layout

Use the existing `RailComponent` (already used in channel page):

```html
<div class="browse-page flex flex-column gap-6 p-4">
  <!-- Search + category chips -->
  <div class="browse-toolbar flex flex-column gap-3">
    <div class="flex align-items-center gap-3">
      <p-iconfield>
        <p-inputicon class="pi pi-search" />
        <input pInputText [ngModel]="searchQuery()"
               (ngModelChange)="onSearchChange($event)"
               placeholder="Search streams..." class="w-20rem" />
      </p-iconfield>
      @if (searchQuery() || selectedCategory()) {
        <p-button label="Clear" icon="pi pi-times" severity="secondary"
                  [text]="true" (click)="clearFilters()" />
      }
    </div>
    @if (categoryNames().length > 0) {
      <div class="flex flex-wrap gap-2">
        @for (cat of categoryNames(); track cat) {
          <p-chip [label]="cat"
                  [class.bg-primary]="selectedCategory() === cat"
                  (click)="selectCategory(cat)" />
        }
      </div>
    }
  </div>

  <!-- Rail 1: Live Now -->
  <app-rail title="Live Now" [loading]="liveLoading()"
            emptyLabel="No live streams right now">
    @for (stream of filteredLiveStreams(); track stream.id) {
      <a [routerLink]="['/watch', stream.id]"
         class="rail-card flex-none w-20rem ...">
        <!-- thumbnail, title, viewer count, category badge -->
      </a>
    }
  </app-rail>

  <!-- Rail 2: Recently Ended -->
  <app-rail title="Recently Ended" [loading]="endedLoading()"
            emptyLabel="No recently ended streams">
    @for (stream of endedStreams(); track stream.id) {
      <a [routerLink]="['/watch', stream.id]"
         class="rail-card flex-none w-20rem ...">
        <!-- thumbnail, title, "Ended" badge, ended time -->
      </a>
    }
  </app-rail>

  <!-- Rail 3: Categories (if no category is selected; otherwise filtered live grid) -->
  @if (!selectedCategory()) {
    <section>
      <h2 class="text-xl font-semibold mb-3">Browse by Category</h2>
      <div class="grid">
        @for (cat of categoryNames(); track cat) {
          <div class="col-6 md:col-4 lg:col-3 xl:col-2">
            <div class="category-card p-3 border-round-lg surface-card border-1 surface-border cursor-pointer"
                 (click)="selectCategory(cat)">
              <span class="text-sm font-medium">{{ cat }}</span>
            </div>
          </div>
        }
      </div>
    </section>
  }
</div>
```

#### 4a. `StreamService` (Angular) — Add API method

```typescript
public getRecentlyEndedStreams(params?: {
  hours?: number;
  limit?: number;
}): Observable<StreamSummaryResponseDto[]> {
  const q = new URLSearchParams();
  if (params?.hours) q.set('hours', String(params.hours));
  if (params?.limit) q.set('limit', String(params.limit));
  const qs = q.toString();
  return this.http.get<StreamSummaryResponseDto[]>(
    `${this.base}/streams/recently-ended${qs ? '?' + qs : ''}`,
  );
}
```

Also add `sort` param to existing `getLiveStreams()`:
```typescript
public getLiveStreams(params?: {
  keyword?: string;
  cursor?: string;
  limit?: number;
  sort?: string;  // NEW: 'views' or 'started_at'
}): Observable<LiveStreamPageResponseDto> {
  // ... existing code, add sort to URLSearchParams
}
```

## Files to Change (Complete List)

| # | File | Action |
|---|------|--------|
| 1 | `StreamController.java` | Edit — add `GET /streams/recently-ended`, add sort to live |
| 2 | `StreamService.java` | Edit — add `getRecentlyEndedStreams()`, sort support |
| 3 | `stream.service.ts` (Angular) | Edit — add `getRecentlyEndedStreams()`, sort param |
| 4 | `browse.page.ts` + `.html` | Edit — restructure to multi-rail layout |

## Verification

1. Navigate to `/browse`
2. See "Live Now" rail with active streams sorted by views
3. See "Recently Ended" rail with streams that ended in last 24h
4. See "Browse by Category" section with clickable category chips
5. Search/filter works across the live rail
6. Clicking a live stream → goes to `/watch/:id`
7. Clicking an ended stream → goes to `/watch/:id` (shows ended state from T4)

## Agent Execution

Two parallel sub-tasks:
1. **Backend**: Add recently-ended endpoint + sort to live
2. **Frontend**: Restructure browse page to multi-rail with new API methods
