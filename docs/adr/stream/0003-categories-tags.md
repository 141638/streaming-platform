# ADR-0003: Categories and Tags

**Status:** Accepted
**Date:** 2026-07-07
**Domain:** Stream Service

## Context

Streams currently use a free-text `category` VARCHAR column. This prevents:
- Category-based discovery (no indexed filtering)
- Consistent category naming across streams
- Frontend dropdown selection (requires a known vocabulary)

Additionally, streamers need tags for cross-category filtering (e.g., "ranked", "speedrun", "beginner-friendly").

## Decision

### Category: Managed Lookup Table

Create `stream_category` as a controlled vocabulary with a foreign key from `stream_session`:

```
stream_category (managed, seeded)
├── id UUID PK
├── name VARCHAR(64) UNIQUE  — "Gaming", "Music", etc.
├── slug VARCHAR(64) UNIQUE  — "gaming", "music", etc.
└── display_order INT        — for deterministic UI ordering

stream_session
├── category_id UUID FK → stream_category.id
└── category VARCHAR(64)      — denormalized name (backward compat)
```

The existing `category` VARCHAR column is **kept** as a denormalized copy populated on save. This avoids breaking existing queries while the `category_id` FK provides proper relational integrity and indexed filtering.

### Tags: PostgreSQL TEXT[] Array

Tags are stored as a `TEXT[]` array column with a GIN index for fast `@>` (contains) queries:

```sql
ALTER TABLE stream.stream_session ADD COLUMN IF NOT EXISTS tags TEXT[];
CREATE INDEX IF NOT EXISTS ix_stream_session_tags ON stream.stream_session USING GIN(tags);
```

In the Java entity, tags are a `String[]` field. The DTO exposes `List<String>`.

### Seed Data

Eight default categories seeded in V4 migration: Gaming, Music, Talk Show, Just Chatting, Art, Technology, Sports, Education.

### API

`GET /v1/categories` returns all categories ordered by `display_order`. No authentication required (public metadata).

### Alternatives Considered

| Approach | Verdict | Reason |
|----------|---------|--------|
| `category` VARCHAR only | Rejected | No referential integrity; free-text drift; no indexed filtering |
| `category_id` FK + drop `category` VARCHAR | Rejected | Breaking change; backward compat risk |
| Tags as join table (`stream_tag` + `stream_session_tag`) | Deferred | Better for tag analytics (popularity, dedup) but adds 2 tables; TEXT[] is sufficient for Phase 2 |
| Tags as JSONB column | Rejected | TEXT[] has native GIN index and `@>` operator; JSONB is heavier for simple string arrays |

## Consequences

- **Positive**: Category filtering is indexed and fast
- **Positive**: Frontend gets a dropdown from `GET /categories` instead of free-text input
- **Positive**: Tag queries (`WHERE tags @> ARRAY['ranked']`) are GIN-accelerated
- **Negative**: `category` VARCHAR remains as denormalized data — must be kept in sync with `category_id` on updates
- **Negative**: TEXT[] doesn't track tag popularity or provide global dedup — a join table (deferred) would be better for analytics

## References

- [ADR-0001: Stream State Machine](0001-stream-state-machine.md)
- `StreamCategoryEntity.java` — category entity
- `StreamCategoryRepository.java` — reactive repository
- `V4__categories_tags_state_machine.sql` — migration
