# Blueprint: Insight Service Phase A — Event-Driven Analytics

**Date**: 2026-08-06
**Status**: draft
**Deciders**: hieuht, Claude

## Summary

Build the insight-service Gradle module from scratch and ship the analytics sub-domain of Phase A: capture viewer events via Kafka, compute cold-start suggestions and streamer analytics from aggregated engagement data, and expose two REST endpoints. This is the data foundation for all future AI/LLM features (Phases B+) and teaches the event-driven analytics pipeline end-to-end — Kafka produce, consume, dedup, persist, aggregate, and serve.

## Learning Objectives

| Skill | Where you'll implement it |
|-------|---------------------------|
| **Kafka producer (fire-and-forget telemetry)** | `ViewEventProducer.java` in stream-service — `KafkaTemplate.send()` |
| **Kafka consumer (JSON deserialize + dedup)** | `EngagementEventListener.java` in insight-service — mirrors `StreamControlListener` |
| **R2DBC + Flyway schema bootstrap** | `V1__bootstrap_insight_schema.sql` + `EngagementEventRepository` |
| **Persistable entity + create() factory** | `EngagementEventEntity.java` — mirrors `OutboxEvent` pattern |
| **Aggregation SQL (GROUP BY, windowed)** | `SuggestionService.java` and `AnalyticsService.java` — R2DBC `DatabaseClient` queries |
| **REST API design (suggestion + analytics)** | `SuggestionController.java` + `AnalyticsController.java` |
| **New Gradle module registration** | `settings.gradle.kts` + `build.gradle.kts` |

## Patterns to Mirror

| Category | Source file | Pattern to copy |
|----------|-------------|-----------------|
| Module registration | `settings.gradle.kts:17-26` | `include("insight-service")` in comma-separated list |
| Build script (dependencies) | `stream-service/build.gradle.kts` | Java 21 toolchain, Spring Boot 3.3.6, WebFlux, R2DBC, Kafka, Flyway, Eureka, Lombok |
| Kafka topic definition | `common/.../KafkaTopicConfig.java:33-39` | `@Bean NewTopic` with `TopicBuilder` |
| Shared event record | `common/.../StreamEvent.java` | Immutable `record` with static factory methods |
| Main application class | `stream-service/.../StreamApplication.java` | `@SpringBootApplication` + `@EnableKafka` |
| Security config (JWT) | `stream-service/.../config/SecurityConfig.java` | `SecurityWebFilterChain` with `oauth2ResourceServer` + `Structured401AuthenticationEntryPoint` |
| R2DBC config | `stream-service/.../config/R2dbcConfig.java` | `TransactionalOperator` bean |
| Entity (Persistable<UUID>) | `stream-service/.../entity/OutboxEvent.java` | `@Table` + `@Id UUID` + `@Transient boolean isNew` |
| Repository (R2DBC) | `stream-service/.../repository/StreamSessionRepository.java` | `ReactiveCrudRepository` + `@Query` text blocks |
| Kafka consumer (listener) | `notification-service/.../StreamControlListener.java` | `@KafkaListener` + JSON deserialization + Redis SETNX dedup + `switch` routing + `blockOptional(10s)` |
| application.yml (config) | `notification-service/src/main/resources/application.yml` | `server.port`, `spring.application.name`, `spring.webflux.base-path`, `spring.flyway.*`, `spring.r2dbc.*`, `spring.kafka.*`, `eureka.*`, `streaming.*` |
| Flyway V1 migration | `stream-service/.../V1__bootstrap_stream_schema.sql` | `CREATE SCHEMA IF NOT EXISTS` + `CREATE TABLE IF NOT EXISTS` with schema prefix |
| Gateway route | `gateway-service/src/main/resources/application.yml` | `- id:` with `Path=` predicate and `uri=lb://` |

## Architecture Diagram

