# ADR-0000: Architecture Foundation — Insight Service

**Date**: 2026-07-10
**Status**: proposed
**Deciders**: hieuht, Claude

> **Entry point.** Read this before modifying or extending `insight-service/`.
> It defines the two sub-domains the service owns (analytics/engagement + AI/LLM),
> the event-driven data flow, and the phased rollout strategy. ADRs
> [0001](0001-insight-service-architecture.md) through
> [0004](0004-capability-ladder.md) cover the AI/LLM layer in detail;
> this ADR defines the foundation both sub-domains rest on.

## Context

`insight-service` was originally scoped as "the AI/LLM layer" — summarization,
classification, semantic search, RAG. That scope captured one dimension but
missed the data layer those features depend on: **who watched what, when, and
how engaged were they?** Without engagement data, even simple suggestions
("trending now") are impossible.

We now define insight-service as a **two-sub-domain bounded context**:

| Sub-domain | Concern | Depends on |
|------------|---------|------------|
| **Analytics & Engagement** (this ADR) | Capture view/like/sub events, compute aggregations, surface suggestions | Kafka events from stream-service + future channel-service |
| **AI / LLM** ([0001](0001-insight-service-architecture.md)–[0004](0004-capability-ladder.md)) | Moderation, summarization, semantic search, RAG | LLM provider + pgvector |

The analytics sub-domain is the **data foundation** for the AI sub-domain: a
"catch me up on chat" summary is useful, but "recommend streams you'll actually
like" is transformative. Both live in the same service because they share
infrastructure (Kafka consumers, PostgreSQL, the same deployment), but they
evolve on independent timelines.

## Decision

### 1. Two sub-domains, one service, independent timelines

```
insight-service/
├── analytics/          ← this ADR defines
│   ├── event/          # EngagementEvent consumer + model
│   ├── aggregation/    # Channel/category trending, peak-hour histograms
│   └── suggestion/     # Suggestion REST API + logic
│
└── llm/                ← ADRs 0001–0004 define
    ├── port/           # LLM provider port + adapter
    ├── moderation/     # Toxicity/spam classification
    ├── summarization/  # Chat catch-up, stream recaps
    └── rag/            # Embedding → retrieval → generation pipeline
```

