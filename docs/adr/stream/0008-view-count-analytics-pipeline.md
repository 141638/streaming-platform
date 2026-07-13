# ADR-0008: View Count Analytics Pipeline — Redis-Now, Kafka-Batch Later

**Status:** Accepted (Phase 1 implementing)
**Date:** 2026-07-13
**Domain:** Stream Service / Insight Service

## Context

Broadcast sorting by "popular" requires per-stream view counts. The naive approach —
`UPDATE stream_session SET views = views + 1` on every `GET /v1/streams/{id}` — creates
a write bottleneck: a popular stream with 1 000 concurrent viewers would queue 1 000
row-locking UPDATEs against the same `stream_session` row.

Additionally, simple counter increments have no deduplication: an F5 refresh, back-navigation,
or redirect all count as new views. For analytics purposes, we need per-user-per-stream
granularity so the insight service can compute unique viewers, return-visit frequency, and
(Phase 2) watch duration.

View tracking is fundamentally an **analytics concern**, not a session-lifecycle concern.
Treating it as such keeps the stream-service bounded context clean and enables the insight
service (already planned) to own aggregation, denormalization, and historical reporting.

## Decision

### Phase 1: Redis Per-User Hash + Batch Flush to Analytics Table (Now)

**View tracking path:**

```
GET /v1/streams/{id}
  │
  ├─► Resolve viewer identity:
  │     - JWT subject (authenticated users)
  │     - "ip:<remote-address>" (anonymous fallback)
  │
  ├─► Skip if viewer == broadcaster (self-view)
  │
  └─► Redis Hash: stream:view:{streamId}:{viewerId}
        Fields: user_id, first_seen_at, last_seen_at
        HSET user_id, HSETNX first_seen_at, HSET last_seen_at
        EXPIRE 24h (configurable)
```

**Deduplication mechanism:**

The Redis key `stream:view:{streamId}:{viewerId}` acts as a uniqueness constraint
within the TTL window:

