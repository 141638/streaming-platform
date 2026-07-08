# Implementation Plan

**Last updated:** 2026-07-08
**Current phase:** 2 — Stream Lifecycle (2.4 complete) / 3 — Real-time Chat (partial)

## End Goal

A dual-plane streaming platform:

- **Control plane:** identities, stream metadata lifecycle, chat, notifications, orchestration signals — synchronous REST APIs + event-driven integration (Kafka) + shared state (PostgreSQL, Redis)
- **Data plane:** real-time media ingestion, packaging, and delivery (OBS → SRS → HLS-capable playback)

```
Streamer signs in → creates stream → gets publish key → OBS publishes to SRS
→ SRS validates key → stream goes live → viewers watch HLS + chat in real-time
→ notifications fire on stream events
```

---

## Phase Overview

```
Phase 1 ──► Phase 2 ──► Phase 3 ──► Phase 4 ──► Phase 5 ──► Phase 6 ──► Phase 7
(Auth)      (Stream)    (Chat)      (Viewer)    (Notify)    (Harden)    (AI/LLM)
  ✅          ⚡           ⚡           ○           ○           ⚡           ○
```

| Phase | Status | Goal | Third-Party Services |
|-------|--------|------|---------------------|
| [1 — Auth & Foundation](#phase-1--auth--foundation) | ✅ Done | Login, token rotation, PBAC JWT, gateway, frontend auth | PostgreSQL |
| [2 — Stream Lifecycle](#phase-2--stream-lifecycle) | ⚡ Current | Stream CRUD with PBAC, state machine, SRS webhook, frontend dashboard | PostgreSQL, Kafka |
| [3 — Real-time Chat](#phase-3--real-time-chat) | ⚡ Current | PG-backed chat with Redis ZSET cache-aside, room lifecycle from stream events | PostgreSQL, **Redis** |
| [4 — Viewer Experience](#phase-4--viewer-experience) | ○ Planned | Stream discovery, HLS player, embedded chat, viewer presence | **SRS** |
| [5 — Notifications](#phase-5--notifications) | ○ Planned | Email notifications, subscription management, Kafka-driven dispatch | Kafka, SMTP |
| [6 — Production Hardening](#phase-6--production-hardening) | ⚡ In Progress | Idempotency, shared pbac-common, rate limiting, WebSocket, observability. Redis infrastructure hardened, structured logging done, refresh tokens migrated to Redis. | — |
| [7 — AI / LLM Layer](#phase-7--ai--llm-layer-insight-service-) | ○ Planned (final) | `insight-service`: LLM summaries, moderation, semantic search, RAG — built in a 4-rung capability ladder. **Scaffolding only so far** (ADRs + sketch). | LLM provider, **pgvector** |

---

## Third-Party Service Lifecycle Convention

Every phase that depends on a Docker-based service follows this pattern:

| Step | Name | Description |
|------|------|-------------|
| 1 | **Define** | Docker Compose service entry — image tag, port mapping, health check, volume (if persistence needed), env vars |
| 2 | **Provision** | `docker compose up -d <service>`, wait for health check to pass |
| 3 | **Connect** | Application config (env vars → `application.yml`), verify connectivity with a ping or health endpoint |
| 4 | **Test** | Integration tests against a real instance (Testcontainers or dedicated compose profile) — verify hot path, cold path, fallback, and edge cases |
| 5 | **Verify** | End-to-end smoke test: write data through the service → read it back → confirm it round-trips correctly |

> **Rule:** A phase is NOT done until steps 1–5 are complete for every third-party service it introduces. "The Docker image exists on Docker Hub" or "there's an env file" does not count — the service must be defined in a compose file, provisioned, connected, and tested.

---

## Phase 1 — Auth & Foundation ✅

**Status:** Complete

### Deliverables

| # | Item | Artifacts |
|---|------|-----------|
| 1.1 | Auth service with PBAC JWT issuance | `AuthController`, `AccessTokenIssuanceService`, PBAC claims (`ent`, `pv`, `ver`, `attr`) |
| 1.2 | Refresh token rotation with family-based replay detection | `RefreshTokenService`, `OpaqueTokenHasher`, `PESSIMISTIC_WRITE` lock |
| 1.3 | HttpOnly cookie for refresh token (web) + body fallback (mobile) | `CookieService`, `JwtIssuerProperties` |
| 1.4 | Gateway JWT validation + distinguishable 401 error codes | `CustomServerAuthenticationEntryPoint` (`token_expired` vs `invalid_token`) |
| 1.5 | WWW-Authenticate suppression (browser native login dialog fix) | `SecurityConfig.exceptionHandling().authenticationEntryPoint()` |
| 1.6 | Frontend login / logout / forgot password / password reset flows | `LoginPage`, `ForgotPasswordPage`, `PasswordResetPage` |
| 1.7 | Auth interceptor with RxJS single-flight token refresh | `auth.interceptor.ts`, `AuthService.refresh()` |
| 1.8 | Route guards (auth + guest) with `CanMatch` | `auth.guard.ts`, `guest.guard.ts` |
| 1.9 | App shell with toolbar and router outlet | `AppShellComponent`, `HomePage` (placeholder) |
| 1.10 | Eureka discovery, gateway routing to all services | `discovery-service`, gateway `application.yml` routes |
| 1.11 | Docker Compose infrastructure (PostgreSQL, Redis, Kafka, SRS, Discovery) | ⚠️ **Partial** — Kafka, Redis, and Discovery have compose files; PostgreSQL and SRS are env-file-only (see [Infrastructure Status](#infrastructure-status)) |

### Reference Docs

- [ARCHITECTURE.md](ARCHITECTURE.md)
- [SERVICE-ARCHITECTURE.md](SERVICE-ARCHITECTURE.md)
- [PBAC-AUTHORIZATION.md](PBAC-AUTHORIZATION.md)
- [REFRESH-TOKEN-ROTATION.md](REFRESH-TOKEN-ROTATION.md)
- [RXJS-SINGLE-FLIGHT-PATTERN.md](RXJS-SINGLE-FLIGHT-PATTERN.md)
- [AUTH-INTERCEPTOR-PATTERN.md](AUTH-INTERCEPTOR-PATTERN.md)
- [IDEMPOTENCY-PATTERN.md](IDEMPOTENCY-PATTERN.md)

---

## Infrastructure Status

As of 2026-07-08, the root `compose.yaml` (Phase 1.11) has **not been created**. The following third-party services are used across phases:

| Service | Phase | Compose Defined? | Env File? | Health Check? | Volume? |
|---------|-------|------------------|-----------|---------------|---------|
| PostgreSQL | 1–6 | ❌ | ✅ `main/env/postgres.env` | ❌ | ❌ |
| Kafka | 2–5 | ✅ `main/docker/kafka/` | ✅ `main/env/kafka.env` | ❌ | ✅ (broker data) |
| Redis | 3 | ✅ `main/docker/redis/` | ✅ `main/env/redis.env` | ✅ `redis-cli ping` | ❌ (disposable per ADR-0002) |
| SRS | 4 | ❌ | ❌ (in `spring.env.template`) | ❌ | ❌ |
| Discovery (Eureka) | 1–6 | ✅ `main/docker/discovery/` | ✅ `main/env/discovery.env` | ✅ `curl /actuator/health` | ❌ |

Each phase below now includes an **infrastructure setup** item (`.0`) that must be completed before the phase is considered done. These items collectively drive the creation of the root `compose.yaml`.

---

## Phase 2 — Stream Lifecycle ⚡

**Status:** Active — PBAC (2.2 ✅), Kafka (2.0 ✅), state machine (2.3 ✅), categories/tags (2.3 ✅), SRS integration + publish tokens (2.4 ✅) complete. Frontend dashboard (2.5) and Kafka testing (2.6) next.

**Goal:** A streamer can create, configure, start, and end a stream. End-to-end: login → create stream → get publish URL → paste into OBS → OBS starts → SRS webhook validates → stream auto-goes-live → viewers watch via direct HLS.

### Work Items

#### 2.0 — Infrastructure: Kafka Connectivity

**Why:** The stream service depends on Kafka for publishing stream lifecycle events (`STREAM_STARTED`, `STREAM_ENDED`). Kafka compose files exist (`main/docker/kafka/`) but connectivity has not been verified from the stream service. Additionally, health checks are missing on the Kafka broker containers.

**What:**
- Add health check to Kafka broker compose services (`KAFKA_CFG_HEALTH_CHECK_TOPIC` or `nc -z localhost 9092`)
- Verify stream-service can connect to Kafka — check `spring.kafka.bootstrap-servers` resolves
- Verify topic auto-creation works (produce a test message, verify it appears)
- Document startup order: Kafka must be healthy before stream-service starts

**Files:**
- `main/docker/kafka/docker-compose.yml` (add health checks)
- `main/docker/kafka/single-broker/docker-compose.yaml` (add health checks)
- `main/source/backend/stream-service/src/main/resources/application.yml` (verify Kafka config)

**Validate:** `docker compose -f main/docker/kafka/single-broker/docker-compose.yaml up -d` → brokers healthy → stream-service connects and can produce to a test topic

---

#### 2.1 Gateway Route Path Rewriting

**Why:** Gateway routes `/api/streams/**` to stream-service, but stream-service controllers are mapped to `/v1/...` without the `/api/streams` prefix. No request can reach the stream service through the gateway.

**What:**
- Add `RewritePath` or `StripPrefix` filters to gateway route configuration
- Apply consistently across all service routes (`/api/auth/**`, `/api/streams/**`, `/api/chat/**`, `/api/notifications/**`)

**Files:** `gateway-service/src/main/resources/application.yml`

**Validate:** `curl http://localhost:8080/api/streams/v1/ping` → reaches stream service

---

#### 2.2 PBAC Enforcement in Stream Service ✅

**Why:** Current endpoints allow any authenticated user to read/modify/delete any other user's stream. User A can delete user B's stream. This is the largest security gap in the platform.

**What:**
- Build `EntitlementMatcher` — parses JWT `ent` claim, resolves resource ownership (`sub` → `broadcaster_subject`), evaluates policy statements
- Add `@PreAuthorize` or custom `@RequireEntitlement` annotation to stream controller endpoints
- Map HTTP methods to PBAC actions: `POST /streams` → `create`, `GET /streams/{id}` → `read`, `PATCH` → `update`, `DELETE` → `delete`, `POST .../publish-key` → `issue_key`
- Use `ReactiveSecurityContextHolder` to extract JWT in service layer

**Files:**
- `stream-service/src/main/java/com/streaming/stream/security/EntitlementMatcher.java` (new)
- `stream-service/src/main/java/com/streaming/stream/security/RequireEntitlement.java` (new annotation)
- `stream-service/src/main/java/com/streaming/stream/config/SecurityConfig.java` (enable method security)
- `stream-service/src/main/java/com/streaming/stream/api/StreamController.java` (add annotations)

**Validate:** Integration test — user A's token cannot read/modify/delete user B's stream

**Reference:** [PBAC-AUTHORIZATION.md §6](PBAC-AUTHORIZATION.md) — enforcement matrix per service

---

#### 2.3 Stream State Machine ✅

**Why:** Stream lifecycle was a string field with no transition validation.

**What was implemented:**
- State machine via `StreamSessionEntity` domain methods: `transitionTo()`, `goLive()`, `end()`, `cancel()`
- Transition map: DRAFT → LIVE | CANCELLED, LIVE → ENDED, ENDED/CANCELLED → terminal
- SCHEDULED is a creation-time-only state (terminal except for go-live action — see 2.4)
- `@Version` optimistic locking on all transitions
- One-live-stream-per-broadcaster: service-layer check + partial unique index
- PBAC `LIFECYCLE` action on all transition endpoints
- Kafka events: `STREAM_CREATED`, `STREAM_SCHEDULED`, `STREAM_STARTED`, `STREAM_ENDED`, `STREAM_CANCELLED`
- Lifecycle endpoints: `POST /start`, `/end`, `/cancel`
- `POST /schedule` removed in revision — SCHEDULED is creation-only

**What was removed (Phase 2.4 revision):**
- `DRAFT → SCHEDULED` generic transition — schedule only at creation time
- `SCHEDULED → LIVE`, `SCHEDULED → CANCELLED` — SCHEDULED is terminal
- `entity.schedule()`, `scheduleStream()`, `POST /streams/{id}/schedule`, `ScheduleStreamRequest`

**Files:**
- `StreamStatus.java` — `allowedTransitions()` via Java 21 switch
- `StreamSessionEntity.java` — domain methods + `@Version`
- `StreamService.java` — lifecycle methods, one-live check, dual-entry create
- `StreamController.java` — lifecycle endpoints
- `StreamServiceTest.java` — 34 tests (8 schedule-related tests removed per 2.4a revision)

**ADR:** [0001-stream-state-machine.md](adr/stream/0001-stream-state-machine.md) (Revised 2026-07-07)

**Validate:** `./gradlew :stream-service:test` — 34/34 pass (before 2.4b additions)

---

#### 2.4 SRS Webhook Integration & Publish Token Architecture ✅

**Status:** Complete (implemented 2026-07-08)

**Why:** SRS must validate that a streamer is authorized to publish before accepting an RTMP connection. The stream service must auto-detect stream start/end via SRS webhooks and transition state accordingly.

**Architecture (see [ADR-0004](adr/stream/0004-srs-webhook-publish-token.md), revised during implementation):**

Three separate identifiers:
- **Stream ID** (public, in REST URLs)
- **SRS stream name** (`srsName`, semi-private UUID stored as plaintext in `srs_name` column; SHA-256 stored in `stream_key_hash` for lookup)
- **Publish token** (JWT, in RTMP `?token=` query param; self-validating, single 2h TTL)

Flow:
```
POST /streams → DRAFT + srsName + publish JWT (token issued once, stored nowhere server-side)
Streamer calls GET /publish-key → receives rtmpUrl with ?token={jwt}
Streamer pastes URL into OBS
OBS → RTMP → SRS → on_publish webhook → gateway → stream service validates JWT → auto-goLive
SRS → HLS → viewer browser (direct, no proxy)
OBS stops → SRS → on_unpublish webhook → stream service → auto-end
```

**Key design decisions (revised during implementation):**

| Decision | Original ADR | Implemented | Rationale |
|----------|-------------|-------------|-----------|
| Token TTL | Dual: 2h (DRAFT) / 15m (LIVE) | **Single: 2h** | OBS only ever has one token (issued at DRAFT); no LIVE-specific token exists |
| Expiry on reconnect | Always enforced | **Sol3: DRAFT=enforce, LIVE=skip** | Unbounded stream duration; exp only matters for DRAFT→LIVE authorization gate |
| Key rotation (LIVE) | Rotate srsName + JWT | **JWT-only rotation, srsName stable** | Rotating srsName breaks HLS playback for all viewers |
| Playback URL | In `StreamResponse` | **In `PublishKeyResponse` only** (Option B) | srsName not needed in list/detail responses; viewer discovery deferred to Phase 4 |
| srsName storage | SHA-256 only (irreversible) | **Plaintext `srs_name` column + SHA-256 hash** | Needed for GET /publish-key URL reconstruction |
| Webhook routing | Direct to stream-service | **Through gateway** | Gateway retry filter (3 retries with backoff) protects against transient failures |
| Webhook shared secret | `X-Webhook-Secret` header | **Deferred to Phase 4** | SRS doesn't support custom HTTP headers; network isolation + JWT validation is sufficient for internal Docker network |

**What was implemented:**

- **2.4a — State machine revision:**
  - Removed SCHEDULED transitions (`DRAFT→SCHEDULED`, `SCHEDULED→LIVE`, `SCHEDULED→CANCELLED`)
  - SCHEDULED is creation-time-only terminal state
  - Added `POST /v1/streams/{id}/go-live` — SCHEDULED→DRAFT with fresh publish key
  - SCHEDULED streams have no publish key until go-live
- **2.4b — Publish token issuance:**
  - `PublishTokenService`: HS256 JWT with claims `sub`, `streamId`, `srsName`, `exp`(now+2h)
  - srsName + token generated at DRAFT creation; SCHEDULED gets no key
  - `GET /streams/{id}/publish-key` — view existing key (token masked); returns srsName, rtmpUrl, playUrl
  - `POST /streams/{id}/publish-key` — rotate: DRAFT=full (new srsName+JWT), LIVE=JWT-only (same srsName)
  - `PublishTokenProperties`: configurable TTL, SRS RTMP/HLS host URLs
- **2.4c — Webhook endpoints:**
  - `SrsWebhookController`: `POST /v1/webhooks/srs/on_publish`, `on_unpublish`
  - `on_publish` Sol3 validation: verify JWT sig + srsName match; enforce exp for DRAFT, skip exp for LIVE
  - `on_unpublish`: LIVE→ENDED; idempotent for non-LIVE states
  - Gateway: webhook paths (`/api/streams/v1/webhooks/**`) added to public filter chain (no JWT required)
  - Stream-service: `/v1/webhooks/**` permitted in SecurityConfig
- **2.4d — Playback URL:**
  - `PublishKeyResponse` includes `rtmpUrl` (with `?token=`), `playUrl` (`.m3u8`), `srsName`, raw `token`, `expiresAt`
  - `GET /publish-key` shows URLs with masked token (`****`); `POST /publish-key` shows raw token once
- **2.4e — Migration V5:**
  - Reverse V3: `stream_key_hash SET NOT NULL`
  - Added `srs_name VARCHAR(36)` column for plain SRS stream name
  - Backfill: legacy NULL hashes set to empty string

**Files (15 changed):**
- `StreamStatus.java`, `StreamSessionEntity.java` — state machine revision (2.4a)
- `PublishTokenService.java` (new) — JWT issuance + Sol3 validation
- `PublishTokenProperties.java` (new) — TTL + SRS host config
- `SrsWebhookController.java` (new) — on_publish, on_unpublish
- `SrsWebhookPayload.java` (new) — webhook body DTO with `extractToken()`
- `StreamService.java` — goLiveFromSchedule, handlePublish, handleUnpublish, revised issuePublishKey/getPublishKey
- `StreamController.java` — added POST /go-live endpoint
- `PublishKeyResponse.java` — rewritten: srsName, rtmpUrl, playUrl, token, expiresAt
- `StreamSessionRepository.java` — added `findByStreamKeyHash()`
- `SecurityConfig.java` (stream-service) — permit `/v1/webhooks/**`
- `SecurityConfig.java` (gateway-service) — webhook paths in public filter chain
- `application.yml` — publish-token + SRS config
- `V5__reverse_stream_key_hash_not_null.sql` (new) — NOT NULL + srs_name column
- `PublishTokenServiceTest.java` (new) — 5 tests: issuance, Sol3 DRAFT/LIVE, sig/srsName validation
- `StreamServiceTest.java` — +15 tests: goLiveFromSchedule, handlePublish, handleUnpublish
- `ScheduleStreamRequest.java` — deleted (DRAFT→SCHEDULED transition removed)

**Validate:** `./gradlew :stream-service:test` — 54/54 pass. `./gradlew :stream-service:compileJava :gateway-service:compileJava` — BUILD SUCCESSFUL.

**ADR:** [0004-srs-webhook-publish-token.md](adr/stream/0004-srs-webhook-publish-token.md) (to be revised with Sol3 + single-TTL decisions)

---

#### 2.5 Frontend: Stream Dashboard

**Why:** Streamers need a UI to manage their streams. Currently only a placeholder home page exists.

**What:**
- `StreamService` (frontend) — HTTP client for stream API endpoints
- `StreamListComponent` — lists user's streams with status badges (draft/scheduled/live/ended)
- `StreamCreateComponent` — form: title, description, category, max viewers
- `StreamDetailComponent` — stream info, publish key management (generate, copy, show RTMP URL), start/end controls
- `StreamDashboardPage` — container with tab navigation (My Streams / Create)
- Lazy-loaded route under `/dashboard/streams`

**Files (new):**
- `frontend/streaming-ui/src/app/core/services/stream.service.ts`
- `frontend/streaming-ui/src/app/features/streams/stream-list/`
- `frontend/streaming-ui/src/app/features/streams/stream-create/`
- `frontend/streaming-ui/src/app/features/streams/stream-detail/`
- `frontend/streaming-ui/src/app/features/streams/stream-dashboard-page/`

**Validate:** `ng build` + manual flow: login → navigate to dashboard → create stream → view stream → generate publish key

---

#### 2.6 — Kafka Integration Testing

**Why:** The stream state machine (2.3) publishes events to Kafka, and downstream services (chat-service 3.3, notification-service 5.2) consume them. There are currently no tests verifying that Kafka produce/consume works end-to-end.

**What:**
- Add `testImplementation("org.testcontainers:kafka")` to stream-service
- Write `StreamEventPublisherTest` — produce a `STREAM_CREATED` event, verify it appears on the topic
- Write `StreamControlListenerTest` (in chat-service after 3.3) — consume a `STREAM_CREATED` event, verify room is created
- Verify topic auto-creation and serialization/deserialization with the configured KafkaTemplate

**Files (planned):**
- `stream-service/src/test/java/.../messaging/StreamEventPublisherTest.java` (new)
- `chat-service/src/test/java/.../messaging/StreamControlListenerTest.java` (new, after 3.3)

**Validate:** `./gradlew :stream-service:test` — Kafka integration tests pass with Testcontainers

---

#### 2.7 — Schedule Reminder Batch (Deferred)

**Why:** Scheduled streams need pre-stream reminders to prompt the streamer to set up. The reminder system ensures scheduled streams don't go forgotten.

**Design (ADR deferred):**
- Three-wave reminder: 9AM 2 days before, 4PM 1 day before, 8 hours before scheduled time
- Requires: job scheduler (Spring `@Scheduled`/Quartz), push notification infrastructure, user notification preferences
- Schedule data model supports it: `scheduled_at` column exists; may need `reminder_sent_at` tracking

**Depends on:** Phase 5.x (notification infrastructure), Phase 4.x (subscription data model)

---

#### 2.8 — Stream Templates (Deferred)

**Why:** Streamers who go live regularly need reusable boilerplates. Creating from scratch each time is friction.

**Design sketch (full ADR later):**
- `StreamTemplateEntity`: `id`, `broadcasterSubject`, `name`, `title`, `description`, `categoryId`, `tags[]`, `maxViewers`
- `POST /v1/streams/templates` — save template
- `GET /v1/streams/templates` — list templates
- `POST /v1/streams?fromTemplate={id}` — create DRAFT from template (pre-fills, issues new key)
- Template ≠ stream — no status, no publish key, no lifecycle
- Different from SCHEDULED: template is reusable, schedule is a specific planned broadcast

**Depends on:** Phase 2.4 (publish token issuance in create flow)

---

### Phase 2 Checklist

- [x] 2.0 — Kafka connectivity verification + health checks
- [x] 2.1 — Gateway path rewriting (not needed — base-path stripping handles routing)
- [x] 2.2 — PBAC enforcement (ownership checks)
- [x] 2.3 — Stream state machine + Kafka events
- [x] 2.4a — State machine revision (remove SCHEDULED transitions, add go-live)
- [x] 2.4b — Publish token issuance (single 2h TTL, Sol3 validation, key rotation)
- [x] 2.4c — SRS webhook endpoints (Sol3 on_publish/on_unpublish, gateway routing)
- [x] 2.4d — Playback URL in PublishKeyResponse (Option B)
- [x] 2.4e — Migration V5 (reverse NOT NULL, add srs_name column)
- [x] 2.5 — Frontend stream dashboard (partial: stream-create + channel pages exist)
- [ ] 2.6 — Kafka integration testing
- [ ] 2.7 — Schedule reminder batch (deferred — depends on notification + subscription)
- [ ] 2.8 — Stream templates (deferred — separate ADR needed)

**Architecture Decisions — see [docs/adr/stream/](adr/stream/):**

| ADR | Decision |
|-----|----------|
| [0001](adr/stream/0001-stream-state-machine.md) | Stream state machine with entity domain methods, optimistic locking, one-live-stream rule (Revised 2026-07-07) |
| [0002](adr/stream/0002-kafka-event-publishing.md) | Reactive Kafka publisher with at-most-once delivery, deferred DLQ/outbox concerns |
| [0003](adr/stream/0003-categories-tags.md) | Managed `stream_category` lookup table + `TEXT[]` tags with GIN index |
| [0004](adr/stream/0004-srs-webhook-publish-token.md) | SRS webhook integration with JWT publish token, srsName/SHA-256 lookup, Sol3 contextual expiry (to be revised: single-TTL + Sol3 decisions)

---

## Phase 3 — Real-time Chat ⚡

**Status:** In Progress — 3.1 (service layer) and 3.2 (JWT identity) committed. 3.5 (frontend) built but uncommitted. Redis infrastructure not yet defined.

**Goal:** Viewers in a stream room can chat. Messages persist to PostgreSQL with Redis as the hot cache. Room lifecycle is driven by stream events from Kafka.

### Architecture Decisions

Three ADRs were recorded during Phase 3 design — see [docs/adr/chat/](adr/chat/):

| ADR | Decision |
|-----|----------|
| [0001](adr/chat/0001-layered-reactive-architecture.md) | Layered reactive (`api/` → `application/` → `domain/` → `infrastructure/`) over DDD/Clean/Hexagonal — matches stream-service conventions |
| [0002](adr/chat/0002-cache-aside-redis-zset.md) | Cache-aside with Redis ZSET (epoch-millis scored) — PG is system of record, Redis is disposable hot cache |
| [0003](adr/chat/0003-jwt-derived-author-identity.md) | Author identity from JWT `sub` claim only — `SendMessageRequest` has no `author` field, eliminating impersonation |

### Scaffold Artifacts

```
chat-service/src/main/java/com/streaming/chat/
├── api/
│   ├── ChatController.java              ← rewritten: @AuthenticationPrincipal, delegates to ChatService
│   └── dto/
│       ├── SendMessageRequest.java       ← { @NotBlank String content } — no author field
│       ├── MessageResponse.java          ← from(ChatMessage, roomKey)
│       └── RoomResponse.java             ← from(ChatRoom): externalKey, status, createdAt, archivedAt
├── application/
│   ├── ChatService.java                  ← cache-aside orchestration (PG-first writes, Redis-first reads)
│   └── RoomService.java                  ← getOrCreate + archive (idempotent)
├── domain/
│   ├── ChatRoom.java                     ← entity → chat.chat_room, Persistable<UUID>
│   ├── ChatMessage.java                  ← entity → chat.chat_message, Persistable<UUID>
│   └── RoomStatus.java                   ← ACTIVE, ARCHIVED with wireValue()
├── infrastructure/
│   ├── persistence/
│   │   ├── ReactiveChatRoomRepository.java       ← findByExternalKey, existsByExternalKey
│   │   └── ReactiveChatMessageRepository.java    ← findByRoomIdOrderByCreatedAtDesc, findByRoomIdAndCreatedAtBeforeOrderByCreatedAtDesc
│   └── cache/
│       └── RedisMessageCache.java        ← ZSET per room (key: chat:room:{roomKey}:recent), 100-msg cap
└── config/
    ├── SecurityConfig.java               ← existing, unchanged
    ├── JwtProperties.java                ← existing, unchanged
    └── ChatAuthenticationEntryPoint.java ← existing, unchanged

V1__bootstrap_chat_schema.sql             ← existing: chat schema + chat_room + chat_message tables
V2__add_room_status.sql                   ← new: status + archived_at on chat.chat_room
```

### Work Items

---

#### 3.0 — Infrastructure: Redis Setup

**Why:** The chat service depends on Redis for the hot cache layer. Currently no Redis Docker Compose service exists — only an env file (`main/env/redis.env`). Without a defined Redis service, the cache layer cannot be tested or run.

**What:**
- Add a `redis` service to the root `compose.yaml` with:
  - Image: `redis:7-alpine`
  - Port: `${REDIS_PUBLISH_PORT}:6379`
  - Health check: `redis-cli ping`
  - Volume: none (Redis is a disposable cache per ADR-0002; no persistence needed)
  - Network: shared application network
- Verify the chat service connects via `REDIS_HOST` / `REDIS_PORT` env vars
- Verify `ReactiveStringRedisTemplate` can execute a `PING` command

**Files:**
- `compose.yaml` (new — add Redis service)
- `main/env/redis.env` (already exists — verify values)

**Validate:** `docker compose up -d redis` → `redis-cli ping` → PONG → chat-service starts and connects

---

#### 3.1 — Chat Service Layer + PG Persistence + Redis Cache-Aside ✅

**Status:** Done

**What was built:**
- Decomposed the 65-line prototype `ChatController` (inline Redis calls, no service layer) into `api/` → `application/` → `domain/` → `infrastructure/` layered architecture
- `ChatService` orchestrates cache-aside: writes PG first then updates Redis; reads Redis first, falls back to PG with async backfill
- `RedisMessageCache` wraps `ReactiveStringRedisTemplate` with ZSET operations (ZADD + ZREVRANGEBYSCORE + ZREMRANGEBYRANK), Jackson JSON serialization with `JavaTimeModule`
- `ChatRoom` and `ChatMessage` implement `Persistable<UUID>` with `@Transient isNew` (matching stream-service pattern)
- `RoomStatus` enum (ACTIVE, ARCHIVED) with Flyway migration V2
- R2DBC repositories with derived query methods

**What was built beyond the original plan:**
- **Redis resilience:** All three `RedisMessageCache` public methods wrapped with `.onErrorResume()` — Redis connection failure returns empty list / `false` instead of propagating exception. Writes are PG-first, so no data loss.
- **Cursor-based pagination:** `getBefore(roomKey, maxScore, limit)` using `Range.of(Bound.unbounded(), Bound.exclusive(maxScore))` with `reverseRangeByScore` for ZSET cursor queries. PG fallback via `findByRoomIdAndCreatedAtBeforeOrderByCreatedAtDesc`.
- **Room status endpoint:** `GET /v1/rooms/{roomKey}` returns `RoomResponse` with status, timestamps. Used by frontend to gate chat features.
- **Archived room enforcement:** `sendMessage()` checks `room.isActive()` and throws `RoomArchivedException` if archived.

**Cache warm-up (implemented for `getRecentMessages`, gap for `getMessagesBefore`):**
- `getRecentMessages()`: On Redis miss → query PG → **async backfill** via `Flux.fromIterable(fromPg).flatMap(m -> cache.addToRecent(roomKey, m)).subscribe(...)`. Fire-and-forget; failures are logged and swallowed.
- `getMessagesBefore()`: On Redis miss → query PG → **does NOT backfill** (gap — see [3.7](#37--cache-warm-up-completion)). Messages fetched via cursor pagination from PG are never written to Redis, so repeated scroll-up on the same room always hits PG.

**Files:** 13 new + 1 rewritten + 1 new migration (see scaffold tree above), plus `ChatController`, `ChatService`, `RedisMessageCache`, `ReactiveChatMessageRepository`, and `RoomResponse.java`

**Validate:** `./gradlew :chat-service:compileJava` — BUILD SUCCESSFUL

---

#### 3.2 — JWT-Based Author Identity ✅

**Status:** Done

**What was built:**
- `SendMessageRequest` is a single-field record: `@NotBlank String content` — no `author` field exists
- `ChatController.sendMessage()` extracts `jwt.getSubject()` via `@AuthenticationPrincipal Jwt jwt` and passes it to `ChatService`
- `ChatMessage.create(roomId, authorSubject, body, now)` stores `authorSubject` from JWT, not from request body
- Jackson ignores unknown properties in request body by default — a client sending `{"author": "someone-else", "content": "hello"}` silently drops the `author` field

**Why this matters:** The prototype controller accepted an `author` field from the request body. Any authenticated user could impersonate any other user. See [ADR-0003](adr/chat/0003-jwt-derived-author-identity.md).

---

#### 3.3 — Room Lifecycle from Stream Events

**Depends on:** Phase 2.3 (stream state machine + Kafka events)

**What:**
- Add `StreamControlListener` — a reactive Kafka consumer in chat-service that listens to `stream.control` topic
- On `STREAM_CREATED` event: create `ChatRoom` with `external_key = streamSessionExternalKey`
- On `STREAM_ENDED` event: archive the room → set `status = ARCHIVED`, `archived_at = now`, evict Redis cache
- Replace `getOrCreateRoom()` auto-creation in `ChatService` with a lookup-only path (room must already exist)
- Handle out-of-order events: archive before create is a no-op, duplicate creates are idempotent (already handled by `getOrCreate`)

**Files (planned):**
- `chat-service/.../messaging/StreamControlListener.java` (new)
- `chat-service/.../config/KafkaConsumerConfig.java` (new — or shared config)
- `chat-service/.../application/ChatService.java` (modify — remove auto-create path)
- `chat-service/.../infrastructure/cache/RedisMessageCache.java` (already has `evictRoom()`)

**Validate:** Integration test — produce `STREAM_CREATED` event → room exists in chat DB → send message succeeds. Produce `STREAM_ENDED` → room archived → send message returns error.

---

#### 3.4 — PBAC Enforcement in Chat

**Depends on:** Phase 3.2 (JWT identity), Phase 6.2 recommended (pbac-common extraction)

**What:**
- Map chat endpoints to `(resource, action)` per [PBAC-AUTHORIZATION.md §6.3](../PBAC-AUTHORIZATION.md#63-chat-service-apichat):
  - `POST /v1/rooms/{roomKey}/messages` → `chat:message:room:{key}` / `send`
  - `GET /v1/rooms/{roomKey}/messages/recent` → `chat:room:{key}` / `read`
- Parse JWT `ent` claim, match against resource + action patterns
- Ownership check: `broadcaster_subject == sub || viewer` (deferred to room membership when implemented)
- Add `EntitlementMatcher` (or use shared `pbac-common` if extracted first)
- Integration test: user without `send` entitlement on room gets 403

**Note:** Currently `ent` enforcement is NOT implemented in any service — only gateway-level JWT validation exists. This work item is the chat-service side of the distributed PBAC enforcement described in [PBAC-AUTHORIZATION.md §7](../PBAC-AUTHORIZATION.md#7-evaluation-algorithm-each-service).

**Files (planned):**
- `chat-service/.../security/EntitlementMatcher.java` (new, or from pbac-common)
- `chat-service/.../api/ChatController.java` (add authorization checks)
- `chat-service/.../config/SecurityConfig.java` (enable method security)

---

#### 3.5 — Frontend Chat Experience

**Status:** Done — significantly expanded from original plan scope.

**What the plan originally said:** Simple `ChatComponent` — message list + input + REST polling + loading/empty/error states.

**What was actually built (12 new files + 1 modified):**

| Component | Type | Description |
|-----------|------|-------------|
| `ChatService` | Service | HTTP client for `/api/chat/v1/rooms` — `getRoom()`, `sendMessage()`, `getRecentMessages()`, `getMessagesBefore(roomKey, cursor, limit)` |
| `ChatPanelComponent` | Organism | Reusable smart chat panel (`[roomKey]` input, no page coupling) — embeddable in any page |
| `ChatLoadTestComponent` | Molecule | Multi-user load testing tool — 3 test users, random messages, bypasses auth interceptor |
| `ChatRoomPage` | Page | Thin wrapper — reads `:roomKey` from route, composes `ChatPanelComponent` + `ChatLoadTestComponent` |
| 3 DTO contracts | Contracts | `ChatMessageResponseDto`, `RoomResponseDto`, `SendMessageRequestDto` |

**`ChatPanelComponent` feature set:**

| Feature | Implementation |
|---------|---------------|
| **Virtual scroll** | `cdk-virtual-scroll-viewport` with `itemSize="auto"` — variable-height bubble measurement via ResizeObserver; only viewport + buffer rendered in DOM |
| **Lazy load** | Scroll near top (120px threshold) → `getMessagesBefore(cursor)` → prepend with scroll position preserved (measures `prevScrollHeight`/`prevScrollTop`, adjusts after CDK re-render in double `requestAnimationFrame`) |
| **Smart scroll-to-bottom** | Captures `isNearBottom` before poll merge → `scrollTo({bottom: 0})` if true; shows "New messages" FAB pill if false |
| **Optimistic send** | Client-generated `clientId` → immediate display with `status: 'sending'` → replaced with server response on success → red "Failed" badge + retry button on error |
| **Room status gate** | `checkRoomStatus()` before features → ARCHIVED: load messages, disable input + polling; ACTIVE: full features; 404: empty state |
| **Archived room UX** | Warning banner ("This room has been archived"), read-only messages, disabled input bar |
| **REST polling** | 3-second interval → `getRecentMessages()` → merge by deduping server IDs, sort by `createdAt` |
| **JWT identity** | Parses `sub` from access token (`atob` → `JSON.parse`) → right-aligns own messages, left-aligns others |
| **Signal-based state** | 12 signals for reactive state (`messages`, `loading`, `sending`, `loadingOlder`, `errorMessage`, `roomStatus`, `showScrollButton`, `showNewMessageHint`, `hasMoreBefore`, `currentUserSub`) |
| **Error handling** | Network error banner, per-message retry, 400 → "room may be archived" message |

**Files:**
- `frontend/streaming-ui/src/app/core/contracts/chat-message-response.dto.ts`
- `frontend/streaming-ui/src/app/core/contracts/room-response.dto.ts`
- `frontend/streaming-ui/src/app/core/contracts/send-message-request.dto.ts`
- `frontend/streaming-ui/src/app/core/services/chat.service.ts`
- `frontend/streaming-ui/src/app/pages/chat/chat-room.page.ts`
- `frontend/streaming-ui/src/app/pages/chat/chat-room.page.html`
- `frontend/streaming-ui/src/app/shared/organisms/chat-panel/chat-panel.component.ts`
- `frontend/streaming-ui/src/app/shared/organisms/chat-panel/chat-panel.component.html`
- `frontend/streaming-ui/src/app/shared/organisms/chat-panel/chat-panel.component.scss`
- `frontend/streaming-ui/src/app/shared/molecules/chat-load-test/chat-load-test.component.ts`
- `frontend/streaming-ui/src/app/shared/molecules/chat-load-test/chat-load-test.component.html`
- `frontend/streaming-ui/src/app/app.routes.ts` (modified — added `/chat/:roomKey` route)

**Known gaps in 3.5:**
- No unit tests for `ChatPanelComponent` or `ChatService`
- `ChatPanelComponent` uses `ChangeDetectionStrategy.Default` (not `OnPush`)
- Load test component has a cross-feature dependency on `LoginResponseDto` from auth contracts

**Validate:** `ng build` — passes (verified 2026-07-04)

---

#### 3.6 — Cache Integration Testing (NEW)

**Why:** The `RedisMessageCache` and `ChatService` have zero tests. The cache-aside pattern has multiple paths (hot read, cold fallback, warm-up, retention trim, cursor pagination, error resilience) — each needs verification against a real Redis instance.

**What:**
- Add `testImplementation("com.redis.testcontainers:testcontainers-redis:1.6.4")` (or `org.testcontainers:testcontainers` + manual `GenericContainer`)
- Add `testImplementation("org.testcontainers:junit-jupiter")`
- Create `src/test/resources/application-test.yml` with Testcontainers-derived Redis properties

**Test categories:**

| # | Test Class | What It Verifies |
|---|-----------|-----------------|
| 1 | `RedisMessageCacheTest` — **hot path write** | `addToRecent()` → ZADD with epoch-millis score → `getRecent()` returns deserialized messages newest-first |
| 2 | `RedisMessageCacheTest` — **hot path read** | `getRecent()` on empty key → empty list (not error); on populated key → correct count, correct order |
| 3 | `RedisMessageCacheTest` — **retention** | Add 150 messages → verify only 100 remain in ZSET (trim via `ZREMRANGEBYRANK`) |
| 4 | `RedisMessageCacheTest` — **cursor pagination** | Add 50 messages → `getBefore(roomKey, middleScore, limit)` → returns only messages with score < middleScore |
| 5 | `RedisMessageCacheTest` — **error resilience** | Stop Redis container → `addToRecent()` returns `false` (not throw); `getRecent()` returns empty list (not throw); `getBefore()` returns empty list (not throw) |
| 6 | `ChatServiceTest` — **cache-aside orchestration** | Redis populated → `getRecentMessages()` returns cached (never queries PG). Redis empty → falls back to PG → async backfill fires. Redis down → falls back to PG gracefully |
| 7 | `ChatServiceTest` — **archived room rejection** | `sendMessage()` on archived room → `RoomArchivedException` |
| 8 | `ChatServiceTest` — **warm-up verify** | After `getRecentMessages()` cold miss → messages are backfilled to Redis → next call hits cache |

**Files (planned):**
- `chat-service/build.gradle.kts` (add Testcontainers deps)
- `chat-service/src/test/resources/application-test.yml` (new)
- `chat-service/src/test/java/.../infrastructure/cache/RedisMessageCacheTest.java` (new)
- `chat-service/src/test/java/.../application/ChatServiceTest.java` (new)
- `chat-service/src/test/java/.../api/ChatControllerTest.java` (new)

**Validate:** `./gradlew :chat-service:test` — all 8 test categories pass with Testcontainers Redis

---

#### 3.7 — Cache Warm-Up Completion (NEW)

**Why:** `getMessagesBefore()` falls back to PG on Redis miss but never backfills the cache. This means every scroll-up on a room with older messages always hits PostgreSQL — the cache is only warm for the most recent 100 messages (populated by `sendMessage` and `getRecentMessages` async backfill). This is a correctness gap in the cache-aside implementation.

**What:**
- In `ChatService.getMessagesBefore()`, after the PG fallback path (`collectList()`), add an async backfill identical to the pattern already in `getRecentMessages()`:
  ```java
  .flatMap(fromPg -> {
      Flux.fromIterable(fromPg)
          .flatMap(m -> cache.addToRecent(roomKey, m))
          .subscribe(
              count -> {},
              err -> log.warn("Backfill cache write failed for roomKey={}", roomKey, err)
          );
      return Mono.just(fromPg);
  });
  ```

**Files:**
- `chat-service/.../application/ChatService.java` (add backfill in `getMessagesBefore` PG fallback path)

**Validate:** After `getMessagesBefore()` cold miss, messages are backfilled → next `getMessagesBefore()` with same cursor range hits Redis

---

### Phase 3 Checklist

- [x] 3.0 — Infrastructure: Redis Docker Compose service + health check + connectivity
- [x] 3.1 — Service layer + PG persistence + Redis cache-aside (extended: cursor pagination, Redis resilience, room status, archived enforcement)
- [x] 3.2 — JWT-based author identity
- [ ] 3.3 — Room auto-creation from stream events (blocked by 2.3)
- [ ] 3.4 — PBAC enforcement
- [x] 3.5 — Frontend chat experience (extended: virtual scroll, lazy load, smart scroll, optimistic send; remaining: unit tests + OnPush)
- [ ] 3.6 — Cache integration testing (zero tests exist)
- [ ] 3.7 — Cache warm-up completion (`getMessagesBefore` backfill gap)

---

## Phase 4 — Viewer Experience ○

**Status:** Planned

**Goal:** A viewer can discover live streams, watch them, and interact via chat.

### Work Items

#### 4.0 — Infrastructure: SRS Setup

**Why:** The viewer experience depends on SRS for HLS streaming. Currently only a config file (`main/docker/srs/conf/custom.conf`) exists — no Docker Compose service definition, no health check, no port mapping.

**What:**
- Add an `srs` service to the root `compose.yaml` with:
  - Image: `ossrs/srs:5` (or `ossrs/srs:6`)
  - Ports: `1935:1935` (RTMP), `8085:8080` (HLS HTTP), `1985:1985` (HTTP API)
  - Volume: `./main/docker/srs/conf/custom.conf:/srs/conf/srs.conf` (mount config)
  - Health check: `curl -s http://localhost:1985/api/v1/versions`
- Verify RTMP ingest works (`ffmpeg` or OBS → publish test stream)
- Verify HLS segments are generated under `./objs/nginx/html`

**Files:**
- `compose.yaml` (new — add SRS service)
- `main/docker/srs/conf/custom.conf` (already exists — verify config)

**Validate:** `docker compose up -d srs` → SRS healthy → publish test RTMP stream → `curl http://localhost:8085/test/index.m3u8` returns playlist

---

| # | Item | Depends on |
|---|------|-----------|
| 4.1 | **Frontend: Browse/discovery page** — list live streams with thumbnails, filter by category, search | 2.3 |
| 4.2 | **Frontend: Stream viewing page** — HLS player (hls.js), embedded chat panel (`ChatPanelComponent` already built), stream info sidebar | 2.4, 3.5, 4.1 |
| 4.3 | **Playback URL generation** — stream service returns HLS URL per stream, gateway proxies or redirects to SRS | 2.4 |
| 4.4 | **Viewer count / presence** — Redis-based ephemeral presence per room (`SETEX` with TTL), shown in UI | 4.2 |

### Phase 4 Checklist

- [ ] 4.0 — Infrastructure: SRS Docker Compose service + health check + RTMP/HLS verification
- [ ] 4.1 — Browse/discovery page
- [ ] 4.2 — Stream viewing page (player + chat)
- [ ] 4.3 — Playback URL generation
- [ ] 4.4 — Viewer presence

---

## Phase 5 — Notifications ○

**Status:** Planned — notification service has only `/v1/ping`, `StreamControlListener` only logs

**Goal:** Users get notified about followed streamers going live, chat mentions, etc.

### Work Items

| # | Item | Depends on |
|---|------|-----------|
| 5.1 | **Notification service core** — subscription CRUD (`channel_subscription` table), outbox management, `NotificationDispatcher` interface | — |
| 5.2 | **Kafka consumer → dispatch** — `StreamControlListener` wired to dispatch logic (stream.started → notify followers, stream.ended → notify) | 2.3, 5.1 |
| 5.3 | **Email adapter** — SMTP integration via Spring Mail, templated emails (Thymeleaf or plain text) | 5.1 |
| 5.4 | **Frontend: Notification settings** — manage subscriptions, toggle email/push per channel, notification preferences | 5.1 |

### Phase 5 Checklist

- [ ] 5.1 — Subscription CRUD + outbox + dispatcher interface
- [ ] 5.2 — Kafka → dispatch wiring
- [ ] 5.3 — Email adapter
- [ ] 5.4 — Frontend notification settings

---

## Phase 6 — Production Hardening ○

**Status:** In Progress — Redis infrastructure hardened, structured logging deployed, refresh tokens migrated to Redis. Remaining items are planned but not started.

**Goal:** The platform is safe, scalable, and maintainable for production use.

### Work Items

| # | Item | Depends on |
|---|------|-----------|
| 6.0a | ✅ **Redis infrastructure hardening** — pinned image (7.2.4-alpine), AOF+RDB persistence, password auth, memory limits (256MB allkeys-lru), RedisInsight (2.44.0), json-file log rotation, restart policy | — |
| 6.0b | ✅ **Refresh token → Redis migration** — replaced JPA pessimistic-lock rotation with atomic Lua script; deleted RefreshTokenEntity/Repository/MaintenanceService; auth-service now uses Redis as primary store for ephemeral credentials. See [ADR auth/0001](adr/auth/0001-redis-refresh-token-storage.md) | 6.0a |
| 6.1 | **Idempotency keys** — gateway filter + Redis dedup + frontend `IdempotencyService` → enable POST retry in auth interceptor | — |
| 6.2 | **Shared `pbac-common` library** — extract duplicated `JwtProperties` + `ReactiveJwtDecoder` + `EntitlementMatcher` from stream/chat/notification into a shared Gradle module | 2.2 |
| 6.3 | **Rate limiting** — gateway-level rate limits per endpoint, Redis-backed token bucket. See [ADR common/0002](adr/common/0002-redis-ephemeral-data-store.md) for design | 6.0a |
| 6.4 | **WebSocket upgrade for chat** — replace REST polling with WebSocket (STOMP or raw) for real-time messaging. Redis Pub/Sub for cross-instance message fan-out | 3.5 |
| 6.5 | **Security hardening** — TLS everywhere, secrets management (env vars → vault), CSP headers, CSRF audit, dependency CVE scanning | — |
| 6.6 | **Observability** — ~~structured JSON logging~~ ✅, Micrometer Tracing (traceId/spanId propagation), Micrometer metrics (Prometheus), Grafana dashboard, centralized log backend (Loki or ELK) | — |

### Phase 6 Checklist

- [x] 6.0a — Redis infrastructure hardening
- [x] 6.0b — Refresh token → Redis migration
- [ ] 6.1 — Idempotency keys
- [ ] 6.2 — Shared `pbac-common` library
- [ ] 6.3 — Rate limiting
- [ ] 6.4 — WebSocket chat
- [ ] 6.5 — Security hardening
- [ ] 6.6 — Observability
  - [x] Structured JSON logging (logstash-logback-encoder, all 6 services, [ADR common/0001](adr/common/0001-structured-json-logging.md), [LOGGING-ARCHITECTURE.md](LOGGING-ARCHITECTURE.md))
  - [x] Logging architecture documentation ([LOGGING-ARCHITECTURE.md](LOGGING-ARCHITECTURE.md), [TRACE-PROPAGATION.md](TRACE-PROPAGATION.md))
  - [x] `streaming.service.instance-id` — unified instance identity across Eureka + logs
  - [x] Local dev profile — human-readable logs via `SPRING_PROFILES_ACTIVE=local`
  - [ ] Micrometer Tracing (traceId/spanId propagation across HTTP + Kafka)
  - [ ] Micrometer metrics (Prometheus endpoint)
  - [ ] Grafana dashboard
  - [ ] Centralized log backend (Loki/Grafana or ELK)

---

## Phase 7 — AI / LLM Layer (`insight-service`) ○

**Status:** Planned — final phase. **Scaffolding only exists today** (ADRs + design
sketch); no code, no Gradle module, no build impact. This phase is intentionally
deferred so it doesn't add complexity to the core streaming phases, while the design
decisions are captured now to make the eventual build fast and consistent.

**Goal:** Add LLM-powered capabilities — chat/stream summarization, chat moderation,
semantic search, and retrieval-augmented Q&A ("ask this stream") — as a new,
isolated control-plane service.

**Guiding principle: a 4-rung capability ladder, delivered in order.** RAG is the
*destination* (rung 4), not the starting point. Each rung ships one working feature and
teaches one new concept. See [ADR-0004](adr/insight/0004-capability-ladder.md).

### Design Decisions (all **Proposed** — see [docs/adr/insight/](adr/insight/))

| ADR | Decision |
|-----|----------|
| [0001](adr/insight/0001-insight-service-architecture.md) | New standalone `insight-service`, layered-reactive + hexagonal ports at the two swappable edges (LLM provider, vector store) |
| [0002](adr/insight/0002-llm-provider-port.md) | LLM provider behind a vendor-neutral `LlmPort`; timeout/retry/fallback/cost wrap the port in the application layer |
| [0003](adr/insight/0003-pgvector-over-dedicated-vector-db.md) | `pgvector` on the existing PostgreSQL for vectors — no new datastore; swap to a dedicated vector DB only on a measured trigger |
| [0004](adr/insight/0004-capability-ladder.md) | Incremental ladder: plain call → classify → embed → RAG (**read this first**) |

> Full module/data-flow sketch: [INSIGHT-SERVICE-SKETCH.md](INSIGHT-SERVICE-SKETCH.md)
> (illustrative pseudocode, not compilable).

### Work Items (rungs)

| Rung | Item | New concept | Depends on |
|------|------|-------------|-----------|
| 7.0 | **Infrastructure** — `insight-service` Gradle module, Eureka + gateway route, `pgvector`-enabled Postgres image (rung 3+), `insight` schema | Five-step service lifecycle | — |
| 7.1 | **Rung 1 — Plain LLM call**: "catch me up" chat summary + auto title/tags. Token streaming (`Flux<String>` → SSE), structured JSON output, timeout/retry/fallback, cost accounting | LLM as unreliable dependency | 3.x (chat), `LlmPort` |
| 7.2 | **Rung 2 — Classification**: chat moderation as a Kafka consumer, off the hot path, batched, best-effort | Async LLM in event pipeline | 7.1, chat events |
| 7.3 | **Rung 3 — Embeddings + retrieval** (no generation): semantic VOD search via `EmbeddingPort` + `VectorStorePort` (pgvector); measurable recall | Embeddings, chunking, similarity search | 7.0 (pgvector), **ASR transcripts*** |
| 7.4 | **Rung 4 — Full RAG**: "ask this stream" — retrieval + grounded generation with timestamp citations | Grounded generation on top of retrieval | 7.3 |

> **\*ASR / transcript pipeline** (media-plane-owned) is a prerequisite for rungs 3–4
> and is tracked outside this ladder. Rungs 1–2 have no such dependency and can ship first.

### Deliberately deferred to Phase-7 start (not decided now)

- Which LLM provider / model (hosted vs. local; per-rung choice) → follow-up ADR.
- Embedding model + vector dimension → decided with the pgvector schema at rung 3.
- Cost ceilings and per-feature rate limits → set when the first billed feature ships.

### Phase 7 Checklist

- [ ] 7.0 — Infrastructure: module, discovery/gateway wiring, pgvector image, `insight` schema
- [ ] 7.1 — Rung 1: plain LLM call (summary + title/tags, streaming, fallback)
- [ ] 7.2 — Rung 2: classification (moderation via Kafka)
- [ ] 7.3 — Rung 3: embeddings + semantic search (pgvector)
- [ ] 7.4 — Rung 4: full RAG ("ask this stream")

---

## Future (Phase 8+)

Not planned yet — candidates:

| Feature | Notes |
|---------|-------|
| VOD / Archives | Record streams, playback on demand (also a prerequisite for AI rungs 3–4 transcripts) |
| Transcoding (FFmpeg) | Adaptive bitrate via SRS/FFmpeg pipeline |
| ASR / transcription pipeline | Speech-to-text on recordings — feeds Phase 7 rungs 3–4 |
| Monetization | Subscriptions, tips, ads |
| Moderation dashboard | Admin UI for chat moderation, stream takedowns |
| Mobile app | Ionic/Capacitor or native, reusing existing API |
| CDN integration | HLS edge delivery for scale |
| Multi-region | Geo-distributed SRS + DB replication |

---

## Dependency Graph

```
                    ┌──────────────────────────┐
                    │   Infrastructure Layer    │
                    │                           │
                    │  PostgreSQL ── (all phases)│
                    │  Kafka ─────── (2,3,5)    │
                    │  Redis ─────── (3,6.3)    │
                    │  SRS ───────── (4)        │
                    │  SMTP ──────── (5)        │
                    │  LLM provider  (7)        │
                    │  pgvector ──── (7.3+)     │
                    └──────┬───────────────────┘
                           │
Phase 1 (DONE) ───────────┘
    │
    ▼
Phase 2 ─────────────────────────────┐
│  2.0 Kafka infra + health check    │
│  2.1 Gateway fix                   │
│  2.2 PBAC enforcement ──────────────┼──┐
│  2.3 Stream state machine ──────────┼──┼──┐
│  2.4 SRS webhook                   │  │  │
│  2.5 Frontend stream dashboard     │  │  │
│  2.6 Kafka integration tests       │  │  │
│                                     │  │  │
Phase 3 ──────────────────────────────┘  │  │
│  3.0 Redis infra + compose service    │  │
│  3.1 Chat service layer               │  │
│  3.2 Fix author identity              │  │
│  3.3 Room from stream events ◄─────────┘  │
│  3.4 PBAC in chat                         │
│  3.5 Frontend chat experience             │
│  3.6 Cache integration tests              │
│  3.7 Cache warm-up completion             │
│                                           │
Phase 4 ────────────────────────────────────┘
│  4.0 SRS infra + compose service  ◄── needs SRS Docker service
│  4.1 Browse/discovery page        ◄── needs stream state machine (2.3)
│  4.2 Stream viewing page          ◄── needs SRS webhook (2.4) + chat (3.5)
│  4.3 Playback URL generation      ◄── needs SRS webhook (2.4)
│  4.4 Viewer presence
│
Phase 5
│  5.1-5.4 Notification service     ◄── needs stream events (2.3)
│
Phase 6
│  6.1-6.6 Hardening                ◄── can run in parallel with earlier phases
│
Phase 7 (final — scaffolding only today)
│  7.0 insight-service infra + pgvector image
│  7.1 Rung 1 plain LLM call        ◄── needs chat (3.x)
│  7.2 Rung 2 classification        ◄── needs chat events
│  7.3 Rung 3 embeddings + search   ◄── needs pgvector (7.0) + ASR transcripts (Phase 8)
│  7.4 Rung 4 full RAG              ◄── needs 7.3
```

---

## Conventions

### Per-Work-Item Flow

```
1. /plan    → implementation plan for the work item
2. /implement → write code, tests, verify build
3. Code review → code-reviewer agent
4. Commit → conventional commits format
5. Reference doc → docs/<PATTERN-NAME>.md if new pattern introduced
```

### Third-Party Service Lifecycle

For every Docker-based service introduced in a phase:

```
1. Define   → Compose service entry (image, port, health check, volume, env)
2. Provision → docker compose up -d, verify health
3. Connect  → App config (env vars → application.yml), verify connectivity
4. Test     → Integration tests with real instance (Testcontainers or compose)
5. Verify   → End-to-end smoke test: write → read → round-trip confirmed
```

A phase is NOT done until all 5 steps are complete for every service it introduces.

### Commit Format

```
<type>: <description>

<optional body>

Co-Authored-By: Claude <noreply@anthropic.com>
```

Types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`

---

## Related Docs

- [ARCHITECTURE.md](ARCHITECTURE.md) — High-level system architecture
- [SERVICE-ARCHITECTURE.md](SERVICE-ARCHITECTURE.md) — Per-service architectural styles
- [PBAC-AUTHORIZATION.md](PBAC-AUTHORIZATION.md) — Authorization model and JWT design
- [REFRESH-TOKEN-ROTATION.md](REFRESH-TOKEN-ROTATION.md) — Token lifecycle
- [RXJS-SINGLE-FLIGHT-PATTERN.md](RXJS-SINGLE-FLIGHT-PATTERN.md) — Concurrent dedup pattern
- [AUTH-INTERCEPTOR-PATTERN.md](AUTH-INTERCEPTOR-PATTERN.md) — Frontend 401 handling
- [IDEMPOTENCY-PATTERN.md](IDEMPOTENCY-PATTERN.md) — Idempotency key design
- [INSIGHT-SERVICE-SKETCH.md](INSIGHT-SERVICE-SKETCH.md) — Phase 7 AI/LLM layer design sketch (deferred)
- [docs/adr/insight/](adr/insight/) — AI/LLM layer ADRs (all Proposed)