The analytics side ships **first** (Phase A — no channel-service dependency).
The LLM side follows (Phase B+ — as described in 0004's capability ladder).
They share nothing at the code level except the service scaffold and database
connection; a future split into two services is possible but not required.

### 2. Event-driven ingestion (Kafka)

Engagement events flow through Kafka, matching the platform's existing pattern
(stream-service → Kafka → downstream consumers):

```
Producer                     Topic                  Consumer
─────────                    ─────                  ────────
stream-service           → stream.view           ──→ insight-service
channel-service (future) → channel.like          ──→ insight-service
channel-service (future) → channel.subscribe     ──→ insight-service
```

**Why Kafka and not synchronous REST writes?** The view/like/sub action
succeeds regardless of whether analytics recorded it — a failed analytics write
must never reject a view or like. Kafka decouples the hot path from the
analytics path. A missed event loses one data point; a blocked REST call loses
a user action.

### 3. Engagement event model

```java
public record EngagementEvent(
    String eventId,          // UUID
    String eventType,        // VIEW | LIKE | SUBSCRIBE
    String actorSubject,     // who performed the action (JWT sub)
    String targetType,       // CHANNEL | CATEGORY
    String targetId,         // username (for CHANNEL) or category name
    OffsetDateTime occurredAt
) {}
```

**Why not include the score in the event?** Scores are policy, not fact. A VIEW
is 0.25 pts today, might be 0.3 tomorrow. The event records *what happened*;
the aggregation layer applies the scoring policy. This is the same principle as
not baking the discount percentage into an order line — it changes, and
replaying events against a new policy should produce different results.

### 4. Scoring model (configurable, not hardcoded)

| Action | Score | Rationale |
|--------|-------|-----------|
| VIEW | 0.25 | Passive — user opened a page, may have bounced immediately |
| LIKE | 0.75 | Active — user expressed positive intent |
| SUBSCRIBE | 1.50 | High-intent — user paid money or committed to recurring engagement |

Weight ratio: **1 : 3 : 6** (VIEW : LIKE : SUBSCRIBE). Tunable per environment
via `application.yml`; not schema, not code.

Scores are aggregated by two dimensions:

| Dimension | Key | Aggregation | Suggestion use |
|-----------|-----|-------------|----------------|
| Channel | `broadcaster_username` | Sum of all engagement scores for that channel | "Channels you may like" |
| Category | category name | Sum of all engagement scores for that category | "Categories you may like" |

A user's profile is the intersection: *"user U has affinity X for category C
and affinity Y for channel Z."* Suggestions rank unseen channels/categories by
the user's affinity vector.

### 5. Streamer-side analytics (same event stream, different queries)

The same engagement events answer streamer-facing questions without new
instrumentation:

| Streamer question | Derived from |
|-------------------|-------------|
| When are my viewers most active? | VIEW events grouped by hour-of-day |
| How long do viewers stay? | VIEW event pairs (arrival + next-view interval) |
| Which category brings the most traffic? | VIEW events grouped by category |
| When should I stream next? | Intersection of "my peak hours" + "category peak hours" |

None of these need new events — they're all aggregations over the VIEW stream.
The peak-hour histogram is particularly low-hanging fruit: a `SELECT
EXTRACT(HOUR FROM occurred_at), COUNT(*) ... GROUP BY 1` over view events
filtered by channel produces a schedule recommendation with zero ML.

### 6. Cold-start heuristics (Phase A — no channel-service, no like/sub data)

While the platform has few users and no like/sub events, personalized scoring
produces noise. The cold-start fallback is simple heuristics:

| Suggestion type | Cold-start heuristic | Warm heuristic (Phase B) |
|-----------------|---------------------|--------------------------|
| Channels | Recently active (streamed in last 7 days) + view count | Weighted engagement score |
| Categories | Most-streamed categories this week | User's affinity vector |
| Stream time | Category peak-hour histogram | Personalized peak × category peak intersection |

The cold-start heuristics require only **view events** from stream-service, which
already exists. Everything else is a SQL query.

### 7. What this service does NOT own

| Concern | Owned by | Why |
|---------|----------|-----|
| Follow / Like / Subscribe actions | channel-service (future) | insight-service *consumes* the events, not the actions |
| Subscription payments | payment-service (future) | Separate bounded context |
| Stream lifecycle | stream-service | Already well-defined |
| Notification dispatch | notification-service (future, Phase 5) | "New stream from channel you like" is a notification concern |

## Consequences

### Positive

- **One service, two complementary halves.** Analytics provides the raw material
  (engagement data); AI/LLM provides intelligence on top of it. Together they
  enable features no single sub-domain could: "summarize the stream I'd most
  enjoy based on my taste" is a RAG query ranked by engagement scores.
- **Event-driven = safe to fail.** A Kafka consumer crash loses no events
  (they're still on the topic). A scoring policy change replays against stored
  events. The hot path (stream-service API) has zero analytics latency added.
- **No new infrastructure.** Kafka + PostgreSQL (the `insight_db` database
  already allocated for Phase 7) are the only dependencies. pgvector is already
  planned (0003). No Redis, no Elasticsearch, no dedicated analytics DB.
- **Phase A is buildable today.** View tracking requires only a Kafka producer
  in stream-service (emit `stream.view` on `GET /v1/channels/{username}`) and
  a consumer in insight-service. No channel-service, no social graph.
- **Streamer analytics are free.** Peak-hour histograms, top-category analysis,
  and stream-time suggestions are all SQL over the same view-event table —
  no new instrumentation needed.

### Negative

- **Personalized suggestions are weak until like/sub data exists.** The
  cold-start heuristics are generic (trending, recent, popular). The weighted
  scoring model depends on channel-service for like/sub events. This is
  acceptable: the model improves as the platform grows.
- **View event noise.** A VIEW event fires on every page load — a user
  refreshing a channel page 10 times generates 10 events. The scoring weight
  (0.25) is deliberately low to dampen this. If noise becomes a problem, a
  deduplication window (one VIEW per user-channel per hour) can be added to the
  consumer without changing the event schema.
- **insight-service scope creep risk.** Two sub-domains in one service could
  grow into a monolith. The package separation (`analytics/` vs `llm/`) and
  the independent timelines (Phase A for analytics, Phase B+ for LLM) are
  designed so either can be extracted without touching the other. If one grows
  unwieldy before the other, split them.

## Phase A rollout (what ships first)

1. **`stream.view` Kafka topic** — stream-service emits on authenticated
   `GET /v1/channels/{username}` + `GET /v1/streams/{id}`.
2. **insight-service `EngagementEvent` consumer** — persists to
   `insight.engagement_event` table (event_id, event_type, actor_subject,
   target_type, target_id, occurred_at).
3. **Suggestion endpoints** — `GET /v1/suggestions/channels`,
   `GET /v1/suggestions/categories` (cold-start heuristics).
4. **Streamer analytics endpoints** — `GET /v1/analytics/{username}/peak-hours`,
   `GET /v1/analytics/{username}/top-categories`.

## Phase B (after channel-service exists)

1. **`channel.like` + `channel.subscribe`** events from channel-service.
2. **Weighted scoring** applied in the aggregation layer.
3. **Personalized suggestions** ("Things you may also like") based on user
   affinity vectors.

## Related

- [0001](0001-insight-service-architecture.md) — Standalone hexagonal service
- [0004](0004-capability-ladder.md) — LLM capability delivery order
- [channel-page-scope-review.md](../../plans/channel-page-scope-review.md) —
  defines the channel-service extraction seam
- [ADR-0007 (stream)](../stream/0007-public-channel-read-and-channel-service-seam.md) —
  channel-service boundary definition
- [IMPLEMENTATION-PLAN.md](../../IMPLEMENTATION-PLAN.md#phase-7--ai--llm-layer-insight-service-) —
  Phase 7 original placeholder
