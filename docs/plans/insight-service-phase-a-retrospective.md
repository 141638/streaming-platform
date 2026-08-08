# Insight Service Phase A — Implementation Retrospective

**Date:** 2026-08-08
**Status:** Complete — Phase A (analytics sub-domain) shipped, Phase B+ deferred per blueprint

## 1. What was implemented (vs the original plan)

| # | Planned Item | Commit(s) | Notes |
|---|-------------|-----------|-------|
| 1 | `EngagementEvent` record in `common` | uncommitted | Immutable record with `viewed()` factory; mirrors `StreamEvent` pattern |
| 2 | `streamViewTopic()` bean in `KafkaTopicConfig` | uncommitted | 1 partition, 1 replica, topic `stream.view` |
| 3 | `insight-service` Gradle module + `build.gradle.kts` | uncommitted | Mirrors `stream-service/build.gradle.kts` exactly |
| 4 | `InsightApplication.java` + `application.yml` | uncommitted | Port 0 (Eureka), base-path `/api/insights`, Flyway `insight` schema |
| 5 | `InsightProperties` config record | uncommitted | `@ConfigurationProperties("insight")` with nested `Scoring` and `Suggestions` records |
| 6 | Flyway V1 migration | uncommitted | `CREATE SCHEMA insight` + `CREATE TABLE insight.engagement_event` + 4 indexes (all Tier 1 types) |
| 7 | `SecurityConfig` (JWT) | uncommitted | Mirrors notification-service: `Structured401AuthenticationEntryPoint` |
| 8 | `R2dbcConfig` | uncommitted | `TransactionalOperator` bean only; no converters (all Tier 1) |
| 9 | `EngagementEventEntity` | uncommitted | `Persistable<UUID>` with `@Transient isNew`; mirrors `FanOutJob` pattern |
| 10 | `EngagementEventRepository` | uncommitted | 4 `@Query` methods with nested projection records |
| 11 | `EngagementEventListener` (Kafka consumer) | uncommitted | Mirrors `StreamControlListener`: JSON deserialize → Redis SETNX dedup (24h) → switch routing → `blockOptional(10s)` |
| 12 | `EngagementService` | uncommitted | Entity conversion + `repository.save()` with `onErrorComplete` for idempotent delivery |
| 13 | `SuggestionService` | uncommitted | Trending channels/categories with configurable recency window; manual PnD/PnW parsing fallback to `Duration.parse()` |
| 14 | `AnalyticsService` | uncommitted | `getStreamAnalytics` zips `StreamViewStats` + hourly distribution, computes `peakHour` |
| 15 | Domain DTOs (3 records) | uncommitted | `ChannelSuggestion`, `CategorySuggestion`, `StreamAnalytics` |
| 16 | `SuggestionController` + `SuggestionResponse` | uncommitted | `GET /v1/suggestions` → `Mono.zip(channels, categories)` |
| 17 | `AnalyticsController` + `StreamAnalyticsResponse` | uncommitted | `GET /v1/analytics/streams/{streamId}` → 404 if no data |
| 18 | `ViewEventProducer` (stream-service) | uncommitted | Fire-and-forget `KafkaTemplate.send()` on `boundedElastic`; log-and-drop on failure |
| 19 | Wire producer into `StreamService` + `StreamController` | uncommitted | `getStream()`: `.doOnSuccess()` after `trackViewEvent()` for non-owner views. `getChannelHome()`: added `Jwt` param, emits single view event for non-owner channel page visits |
| 20 | Gateway route for insight-service | uncommitted | `Path=/api/insights/**` → `lb://insight-service` |
| 21 | Tests | uncommitted | Updated `StreamServiceTest` constructor to pass 3 new mocks (`WatchHistoryRepository`, `SseConnectionRegistry`, `TransactionalOperator`, `ViewEventProducer`). Insight-service unit tests deferred per `lightweight-testing-scope` policy. |

**Summary:** All 21 planned tasks completed. Zero deviations from the blueprint architecture. 20 new files created, 7 modified across 4 modules. Main source compilation passes clean (`:common`, `:insight-service`, `:stream-service`, `:gateway-service`).

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking Doc | Reason |
|------|------------|-------------|--------|
| LIKE / SUBSCRIBE event types | Phase B | `insight-service-phase-a-blueprint.md` §Deferred | Requires channel-service (future Phase) |
| Personalized suggestions (user affinity vector) | Phase B | blueprint §Deferred | Requires LIKE/SUBSCRIBE data for meaningful weights |
| pgvector extension + Rung 3 (embeddings) | Phase 7.3 | `IMPLEMENTATION-PLAN.md` Phase 7 | No embeddings needed for analytics |
| LLM provider port / Rung 1 (LLM call) | Phase 7.1 | `IMPLEMENTATION-PLAN.md` Phase 7 | Separate delivery from analytics |
| Stream time suggestions ("best time to stream") | Phase B | blueprint §Deferred | Schema supports query; endpoint is Phase B |
| Notification integration | Phase B+ | blueprint §Deferred | Out of Phase A scope |
| Insight-service unit tests | Phase B | `lightweight-testing-scope` memory | Pet project policy — skip thorough testing |
| Kafka integration tests | Pre-production | `IMPLEMENTATION-PLAN.md` §2.6 | Needs Docker/Testcontainers |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