```
                              ┌──────────────────────────────────────────┐
                              │         PostgreSQL 16                    │
                              │  ┌─────────────────────────────────────┐ │
                              │  │   insight.engagement_event          │ │
                              │  │   (event_id, stream_id, actor,      │ │
                              │  │    target_type, target_id,          │ │
                              │  │    category, occurred_at)           │ │
                              │  └─────────────────────────────────────┘ │
                              └──────────────────────────────────────────┘
                                        ▲ persist (R2DBC INSERT)
                                        │
┌──────────┐  GET /v1/streams/{id}   ┌───────────────────────────────┐
│ Browser  │ ──────────────────────► │  stream-service               │
│ (viewer) │                         │                               │
│          │ ◄── JSON response       │  getStream() ──► Redis view   │
│          │                         │    tracking (existing)        │
│          │                         │                               │
│          │                         │  ViewEventProducer            │
│          │                         │    .send(viewEvent)           │
│          │                         │    ──► fire-and-forget        │
└──────────┘                         └───────────┬───────────────────┘
                                                 │ Kafka: stream.view
                                                 ▼
                              ┌───────────────────────────────────────┐
                              │  Kafka (KRaft, 4.1.2)                 │
                              │  topic: stream.view                   │
                              │  partitions: 1, replicas: 1           │
                              └───────────┬───────────────────────────┘
                                          │ consume
                                          ▼
                              ┌───────────────────────────────────────────────┐
                              │  insight-service (NEW)                        │
                              │  port: 0 (Eureka), base-path: /api/insights   │
                              │                                               │
                              │  ┌─ infrastructure/messaging/ ──────────────┐ │
                              │  │  EngagementEventListener                 │ │
                              │  │    @KafkaListener("stream.view")         │ │
                              │  │    → JSON deserialize EngagementEvent    │ │
                              │  │    → Redis SETNX dedup (eventId, 24h)    │ │
                              │  │    → EngagementService.persist()         │ │
                              │  └──────────────────────────────────────────┘ │
                              │                    │                          │
                              │                    ▼                          │
                              │  ┌─ application/ ───────────────────────────┐ │
                              │  │  EngagementService                       │ │
                              │  │    → EngagementEventEntity.create()      │ │
                              │  │    → EngagementEventRepository.save()    │ │
                              │  ├──────────────────────────────────────────┤ │
                              │  │  SuggestionService                       │ │
                              │  │    → cold-start: trending channels       │ │
                              │  │      (view count, last 7 days)           │ │
                              │  │    → cold-start: trending categories     │ │
                              │  ├──────────────────────────────────────────┤ │
                              │  │  AnalyticsService                        │ │
                              │  │    → view count, peak hour, hourly dist  │ │
                              │  └──────────────────────────────────────────┘ │
                              │                    │                          │
                              │                    ▼                          │
                              │  ┌─ api/ ───────────────────────────────────┐ │
                              │  │  GET /v1/suggestions?subject={sub}       │ │
                              │  │  GET /v1/analytics/streams/{streamId}    │ │
                              │  └──────────────────────────────────────────┘ │
                              └───────────────────────────────────────────────┘
                                        ▲             │
                                        │   REST      │ responses
                                        │             ▼
                              ┌───────────────────────────────────────┐
                              │  gateway-service                      │
                              │  route: /api/insights/** → insight    │
                              └───────────────────────────────────────┘
                                        ▲
                                        │
                              ┌─────────┴──────────┐
                              │  Angular 19 SPA    │
                              │  (browse page,     │
                              │   analytics page)  │
                              └────────────────────┘
```

## New Files

### Common Module

| File | Purpose |
|------|---------|
| `main/source/backend/common/src/main/java/com/streaming/common/messaging/EngagementEvent.java` | Immutable record: eventId, eventType, streamId, actorSubject, targetType, targetId, category, occurredAt — with static factory `viewed()` |

### insight-service — Module Scaffold (3 files)

| File | Purpose |
|------|---------|
| `main/source/backend/insight-service/build.gradle.kts` | Gradle build mirroring `stream-service/build.gradle.kts` with all platform deps |
| `main/source/backend/insight-service/src/main/java/com/streaming/insight/InsightApplication.java` | `@SpringBootApplication` + `@EnableKafka` + `@EnableConfigurationProperties` |
| `main/source/backend/insight-service/src/main/resources/application.yml` | Server port 0, Eureka, Kafka consumer, R2DBC/Flyway for `insight` schema, Redis, insight.* config |

