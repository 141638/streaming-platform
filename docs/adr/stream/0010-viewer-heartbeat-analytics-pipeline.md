# ADR-0010: Viewer Heartbeat Analytics Pipeline

**Date**: 2026-07-17
**Status**: proposed
**Deciders**: hieuht, Claude
**Domain**: Stream Service / Insight Service

## Context

Viewer presence heartbeats (`POST /v1/streams/{id}/heartbeat`) write ephemeral keys to Redis
(`stream:presence:{streamId}:{viewerSubject}`) with a 30-second TTL. This data enables live
viewer counts today but vanishes after the TTL expires.

The platform needs durable time-series viewer data for:

- **Real-time dashboard**: Viewer count chart on the stream dashboard and channel analytics
- **Insight service** (Phase 7): Historical viewer analytics — peak concurrency, viewer retention
  curves, category popularity trends, optimal streaming times
- **Monetization** (Phase 8+): Ad impression estimates, sponsor reports

The question is: which service owns the harvesting pipeline, and what granularity should be stored?

## Decision

**stream-service owns the heartbeat harvesting pipeline.** A new `HeartbeatHarvestService`
runs every 30 seconds, SCANs Redis presence keys, and batch-UPSERTs aggregated minute-bucket
counts into a `stream_viewer_snapshot` table in PostgreSQL.

**Storage granularity: one row per stream per minute**, not per-user. Raw per-user heartbeats
are never written to the database — only aggregate counts. A daily compaction job (Spring
`@Scheduled` or Spring Batch) rolls minute buckets into hourly (90-day retention) and daily
(permanent retention) aggregations.

### Why stream-service

1. **Data proximity**: stream-service owns the Redis presence keys. Cross-service Redis access
   would be a coupling anti-pattern.
2. **Existing precedent**: `ViewCountFlushService` (ADR-0008) already demonstrates the identical
   Redis SCAN → PostgreSQL batch-INSERT pattern for view counting. The harvest service is the
   same architecture applied to presence keys instead of view-tracking hashes.
3. **Insight-service doesn't exist yet**: All Phase 7 ADRs are Proposed; no module, no build,
   no deployment. Blocking a working analytics pipeline on a future service would delay
   dashboard features.
4. **Clean export boundary**: stream-service owns the raw data (presence keys) and the
   aggregated data (`stream_viewer_snapshot`). insight-service consumes the aggregated data
   via REST API or direct database read when it comes online — it never needs to touch Redis.

### Why minute buckets, not per-user

Storing `(user_id, stream_id, timestamp)` for every heartbeat produces unmanageable volume:

- 200k concurrent viewers × 30-minute peak = 12M rows for one stream
- Thousands of streams × billions of rows per day

Minute-bucket aggregation reduces this to **one row per stream per minute regardless of
viewer count**: 1,000 active streams × 1,440 minutes/day = ~1.4M rows/day. This is a
comfortable write volume for PostgreSQL on modest hardware.

### Harvest interval = heartbeat TTL

The harvest runs every 30 seconds, which equals the heartbeat TTL. A viewer sending heartbeats
every 15 seconds will always be counted because their key TTL is refreshed before the next
harvest. The `GREATEST(viewer_count, new_count)` on UPSERT captures the peak within each minute
bucket — a reasonable approximation of concurrent viewers at that minute.

Duplication risk is zero: the harvest COUNT and the presence SETEX are synchronized at the
TTL boundary. A viewer who disconnects will have their key expire within 30 seconds and stop
being counted in the next harvest.

## Alternatives Considered

### Alternative 1: insight-service owns the harvesting

- **Pros**: The primary consumer of analytics data owns its ingestion; aligns with bounded context
- **Cons**: insight-service is scaffolding-only (ADRs + sketch, zero code); would need its own
  Redis connection to scan stream-service's keys; couples insight-service deployment to
  stream-service's Redis instance
- **Why not**: Cross-service Redis access is an anti-pattern. insight-service can consume the
  aggregated data when it exists.

### Alternative 2: Store raw per-user heartbeats in PostgreSQL directly

- **Pros**: Maximum data fidelity — every heartbeat is preserved for arbitrary analysis
- **Cons**: 12M writes per stream per 30-minute peak; billions per day at scale. Requires a
  dedicated time-series database or a very large PostgreSQL instance. The analytics value of
  per-user heartbeat data (vs. minute-bucket aggregates) is marginal for the planned use cases.