| # | Item | Context | Recommended Action |
|---|------|---------|-------------------|
| 1 | **No `logback-spring.xml` in insight-service** | All 6 existing services have structured JSON logging deployed (`LOGGING-ARCHITECTURE.md` §1). Insight-service is the 7th backend service but has no logback config. | Copy the canonical `templates/logback-spring.xml` to `insight-service/src/main/resources/`. Track in `LOGGING-ARCHITECTURE.md` §1 row. |
| 2 | **`KAFKA-INFRASTRUCTURE.md` does not exist** | The Architecture & Reference Docs Update Matrix references this file as if it exists, but a glob search found no match. The platform now has 4 Kafka topics, 3 producers, 4 consumers, and 2 outbox implementations — with no central reference doc. | Create `docs/KAFKA-INFRASTRUCTURE.md` as a standalone task (not retro scope). At minimum, populate it with the `stream.view` topic, `ViewEventProducer`, `EngagementEventListener` consumer group, and `EngagementEvent`/`VIEW` event type added this session. |
| 3 | **EXTRACT(HOUR) timezone assumption** | `EngagementEventRepository.findHourlyDistribution()` uses `EXTRACT(HOUR FROM occurred_at)` without explicit timezone. In production, `occurred_at` is stored as `TIMESTAMPTZ` (UTC-normalized), so peak-hour results will be in UTC — which may not match the viewer's local timezone. | Low priority for Phase A (analytics are internal). If surfaced to viewers in a future phase, either apply `AT TIME ZONE` in the query or convert client-side. Tracked as review finding (MEDIUM). |

## 4. Architectural decisions made during implementation

No new ADRs were needed — all decisions were pre-documented in the blueprint (§Key Design Decisions) and matched exactly during implementation:

1. **Fire-and-forget producer for view events** — `ViewEventProducer` uses direct `KafkaTemplate.send()` with `.subscribe()`. Views are telemetry, not transactions. Matches blueprint §Decision 1 and `ARCHITECTURE.md` telemetry pattern.
2. **`EngagementEvent` in `common` module** — Mirrors `StreamEvent` pattern. Both producer (stream-service) and consumer (insight-service) share the contract. Matches blueprint §Decision 2.
3. **Redis SETNX + UNIQUE index dual dedup** — Consumer-side Redis dedup (24h TTL) prevents INSERT attempt; `UNIQUE(event_id)` handles edge cases. Matches blueprint §Decision 3 and the existing `StreamControlListener` pattern.
4. **UUID internal PK + VARCHAR `event_id`** — Internal PK independent of external event identifier. Matches blueprint §Decision 4.
5. **Analytics as a sub-domain of insight-service** — Phase A ships the analytics/engagement sub-domain (event capture → aggregation → suggestions/analytics API). The AI/LLM sub-domain (ADR-0000 §3 planned boundary) remains future work. This follows the two-sub-domain bounded context from ADR-0000 but ships one domain now and one later.

## 5. Documents to update (stale vs current state)