### insight-service — Database (1 file)

| File | Purpose |
|------|---------|
| `main/source/backend/insight-service/src/main/resources/db/migration/V1__bootstrap_insight_schema.sql` | `CREATE SCHEMA insight` + `CREATE TABLE insight.engagement_event` + 4 indexes |

### insight-service — Config (3 files)

| File | Purpose |
|------|---------|
| `.../config/SecurityConfig.java` | JWT security with `Structured401AuthenticationEntryPoint` |
| `.../config/InsightProperties.java` | `@ConfigurationProperties("insight")` record: scoring weights, suggestion limits, recency window |
| `.../config/R2dbcConfig.java` | `TransactionalOperator` bean |

### insight-service — Domain (4 files)

| File | Purpose |
|------|---------|
| `.../domain/model/EngagementEventEntity.java` | `@Table("engagement_event")`, `Persistable<UUID>`, `@Transient isNew`, static `create()` factory |
| `.../domain/model/ChannelSuggestion.java` | Record: `String channelUsername, long viewCount, double score` |
| `.../domain/model/CategorySuggestion.java` | Record: `String category, long viewCount, double score` |
| `.../domain/model/StreamAnalytics.java` | Record: `UUID streamId, long totalViews, long uniqueViewers, int peakHour, ...` |

### insight-service — Infrastructure (2 files)

| File | Purpose |
|------|---------|
| `.../infrastructure/persistence/EngagementEventRepository.java` | `ReactiveCrudRepository` + 4 `@Query` methods with projection records |
| `.../infrastructure/messaging/EngagementEventListener.java` | `@KafkaListener("stream.view")` — mirrors `StreamControlListener` exactly |

### insight-service — Application (3 files)

| File | Purpose |
|------|---------|
| `.../application/EngagementService.java` | `persistView(EngagementEvent)` — entity conversion + save |
| `.../application/SuggestionService.java` | Cold-start trending channels + categories (configurable recency window) |
| `.../application/AnalyticsService.java` | Stream stats: view count, unique viewers, peak hour, hourly distribution |

### insight-service — API (4 files)

| File | Purpose |
|------|---------|
| `.../api/SuggestionController.java` | `GET /v1/suggestions` — zips channel + category suggestions |
| `.../api/AnalyticsController.java` | `GET /v1/analytics/streams/{streamId}` — post-stream dashboard |
| `.../api/dto/SuggestionResponse.java` | Record: `List<ChannelSuggestion>, List<CategorySuggestion>` |
| `.../api/dto/StreamAnalyticsResponse.java` | Record: `UUID streamId, long totalViews, ...` with `from()` mapper |

### stream-service — New Producer (1 file)

| File | Purpose |
|------|---------|
| `.../stream/service/ViewEventProducer.java` | Fire-and-forget `KafkaTemplate` producer — serializes `EngagementEvent` to JSON, sends to `stream.view` |

## Modified Files

| File | Change | Why |
|------|--------|-----|
| `settings.gradle.kts` | Add `"insight-service"` to `include(...)` | Register new Gradle subproject |
| `common/.../KafkaTopicConfig.java` | Add `streamViewTopic()` bean (1 partition, 1 replica) | Declare `stream.view` topic |
| `stream-service/.../StreamService.java` | (a) Inject `ViewEventProducer`. (b) In `getStream()`, call `sendViewEvent()` after `trackViewEvent()` for non-owner views. (c) In `getChannelHome()`, emit view event for non-owner channel views (needs `Jwt` parameter added). | Emit engagement telemetry |
| `stream-service/.../StreamController.java` | Pass `Jwt` to updated `getChannelHome()` method | Forward auth context |
| `gateway-service/.../application.yml` | Add `insight-service` route: `Path=/api/insights/**` → `lb://insight-service` | Route traffic to new service |