- **First view:** `HSETNX first_seen_at` sets the field (key didn't exist → new unique).
- **Return visit within TTL:** `HSETNX first_seen_at` is a no-op (field already exists
  → same unique viewer, only `last_seen_at` updates).
- **Return visit after TTL expiry:** Key expired → treated as a new unique view.
- **Self-view:** Broadcaster viewing their own stream is skipped entirely.

This naturally limits to **one unique view per (stream, user) pair per 24 hours** without
any explicit dedup logic.

**Batch flush to PostgreSQL (scheduled task in stream-service):**

```
@Scheduled every N minutes (default 5 min)
  │
  ├─► SCAN Redis for stream:view:*
  ├─► For each key: parse {streamId} and {viewerId}
  ├─► HGETALL hash fields (user_id, first_seen_at, last_seen_at)
  ├─► INSERT INTO stream_view_event (stream_id, user_id, first_seen_at, last_seen_at)
  │     ON CONFLICT (stream_id, user_id) DO UPDATE
  │       SET last_seen_at = GREATEST(...), first_seen_at = LEAST(...)
  ├─► UPDATE stream_session SET views = (SELECT COUNT(*) FROM stream_view_event WHERE ...)
  └─► DEL Redis key (only after successful DB write — no lost events)
```

**New database table:**

```sql
CREATE TABLE stream.stream_view_event (
    id BIGSERIAL PRIMARY KEY,
    stream_id UUID NOT NULL REFERENCES stream.stream_session(id),
    user_id VARCHAR(255) NOT NULL,           -- JWT sub or "ip:x.x.x.x"
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (stream_id, user_id)
);
```

This table serves as the **source of truth** for view analytics. The `views` column on
`stream_session` is a denormalized cache: `SELECT COUNT(*) FROM stream_view_event WHERE
stream_id = :id`. The insight service (Phase 2) will query this table directly for
time-series aggregation, unique viewer counts, and return-visit patterns.

**Future column:** `watch_duration_seconds INTEGER` — to be populated when client-side
heartbeat pings are implemented. The column is intentionally omitted for now (YAGNI) but
documented here so it is not forgotten.

**Why Redis Hash (not counter):**

- `INCR` provides no deduplication — same user F5 = new count
- Hash keys naturally deduplicate by key existence
- Hash fields (`first_seen_at`, `last_seen_at`) carry analytics metadata
- Same O(1) write path as `INCR` — no performance regression
- `HSETNX` (write-if-absent) preserves the original `first_seen_at` on return visits

**Why batch flush:**

- Batching N view events into bulk INSERTs reduces database writes
- ON CONFLICT upsert handles the edge case where the same (stream, user) pair was
  flushed in a previous cycle
- Redis key is deleted only after DB write succeeds → no lost events if flush crashes
- `views` column is recomputed from the events table each flush (correctness > micro-optimization)

**Why IP fallback:**

- The system currently requires authentication for all endpoints, but designing for
  anonymous viewers from the start avoids a migration when anonymous access is added
- IP is a weak but functional identity for deduplication in the absence of a user account
- IPv6 addresses (which contain colons) are preserved by the key parsing strategy

### Phase 2: Kafka Analytics Pipeline (Future — supersedes or amends this ADR)

**Target architecture:**

```
GET /v1/streams/{id}
  │
  ├─► Publish StreamViewEvent to Kafka    (fire-and-forget, same pattern as ADR-0002)
  │     topic: stream.views
  │     payload: { streamId, viewerSubject?, timestamp, source }
  │
  ▼
Insight Service (future)
  │
  ├─► Consume stream.views events
  ├─► Aggregate into time-window buckets (1min, 5min, 1hr)
  ├─► Denormalize into insight_warehouse or ClickHouse
  │
  ▼
Batch Sync (insight-service → stream-service)
  │
  └─► Periodically push aggregated view counts to stream_session.views
       (or stream-service pulls from an insight-service API)
```

**Migration path from Phase 1 → Phase 2:**

1. Deploy insight-service with `stream.views` consumer
2. Change the view-tracking path from Redis HSET to Kafka publish (one-line change in
   controller middleware or an HTTP filter)
3. Run Phase 1 and Phase 2 in parallel for one flush cycle to verify counts match
4. Deprecate the Redis batch flush — insight-service becomes the sole source of truth
5. Remove Redis view-event keys and scheduled task

**Why Phase 2 is deferred:**
- Insight service is not yet built
- Kafka is wired for **producing** events (ADR-0002) but has no consumer infrastructure
- The Redis approach delivers working "sort by popular" and per-user dedup today without
  blocking on the insight service roadmap

## Alternatives Considered

### Alternative 1: Direct `views = views + 1` UPDATE on `stream_session`
- **Pros**: Simplest — no Redis, no batch job, no Kafka
- **Cons**: Row-locking contention under concurrent viewers; mixes analytics writes into
  the session lifecycle table; violates bounded-context separation; no per-user dedup
- **Why not**: Doesn't scale past trivial traffic

### Alternative 2: Only Redis, no PostgreSQL column — sort from Redis at query time
- **Pros**: No migration needed; no batch flush
- **Cons**: Sorting requires a Redis fetch for every row in the result set (N+1);
  impossible to sort at the database level (our query builder pushes ORDER BY to SQL);
  Redis outage = broken sort
- **Why not**: Destroys the SQL query builder's ability to sort server-side; brittle

### Alternative 3: Separate `stream_views` materialized view in PostgreSQL
- **Pros**: Stays within PostgreSQL; no Redis dependency
- **Cons**: Still requires per-view writes to a hot row; materialized view refresh
  intervals add staleness; doesn't solve the write-contention problem
- **Why not**: Just moves the bottleneck from `stream_session` to another table

### Alternative 4: Simple Redis INCR counter (original Phase 1 design)
- **Pros**: Simplest Redis approach; O(1) atomic increment
- **Cons**: No per-user deduplication — F5/refresh/redirect all count as new views;
  no analytics metadata (who viewed, when, how often); harder to add analytics later
  without a data migration
- **Why not**: Replaced by hash-based design before implementation — the marginal
  complexity of a hash vs counter is negligible, and the analytics value is substantial

## Consequences

### Positive
- **Per-user deduplication** — one view per (stream, user) pair per TTL window
- **Analytics-ready data** — `stream_view_event` table supports unique viewer counts,
  return-visit frequency, and time-window aggregation by the insight service
- **Sort-by-popular works today** without building the insight service
- **Zero DB write per view** — Redis absorbs all view-tracking traffic
- **Clean migration path** to Kafka/insight service — swap one line of code
- **No new infrastructure** — Redis is already in the stack
- **Self-view exclusion** prevents broadcasters from inflating their own counts
- **IP fallback** future-proofs for anonymous access
- **No lost events** — Redis key is deleted only after DB write succeeds

### Negative
- **Stale reads between flushes**: View counts lag by up to the flush interval
  (acceptable for a channel-page sort; viewers don't expect real-time analytics)
- **Redis outage = lost tracking**: If Redis is down, view events for that period are
  not recorded. Mitigation: structured logging on tracking failure; the events table
  is the source of truth, and missed windows are a known trade-off
- **COUNT(*) recompute per flush**: Recomputing `views = COUNT(*)` for each flushed
  stream adds a query per stream per flush cycle. Acceptable for Phase 1; can be
  optimized to `views + 1` increment for new-unique-only if needed
- **IPv6 colons in viewer ID**: IPv6 addresses contain `:` which complicates key parsing.
  Current implementation uses `indexOf(':')` with limit — only the first colon after the
  UUID separates streamId from viewerId, so IPv6 addresses in the viewerId portion are
  preserved intact

### Risks
- **Risk**: Scheduled task crashes and stops flushing → views column goes stale
  **Mitigation**: Health-check endpoint monitors last-flush timestamp; alert if >2× interval
- **Risk**: Redis OOM from unbounded view-event keys
  **Mitigation**: 24h TTL on all `stream:view:*` keys; keys for inactive streams expire
  automatically; key count is bounded by (unique streams × unique viewers per 24h)

## References

- [ADR-0002: Kafka Event Publishing](0002-kafka-event-publishing.md) — fire-and-forget publish pattern
- [Stream Service Architecture Foundation](0000-architecture-foundation.md)
- `StreamEventPublisher.java` — reactive Kafka wrapper (reused for `StreamViewEvent` in Phase 2)
- `BroadcastQueryBuilder.java` — views sort with `NULLS LAST` (Phase 7+)
- `ViewCountFlushService.java` — scheduled Redis → PostgreSQL batch flush
- `StreamService.java#trackViewEvent()` — per-user hash tracking with self-view exclusion
- `ViewCountProperties.java` — configurable view TTL (default 24h)
- `V12__create_stream_view_events.sql` — analytics events table migration