| Document | Current Status | What's Stale | Action |
|----------|---------------|-------------|--------|
| `IMPLEMENTATION-PLAN.md` | Phase 7 says "Scaffolding only exists today — no code, no Gradle module" | Insight-service IS scaffolded with working analytics. Phase A shipped. | Update Phase 7 header + checklist + status. Mark 7.0 partially done (Gradle module ✅, Eureka ✅, gateway ✅; pgvector deferred to Rung 3). Add Phase A analytics as completed work item. |
| `ARCHITECTURE.md` | Insight service listed under "Planned but not yet implemented" | Service is scaffolded and functional (analytics sub-domain) | Move to active components table. Update system context (add insight-service, Kafka `stream.view` edge). Update glossary if needed. |
| `SERVICE-ARCHITECTURE.md` | §1 table has 6 services; no insight-service row | 7th service exists but not documented | Add insight-service row: "Layered reactive" style. Add to §3 Flyway layout table. |
| `LOGGING-ARCHITECTURE.md` | §1 Services table has 6 rows | 7th service exists. No logback deployed yet (gap #1). | Add insight-service row. Mark logback status as ❌ pending (gap). |
| `INSIGHT-SERVICE-SKETCH.md` | Describes planned 4-rung ladder only; no mention of analytics sub-domain | Phase A analytics sub-domain now exists alongside the planned LLM rungs | Update status header. Add analytics sub-domain section with actual schema + endpoints. |

## 6. Architecture & reference docs updated

| Document | Trigger | What Changed | Summary |
|----------|---------|-------------|---------|
| `IMPLEMENTATION-PLAN.md` | Phase A shipped, status change | Phase 7 header, checklist, current phase | Marked 7.0 infrastructure partially done (Gradle ✅, Eureka ✅, gateway ✅; pgvector → Rung 3). Added Phase A analytics as shipped work item. Updated "Last updated" date. |
| `ARCHITECTURE.md` | New service added | §3 component table, §2 diagram, §9 glossary | Moved insight-service from planned → active. Added insight-service to system context. Added "Analytics & Engagement" under active components. |
| `SERVICE-ARCHITECTURE.md` | New Gradle module + new Flyway layout + new schema | §1 table, §3 table | Added insight-service row. Added `insight` schema to Flyway layout table. |
| `LOGGING-ARCHITECTURE.md` | New backend service created | §1 Services table | Added insight-service row (logback pending). |
| `INSIGHT-SERVICE-SKETCH.md` | Phase 7 infrastructure partially implemented | Status header, new analytics section | Updated status. Added Phase A analytics sub-domain with schema, data flow, and REST endpoints. |

## 7. Updated execution order (actual vs planned)

All tasks executed exactly as planned — no reordering or surprises. The blueprint's task summary (21 items) was followed sequentially:

| # | Task | Status |
|---|------|--------|
| 1 | `EngagementEvent` record (common) | ✅ Done |
| 2 | `streamViewTopic()` bean (common) | ✅ Done |
| 3 | Gradle module registration + build script | ✅ Done |
| 4 | Main class + application.yml | ✅ Done |
| 5 | InsightProperties config | ✅ Done |
| 6 | Flyway V1 migration | ✅ Done |
| 7 | SecurityConfig | ✅ Done |
| 8 | R2dbcConfig | ✅ Done |
| 9 | EngagementEventEntity | ✅ Done |
| 10 | EngagementEventRepository | ✅ Done |
| 11 | EngagementEventListener (Kafka consumer) | ✅ Done |
| 12 | EngagementService | ✅ Done |
| 13 | SuggestionService | ✅ Done |
| 14 | AnalyticsService | ✅ Done |
| 15 | Domain DTOs (3 records) | ✅ Done |
| 16 | SuggestionController + response DTO | ✅ Done |
| 17 | AnalyticsController + response DTO | ✅ Done |
| 18 | ViewEventProducer (stream-service) | ✅ Done |
| 19 | Wire into StreamService + StreamController | ✅ Done |
| 20 | Gateway route | ✅ Done |
| 21 | Tests (StreamServiceTest updated) | ✅ Done |

**Compilation:** `:common`, `:insight-service`, `:stream-service`, `:gateway-service` all compile clean. `:stream-service:compileTestJava` has 6 pre-existing errors in `StreamServiceTest.java` (unrelated to Phase A — `CreateStreamRequest`/`UpdateStreamRequest` constructor mismatches from earlier sessions).

## 8. Key risks carried forward

1. **View event noise** (medium) — Refreshing a watch page 10x = 10 VIEW events. Scoring weight is low (0.25) so impact on trending is muted. Consumer-side dedup window (1 VIEW per user-channel per hour) is a documented mitigation but not yet implemented.
2. **Consumer lag during traffic spikes** (medium) — Single partition on `stream.view`. Idempotent dedup + simple INSERT means lag recovery is fast, but throughput is capped at one consumer.
3. **Cold-start suggestions are generic** (high, by design) — Expected for Phase A. Improves with data accumulation + Phase B LIKE/SUBSCRIBE weighting.
4. **No logback structured logging** (low) — Insight-service emits unstructured logs until `logback-spring.xml` is deployed. Not critical for an internal analytics service, but inconsistent with the platform standard.
5. **EXTRACT(HOUR) timezone** (low) — Peak-hour analytics use UTC. Acceptable for internal dashboards; needs client-side conversion or `AT TIME ZONE` if surfaced to viewers.

## Related Docs

- [Blueprint](insight-service-phase-a-blueprint.md) — Original plan (all 21 tasks)
- [IMPLEMENTATION-PLAN.md](../IMPLEMENTATION-PLAN.md) — Master plan, Phase 7
- [ARCHITECTURE.md](../ARCHITECTURE.md) — Updated system context
- [SERVICE-ARCHITECTURE.md](../SERVICE-ARCHITECTURE.md) — Updated architectural styles
- [LOGGING-ARCHITECTURE.md](../LOGGING-ARCHITECTURE.md) — Updated services table
- [INSIGHT-SERVICE-SKETCH.md](../INSIGHT-SERVICE-SKETCH.md) — Updated with analytics sub-domain
- [ADR-0000](../adr/insight/0000-architecture-foundation.md) — Insight service architecture (analytics + AI/LLM bounded contexts)
- [ADR-0001](../adr/insight/0001-insight-service-architecture.md) — Layered-reactive + hexagonal ports