## Key Design Decisions

### 1. Fire-and-forget producer (no outbox) for view events

**Decision**: Use direct `KafkaTemplate.send()` with `.subscribe()` for view events rather than the outbox pattern used by `StreamEvent`.

**Rationale**: Per ADR-0000, views are telemetry, not transactions. "A missed event loses one data point; a blocked REST call loses a user action." The outbox is for events where delivery must be guaranteed (stream lifecycle transitions). View events are lossy by design — the REST response returns before Kafka acks.

### 2. EngagementEvent as common module record (not insight-service local)

**Decision**: `EngagementEvent` lives in `common/src/main/java/.../messaging/` alongside `StreamEvent`.

**Rationale**: Both stream-service (producer) and insight-service (consumer) need the class. This mirrors the `StreamEvent` pattern exactly. No duplication, single source of truth.

### 3. Redis SETNX dedup in consumer (not database-level only)

**Decision**: Consumer-side Redis dedup (SETNX with 24h TTL) PLUS a UNIQUE constraint on `event_id`.

**Rationale**: Mirrors `StreamControlListener` pattern exactly. Redis check prevents the INSERT attempt entirely; the UNIQUE index is defense-in-depth for edge cases (Redis down, restarted, etc.).

### 4. Phase A entity uses UUID internal PK + VARCHAR event_id

**Decision**: `engagement_event.id` is a generated UUID (internal PK). `engagement_event.event_id` is the Kafka event identifier (dedup + tracing).

**Rationale**: Internal PKs should be independent of external identifiers. If the event format changes (e.g., composite event ID in Phase B), the internal PK stays stable. The UNIQUE index on `event_id` handles dedup at DB level.

## Data Flow

```
Step 1: Viewer navigates to watch page
  Browser → GET /api/streams/v1/streams/{id}
    → gateway-service → stream-service.StreamController.get()
      → StreamService.getStream(id, jwt, viewerId)
        → authorization checks (PBAC)
        → trackViewEvent(id, viewerId)           # Redis: per-user-per-stream dedup, TTL 24h
        → viewEventProducer.sendViewEvent(...)   # Kafka: fire-and-forget
        → return StreamResponse

Step 2: EngagementEvent hits Kafka topic stream.view
  Kafka broker (KRaft, port 19091)
    Topic: stream.view, partition: 0

Step 3: insight-service consumes the event
  EngagementEventListener.onEngagementEvent(payload)
    → JSON deserialize → EngagementEvent record
    → Redis SETNX "dedup:stream.view:insight-service:{eventId}" (TTL 24h)
      ├─ acquired=true  → EngagementService.persistView(event)
      └─ acquired=false → skip (duplicate, log debug)

Step 4: Persistence
  EngagementService.persistView(event)
    → EngagementEventEntity.create(event)
    → repository.save(entity)
    → INSERT INTO insight.engagement_event (...)

Step 5: API serves aggregated data
  GET /api/insights/v1/suggestions
    → findTrendingChannels(windowHours, limit)   # COUNT(*) GROUP BY target_id
    → findTrendingCategories(windowHours, limit)  # COUNT(*) GROUP BY category
    → SuggestionResponse

  GET /api/insights/v1/analytics/streams/{streamId}
    → findStreamViewStats(streamId)               # COUNT(*), COUNT(DISTINCT)
    → findHourlyDistribution(streamId)            # EXTRACT(HOUR), GROUP BY
    → StreamAnalyticsResponse
```

## Java Class Contracts (Key Files)

### EngagementEvent (common)

```java
public record EngagementEvent(
        String eventId,         // UUID — unique per event (consumer dedup)
        String eventType,       // "VIEW" (Phase A), "LIKE"/"SUBSCRIBE" (Phase B)
        String streamId,        // UUID of the viewed stream
        String actorSubject,    // JWT sub of the viewer
        String targetType,      // "CHANNEL" or "CATEGORY"
        String targetId,        // broadcaster_username (CHANNEL) or category name
        String category,        // category at time of view
        OffsetDateTime occurredAt
) {
    public static EngagementEvent viewed(UUID streamId, String actor,
                                         String targetId, String category) { ... }
}
```