- **Why not**: Gross over-engineering for the current scale and analytics requirements.

### Alternative 3: Kafka-driven harvesting (heartbeat events published to Kafka)

- **Pros**: Decoupled — any service can consume heartbeat events for analytics; mirrors the
  stream lifecycle event pattern
- **Cons**: 67k messages/sec at 1M users (every 15s); adds Kafka broker load; requires a
  consumer that aggregates (same aggregation logic, just in a different process)
- **Why not**: The aggregation has to happen somewhere. Doing it at the source (stream-service,
  which already has Redis access) avoids the Kafka hop for a write path that is inherently
  ephemeral-to-durable, not event-to-event.

## Consequences

### Positive

- **Immediate dashboard capability**: `stream_viewer_snapshot` enables real-time viewer charts
  without waiting for Phase 7 infrastructure.
- **Predictable write volume**: ~1.4M rows/day for 1,000 active streams — easily handled by
  the existing PostgreSQL instance.
- **Privacy by design**: No per-user viewing data is stored; only aggregate counts.
- **Reuses proven patterns**: Identical architecture to `ViewCountFlushService` (ADR-0008) —
  same Redis SCAN, same batch UPSERT, same `@Scheduled` trigger.
- **Clean insight-service handoff**: When Phase 7 starts, insight-service reads
  `stream_viewer_snapshot` as a documented, stable data source.

### Negative

- **30-second data staleness**: The dashboard won't show sub-30-second viewer count changes.
  Acceptable for the current UX (current frontend polls every 10s; SSE push is every 10s).
- **`GREATEST` loses dips within a minute**: If viewers spike to 200k at 14:30:15 and drop to
  50k at 14:30:45, the stored value is 200k for the 14:30 bucket. For peak-concurrency
  analytics this is correct; for average-concurrency, the hourly aggregation compensates.
- **Daily compaction adds operational complexity**: Spring Batch or a `@Scheduled` job at
  off-peak hours. Can be deferred — minute buckets alone are manageable for weeks.

## Schema

```sql
CREATE TABLE IF NOT EXISTS stream_viewer_snapshot (
    id           BIGSERIAL PRIMARY KEY,
    stream_id    UUID NOT NULL,
    minute_bucket TIMESTAMPTZ NOT NULL,
    viewer_count BIGINT NOT NULL DEFAULT 0,
    harvested_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_stream_minute UNIQUE (stream_id, minute_bucket)
);

CREATE INDEX ix_snapshot_stream_time
    ON stream_viewer_snapshot (stream_id, minute_bucket DESC);
```

## Data Flow

```
Viewer browsers                stream-service                    PostgreSQL
    │                               │                               │
    ├── POST /heartbeat (15s) ──→  SETEX presence key (30s TTL)    │
    │                               │                               │
    │                    HeartbeatHarvestService                    │
    │                    (@Scheduled 30s)                           │
    │                         │                                     │
    │                    SCAN stream:presence:*                     │
    │                    GROUP BY streamId                          │
    │                    COUNT per stream                           │
    │                         │                                     │
    │                    UPSERT stream_viewer_snapshot ──────────→  │
    │                    (stream_id, minute_bucket, viewer_count)   │
    │                                                               │
    │                    Daily compaction (Spring @Scheduled)       │
    │                      minute → hourly (90d)                    │
    │                      hourly → daily (permanent)               │
    │                      DELETE minute rows                       │
```

## Retention Policy

| Granularity | Retention | Rationale |
|-------------|-----------|-----------|
| Minute | 1 day | Compacted to hourly daily |
| Hourly | 90 days | Quarter of analytics history |
| Daily | Permanent | Long-term trend analysis |

## References

- [ADR-0008](0008-view-count-analytics-pipeline.md) — View counting pipeline (same Redis→PG pattern)
- [ADR common/0002](../common/0002-redis-ephemeral-data-store.md) — Redis as ephemeral store
- [insight-service ADR-0001](../insight/0001-insight-service-architecture.md) — Consumer of analytics data
- [Option A Blueprint](../../plans/option-a-viewer-presence-fanout-blueprint.md) — Implementation plan