### V1__bootstrap_insight_schema.sql

```sql
CREATE SCHEMA IF NOT EXISTS insight;

CREATE TABLE IF NOT EXISTS insight.engagement_event (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id       VARCHAR(64) NOT NULL,
    event_type     VARCHAR(32) NOT NULL,
    stream_id      UUID NOT NULL,
    actor_subject  VARCHAR(128) NOT NULL,
    target_type    VARCHAR(32) NOT NULL DEFAULT 'CHANNEL',
    target_id      VARCHAR(128) NOT NULL,
    category       VARCHAR(128),
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_engagement_target_time
    ON insight.engagement_event (target_type, target_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_engagement_actor_time
    ON insight.engagement_event (actor_subject, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_engagement_stream_time
    ON insight.engagement_event (stream_id, occurred_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS ix_engagement_event_id
    ON insight.engagement_event (event_id);
```

### EngagementEventEntity (insight-service domain)

```java
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
@Table(name = "engagement_event")
public class EngagementEventEntity implements Persistable<UUID> {
    @Id private UUID id;
    @Transient private boolean isNew;
    @Column("event_id") private String eventId;
    @Column("event_type") private String eventType;
    @Column("stream_id") private UUID streamId;
    @Column("actor_subject") private String actorSubject;
    @Column("target_type") private String targetType;
    @Column("target_id") private String targetId;
    @Column("category") private String category;
    @Column("occurred_at") private OffsetDateTime occurredAt;

    public static EngagementEventEntity create(EngagementEvent event) { ... }
}
```

### EngagementEventRepository (insight-service infrastructure)

```java
public interface EngagementEventRepository
        extends ReactiveCrudRepository<EngagementEventEntity, UUID> {

    @Query("""
            SELECT target_id, COUNT(*) as view_count
            FROM insight.engagement_event
            WHERE target_type = 'CHANNEL' AND event_type = 'VIEW'
              AND occurred_at > CURRENT_TIMESTAMP - (:h || ' hours')::INTERVAL
            GROUP BY target_id ORDER BY view_count DESC LIMIT :limit""")
    Flux<ChannelViewCount> findTrendingChannels(int h, int limit);

    @Query("""
            SELECT category, COUNT(*) as view_count
            FROM insight.engagement_event
            WHERE category IS NOT NULL AND event_type = 'VIEW'
              AND occurred_at > CURRENT_TIMESTAMP - (:h || ' hours')::INTERVAL
            GROUP BY category ORDER BY view_count DESC LIMIT :limit""")
    Flux<CategoryViewCount> findTrendingCategories(int h, int limit);

    @Query("""
            SELECT COUNT(*) as total_views, COUNT(DISTINCT actor_subject) as unique_viewers
            FROM insight.engagement_event
            WHERE stream_id = :streamId AND event_type = 'VIEW'""")
    Mono<StreamViewStats> findStreamViewStats(UUID streamId);

    @Query("""
            SELECT EXTRACT(HOUR FROM occurred_at) as hour, COUNT(*) as view_count
            FROM insight.engagement_event
            WHERE stream_id = :streamId AND event_type = 'VIEW'
            GROUP BY EXTRACT(HOUR FROM occurred_at) ORDER BY hour""")
    Flux<HourlyBucket> findHourlyDistribution(UUID streamId);

    record ChannelViewCount(String targetId, Long viewCount) {}
    record CategoryViewCount(String category, Long viewCount) {}
    record StreamViewStats(Long totalViews, Long uniqueViewers) {}
    record HourlyBucket(Integer hour, Long viewCount) {}
}
```

### EngagementEventListener (insight-service messaging)

```java
@Component
public class EngagementEventListener {
    // Constructor-injected: ObjectMapper, ReactiveRedisTemplate, EngagementService

    @KafkaListener(topics = "${insight.stream-view-topic:stream.view}",
                   groupId = "${spring.kafka.consumer.group-id}")
    public void onEngagementEvent(String payload) {
        EngagementEvent event = objectMapper.readValue(payload, EngagementEvent.class);
        String dedupKey = "dedup:" + topic + ":" + consumerGroupId + ":" + event.eventId();

        redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", Duration.ofHours(24))
                .flatMap(acquired -> acquired ? handle(event) : Mono.empty())
                .onErrorResume(ex -> handle(event))
                .subscribeOn(Schedulers.boundedElastic())
                .blockOptional(Duration.ofSeconds(10));
    }

    private Mono<Void> handle(EngagementEvent event) {
        return switch (event.eventType()) {
            case "VIEW" -> engagementService.persistView(event);
            default -> Mono.empty();
        };
    }
}
```

### ViewEventProducer (stream-service producer)

```java
@Service
public class ViewEventProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    @Value("${streaming.kafka.topic.stream-view:stream.view}") private String topic;

    /**
     * Fire-and-forget. Serializes EngagementEvent to JSON, sends to Kafka
     * on a boundedElastic thread. The REST response returns before Kafka acks.
     * Logs and drops on failure (views are telemetry, not transactions).
     */
    public void sendViewEvent(UUID streamId, String viewerSubject,
                              StreamSessionEntity entity) {
        EngagementEvent event = EngagementEvent.viewed(
                streamId, viewerSubject,
                entity.getBroadcasterUsername(), entity.getCategory());
        String payload = objectMapper.writeValueAsString(event);

        Mono.fromFuture(kafkaTemplate.send(topic, event.streamId(), payload))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        result -> log.trace("View event sent: eventId={}", event.eventId()),
                        error -> log.warn("Failed to send view event: streamId={}: {}",
                                streamId, error.getMessage()));
    }
}
```

### REST API Contracts

```
GET /api/insights/v1/suggestions
Authorization: Bearer {jwt}
Response 200:
{
  "channels": [
    { "channelUsername": "streamer1", "viewCount": 150, "score": 37.5 },
    { "channelUsername": "streamer2", "viewCount": 89, "score": 22.25 }
  ],
  "categories": [
    { "category": "gaming", "viewCount": 200, "score": 50.0 },
    { "category": "music", "viewCount": 75, "score": 18.75 }
  ]
}

GET /api/insights/v1/analytics/streams/{streamId}
Authorization: Bearer {jwt}
Response 200:
{
  "streamId": "550e8400-e29b-41d4-a716-446655440000",
  "totalViews": 150,
  "uniqueViewers": 89,
  "peakHour": 20,
  "category": "gaming",
  "hourlyDistribution": { "14": 25, "15": 30, "20": 45 }
}
```

## Config Properties Reference

```yaml
# ── insight-service application.yml (new properties) ────────────────────
insight:
  scoring:
    view-weight: 0.25
    like-weight: 0.75          # (Phase B)
    subscribe-weight: 1.50     # (Phase B)
  suggestions:
    channel-limit: 10
    category-limit: 5
    recency-window: P7D
  stream-view-topic: stream.view

# ── stream-service (no new application.yml properties needed; topic
#     name injected via @Value with env override) ────────────────────────
```

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| **View event noise** (refreshing 10x = 10 events) | High | Scoring weight is deliberately low (0.25). If a problem, add consumer-side dedup window (1 VIEW per user-channel per hour) without changing schema |
| **Kafka producer failure blocks REST response** | Low | Fire-and-forget with `.subscribe()` — response returns before Kafka ack; failed sends are logged/dropped |
| **Consumer lag during traffic spikes** | Medium | Single partition. Mitigated by: idempotent dedup, simple INSERT. Scale to more partitions + consumers if needed |
| **Cold-start suggestions are generic** | High (by design) | Expected for Phase A. Improves with data accumulation and Phase B LIKE/SUBSCRIBE events |
| **Duplicate events from at-least-once delivery** | Medium | Redis SETNX dedup (24h TTL) + UNIQUE index on event_id |
| **insight-service DB connection pool** | Low | Same R2DBC defaults as other services (pool size 10); simple INSERTs + indexed reads |

## Validation

```bash
# 1. Build
./gradlew :insight-service:compileJava :stream-service:compileJava :common:compileJava
./gradlew :insight-service:test :stream-service:test

# 2. Start full stack
docker compose up -d

# 3. Verify Eureka registration
curl -s http://localhost:8761/eureka/apps | grep -A5 insight-service

# 4. Verify Kafka topic
docker exec -it $(docker ps -qf "name=kafka") kafka-topics.sh \
  --bootstrap-server localhost:19091 --list
# Expected: stream.view in list

# 5. Verify gateway route
curl -s http://localhost:8080/actuator/gateway/routes | jq '.[] | select(.route_id=="insight-service")'

# 6. Simulate view event via Kafka console
docker exec -it $(docker ps -qf "name=kafka") kafka-console-producer.sh \
  --bootstrap-server localhost:19091 --topic stream.view
# Paste valid EngagementEvent JSON

# 7. Verify persistence
docker exec -it $(docker ps -qf "name=postgres") psql \
  -U postgres -d streaming-platform \
  -c "SELECT * FROM insight.engagement_event;"

# 8. Test REST endpoints (authenticate first)
# curl -H "Authorization: Bearer $JWT" http://localhost:8080/api/insights/v1/suggestions | jq
# curl -H "Authorization: Bearer $JWT" http://localhost:8080/api/insights/v1/analytics/streams/{id} | jq

# 9. End-to-end: view a stream in browser → check insight.engagement_event has new row
```

## Task Summary

| # | Task | Module | Type |
|---|------|--------|------|
| 1 | Create `EngagementEvent` record | common | New file |
| 2 | Add `streamViewTopic()` bean to `KafkaTopicConfig` | common | Modify file |
| 3 | Register `insight-service` in `settings.gradle.kts` + `build.gradle.kts` | root | Modify + New |
| 4 | Create `InsightApplication.java` + `application.yml` | insight-service | New files |
| 5 | Create `InsightProperties` config record | insight-service | New file |
| 6 | Create Flyway V1 migration | insight-service | New file |
| 7 | Create `SecurityConfig` | insight-service | New file |
| 8 | Create `R2dbcConfig` | insight-service | New file |
| 9 | Create `EngagementEventEntity` | insight-service | New file |
| 10 | Create `EngagementEventRepository` | insight-service | New file |
| 11 | Create `EngagementEventListener` (Kafka consumer) | insight-service | New file |
| 12 | Create `EngagementService` | insight-service | New file |
| 13 | Create `SuggestionService` | insight-service | New file |
| 14 | Create `AnalyticsService` | insight-service | New file |
| 15 | Create domain DTOs (`ChannelSuggestion`, `CategorySuggestion`, `StreamAnalytics`) | insight-service | New files |
| 16 | Create `SuggestionController` + `SuggestionResponse` DTO | insight-service | New files |
| 17 | Create `AnalyticsController` + `StreamAnalyticsResponse` DTO | insight-service | New files |
| 18 | Create `ViewEventProducer` | stream-service | New file |
| 19 | Wire `ViewEventProducer` into `StreamService` + `StreamController` | stream-service | Modify files |
| 20 | Add gateway route for insight-service | gateway-service | Modify file |
| 21 | Write unit + integration tests (7 test files) | common, insight, stream | New files |

## Deferred to Phase B

| Item | Reason |
|------|--------|
| LIKE / SUBSCRIBE event types | Require channel-service (future Phase) |
| Personalized suggestions (user affinity vector) | Requires LIKE/SUBSCRIBE data for meaningful weights |
| pgvector extension | Rung 3 of AI/LLM ladder — no embeddings needed for analytics |
| LLM provider port / LlmPort | Rung 1 of AI/LLM ladder — separate delivery |
| Stream time suggestions ("best time to stream") | Phase A schema supports the query, but the endpoint is Phase B |
| Notification integration | notification-service integration, out of Phase A scope |
