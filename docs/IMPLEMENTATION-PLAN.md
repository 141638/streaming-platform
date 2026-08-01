# Implementation Plan

**Last updated:** 2026-08-01 (6.4 WebSocket shipped, architecture doc refreshed; 6.5/6.6/6.7 Tier 2 deferred)
**Current phase:** 6 — Production Hardening ⚡ (6.0a–c ✅, 6.1 ✅, 6.2 ✅, 6.2a ✅, 6.3 ✅, 6.4 ✅, 6.5–6.6 🔵 deferred, **6.7 Tier 1 ✅, Tier 2 🔵 deferred**)
**Active blueprint:** None (Phase C quick wins + 6.7 Tier 2 under discussion)

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
  ✅          ⚠️           ⚠️           ⚠️           ⚠️           ⚡           ○
  
⚠️ = Functionally complete but has documented production gaps — see gap analysis
```

| Phase | Status | Goal | Third-Party Services |
|-------|--------|------|---------------------|
| [1 — Auth & Foundation](#phase-1--auth--foundation) | ✅ Done | Login, token rotation, PBAC JWT, gateway, frontend auth | PostgreSQL |
| [2 — Stream Lifecycle](#phase-2--stream-lifecycle) | ✅ Done | Stream CRUD with PBAC, state machine, SRS webhook, frontend dashboard, channel page | PostgreSQL, Kafka |
| [3 — Real-time Chat](#phase-3--real-time-chat) | ✅ Done | PG-backed chat with Redis ZSET cache-aside, room lifecycle from stream events, moderation | PostgreSQL, Redis |
| [4 — Viewer Experience](#phase-4--viewer-experience) | ✅ Done | Stream discovery, HLS player, embedded chat, viewer presence + heartbeat harvest | **SRS** |
| [5 — Notifications](#phase-5--notifications) | ✅ Done | Email notifications, subscription management, Kafka-driven dispatch, SSE delivery, follower fan-out, frontend settings + follow + bell | Kafka, SMTP |
| [6 — Production Hardening](#phase-6--production-hardening) | ⚡ In Progress | Idempotency, shared pbac-common, rate limiting, WebSocket, observability. Redis infrastructure hardened, structured logging done, refresh tokens migrated to Redis, outbox pattern shipped. | — |
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

As of 2026-07-14, the root `compose.yaml` **has been created** (Phase B1). The following third-party services are used across phases:

| Service | Phase | Compose Defined? | Env File? | Health Check? | Volume? |
|---------|-------|------------------|-----------|---------------|---------|
| PostgreSQL | 1–6 | ✅ `compose.yaml` | ✅ `main/env/postgres.env` | ✅ `pg_isready` | ✅ `postgres_data` |
| Kafka | 2–5 | ✅ `compose.yaml` | ✅ `main/env/kafka.env` | ✅ `kafka-topics --list` | ✅ `kafka_data` |
| Redis | 3 | ✅ `compose.yaml` | ✅ `main/env/redis.env` | ✅ `redis-cli ping` | ✅ `redis_data` |
| SRS | 4 | ✅ `compose.yaml` | ✅ `main/env/srs.env` | ✅ `curl /api/v1/versions` | ✅ `srs_dvr_data`, `srs_hls_data` |
| Discovery (Eureka) | 1–6 | ✅ `compose.yaml` | ✅ `main/env/discovery.env` | ✅ `curl /actuator/health` | ❌ |

All services run on `streaming_network` (bridge). Optional dev tools (RedisInsight, Kafka UI) are behind the `dev` profile: `docker compose --profile dev up -d`.

---

## Phase 2 — Stream Lifecycle ⚡

**Status:** Done. Core stream lifecycle (2.2–2.5), channel page (2.5b), and channel page (2.5b) shipped. Deferred: stream templates (2.8 — revisit when streamer friction data justifies it), schedule reminders (2.7 — blocked on Phase 5 notifications), SRS thumbnails (2.9 — blocked on Phase 4.0 SRS infrastructure).

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

#### 2.5 Frontend: Stream Dashboard ✅

**Status:** Done (implemented 2026-07-08)

**Why:** Streamers need a UI to manage their streams. Currently only a placeholder home page exists.

**What was implemented:**
- `StreamService` (frontend) — repaired: removed dead `scheduleStream()` (backend `/schedule` removed in 2.4a); added `goLive()`, `getPublishKey()`, `issuePublishKey()`; added `PublishKeyResponseDto`
- `StreamStatusBadgeComponent` (molecule) — reusable `p-tag` badge, `OnPush`, `computed` severity per status (DRAFT/SCHEDULED/LIVE/ENDED/CANCELLED)
- `StreamListComponent` — real card grid with status badges + thumbnail (placeholder while null); rows deep-link to `/channel/:id`. Replaced the raw-JSON channel dump
- `StreamDashboardPage` — `p-tabs` (PrimeNG 19) container: "My Streams" (list) + "Create" (existing `StreamCreatePage`)
- `StreamDetailPage` — stream info, thumbnail, lifecycle controls (start/go-live/end/cancel gated by status), publish-key management (generate/rotate, copy RTMP, reveal/mask token). Route `/channel/:id` via `withComponentInputBinding()`
- Thumbnail display wired throughout with `img/stream-placeholder.svg` fallback
- Routing: `/dashboard/streams`, `/channel/:id`, `/channel` → redirect; shell "Channel"/"Go Live" menu retargeted to dashboard; old `channel` page deleted

**Deviations from original plan:**
- Files live under `pages/streams/` (existing convention), not `features/streams/`
- Thumbnail field + display added (see 2.5 thumbnail note below and [2.9](#29--srs-snapshot-thumbnails))

**Thumbnail (field + display only; capture deferred to 2.9):**
- Backend: migration `V6__add_thumbnail_url.sql` (nullable `thumbnail_url VARCHAR(512)`), entity field, `thumbnailUrl` on `StreamResponse` + `StreamSummaryResponse`
- Field is **client-read-only** — create/update requests do not accept it; stays NULL until an SRS snapshot is generated
- Frontend renders a placeholder image while NULL — so every card shows the placeholder until 2.9 + Phase 4 SRS land
- Decision recorded in [ADR-0005](adr/stream/0005-stream-thumbnails.md); custom-upload via MinIO deferred to planned ADR-0006

**Files:**
- `frontend/streaming-ui/src/app/core/services/stream.service.ts` (repaired)
- `frontend/streaming-ui/src/app/core/contracts/publish-key-response.dto.ts` (new)
- `frontend/streaming-ui/src/app/core/contracts/schedule-stream-request.dto.ts` (deleted — dead)
- `frontend/streaming-ui/src/app/shared/molecules/stream-status-badge/`
- `frontend/streaming-ui/src/app/pages/streams/stream-list/`
- `frontend/streaming-ui/src/app/pages/streams/stream-dashboard/`
- `frontend/streaming-ui/src/app/pages/streams/stream-detail/`
- `frontend/streaming-ui/public/img/stream-placeholder.svg` (new)
- `stream-service`: `V6__add_thumbnail_url.sql`, `StreamSessionEntity.java`, `StreamResponse.java`, `StreamSummaryResponse.java`

**Validate:** `ng build` + manual flow: login → dashboard → create stream → open detail → generate publish key → start

---

#### 2.5b — Channel Page `/@username` (Authenticated) ✅

**Status:** Done (implemented 2026-07-09–10)

**Why:** The platform needed a Twitch-style channel page at `/@username` where viewers can see a broadcaster's recent streams, categories, bio, social links, and channel stats. All authenticated users can view any channel; owner-only edit affordances are gated client-side with server-side enforcement on writes.

**Architecture decisions — see [docs/adr/stream/0007](adr/stream/0007-public-channel-read-and-channel-service-seam.md) and [channel-page-retrospective](../docs/plans/channel-page-retrospective.md):**

| Decision | Where | Summary |
|----------|-------|---------|
| Denormalized identity | V7 migration | `broadcaster_username` + `broadcaster_verified` on `stream_session`, populated from JWT `attr` at create time |
| Safe cross-user projection | `ChannelResponse` | Excludes `broadcasterSubject`, publish key, `rtmpUrl` — never leaked to other users |
| Broadcaster profile | V8+V9 migrations | Separate `broadcaster_profile` table keyed by username: `bio TEXT` + `social_links JSONB` |
| JSONB converter pattern | `R2dbcConfig` | `@ReadingConverter`/`@WritingConverter` using `io.r2dbc.postgresql.codec.Json` for correct `jsonb` wire type |
| Channel stats (derived) | `ChannelStats` | Computed on-the-fly from session data: totalStreams, totalHoursStreamed, topCategory, firstStreamedAt, categoryBreakdown |
| Client-side owner gating | `ChannelPage` | `isOwner = computed(() => myUsername() === username())` — server enforces ownership on write endpoints |

**Backend deliverables:**

| # | Item | Files |
|---|------|-------|
| 1 | JWT `username` claim (auth-service) | V9 seed + `SubjectAttributeResolver` + `SubjectAttributes` |
| 2 | Denormalized broadcaster identity | V7 migration, `StreamSessionEntity`, `StreamService.buildEntity()` |
| 3 | `JwtAttr` utility | `JwtAttr.java` — static helpers for reading `attr.username` / `attr.verified_streamer` |
| 4 | `GET /v1/channels/{username}` | `ChannelResponse`, `ChannelStats`, `CategoryCount`, `StreamController.getChannel()` |
| 5 | Broadcaster profile (bio + social links) | V8+V9 migrations, `BroadcasterProfileEntity`, `BroadcasterProfileRepository` |
| 6 | `POST /v1/channels/{username}/profile` | `UpdateProfileRequest`, `StreamService.updateProfile()` — owner-only (JWT username check) |
| 7 | JSONB converters | `SocialLinksReadingConverter`, `SocialLinksWritingConverter`, `R2dbcConfig` |
| 8 | `SocialLink` DTO | `record SocialLink(String platform, String url)` |
| 9 | Repo finder by username | `StreamSessionRepository.findAllByBroadcasterUsernameOrderByCreatedAtDesc()` |

**Frontend deliverables:**

| # | Component | Type | Description |
|---|-----------|------|-------------|
| 1 | `ChannelPage` | Page | Smart container, route input `username`, owner gating, bio + social links editing |
| 2 | `ChannelHeaderComponent` | Organism | DiceBear avatar, verified badge, content-projected viewer/owner action slots |
| 3 | `SessionRailComponent` | Organism | 20rem cards, 8rem thumbnails, drag-to-scroll + chevrons, floating date labels, category portrait thumbnails |
| 4 | `CategoryStripComponent` | Molecule | 3.2/4 portrait cards, gradient backgrounds from name hash, pseudo-subscriber counts |
| 5 | `PlaylistRailComponent` | Organism | Empty "No playlists yet" shell |
| 6 | `SocialLinksComponent` | Molecule | Platform→PrimeIcon mapping, tooltip `<a>` buttons, null-safe |
| 7 | `formatCount` util | Lib | K/M/B number formatting |
| 8 | Contracts (5 new) | DTOs | `ChannelResponseDto`, `ChannelStatsDto`, `SocialLinkDto`, `CategoryCountDto`; `TokenAttrDto` extended |

**Deferred to later phases (documented in [scope review](plans/channel-page-scope-review.md)):**

| Item | Deferred to | Reason |
|------|------------|--------|
| Login rate limiter + uniform 401 | Phase 6.3 or standalone | ADR'd (auth/0003), not implemented |
| Real followers / subscriptions / gifting | channel-service extraction | Needs social graph + payments |
| Playlist domain | channel-service | Needs VOD/video upload first |
| Videos tab | Phase 4+ (VOD) | Tab disabled; needs video infrastructure |
| Guest/public access | Future ADR | Platform is login-gated |
| SRS snapshot thumbnails (2.9) | Phase 4.0 | SRS Docker service required |

**ADR:** [0007](adr/stream/0007-public-channel-read-and-channel-service-seam.md) (Accepted 2026-07-10)

**Retrospective:** [channel-page-retrospective.md](plans/channel-page-retrospective.md)

**Validate:** `./gradlew :stream-service:test` (all channel tests pass), `ng build` (BUILD SUCCESSFUL), manual flow: visit `/@<username>` → header + verified badge + session rail + category strip + About tab with bio/links/stats render from real data.

---

#### 2.5c — Channel Page Refactor: Child Routes, RailComponent, Video Tab, Archive Flow ✅

**Status:** Complete (2026-07-13)

**Why:** Tighten Phase 2's channel page with proper URL structure, a reusable Rail molecule, a Video tab showing archived broadcasts, and the archive action (ended → archived). The "Manage Streams" button is moved to the user menu as "Creator Dashboard."

**Architecture decisions:**

| Decision | Summary |
|----------|---------|
| Archive flow (Option C) | SRS DVR auto-records every session to MP4. User clicks "Archive" → stream-service copies to persistent volume, sets `archived_url`. Unarchived DVR files cleaned after 7 days. No MinIO needed yet. |
| Broadcast = ENDED/LIVE/SCHEDULED | `GET /v1/channels/{username}/broadcasts` returns streams IN (ENDED, LIVE, SCHEDULED) — server-enforced, never from client input. Rail = ENDED only. |
| Uploads + Playlists deferred | Mock data fills the rails; real APIs come in Phase 8 (VOD + playlist domain). |
| Child routes | Channel page becomes a layout shell: `/@username` redirects to `/@username/home`; tabs (`/home`, `/video`, `/about`) are child routes with `<router-outlet>`. |
| Three-endpoint channel split | Monolithic `GET /v1/channels/{username}` split into `/identity` (1-row query), `/home` (capped 15 sessions), `/about` (full scan on-demand). One endpoint per tab — eliminates triple-fetch. |
| R2DBC BroadcastQueryBuilder | `record BroadcastQuery(sql, bindings)` + static factory methods replace in-memory filtering/sorting. Server-enforced status, ILIKE keyword search, CASE-based status priority ORDER BY, views sort with NULLS LAST. |
| `takeUntilDestroyed` in `ngOnInit` | Must use in `ngOnInit`, never constructor. `DestroyRef` is tied to injector context — in constructor it fires before `ngOnInit`, prematurely aborting HTTP subscriptions. |
| `p-tabpanel` + `<router-outlet>` incompatible | PrimeNG detaches outlet on tab change, destroying components independently of router. Fixed by removing `p-tabpanels` wrapper, using `p-tablist` for navigation only. |

**Backend deliverables:**

| # | Item | Files |
|---|------|-------|
| 1 | V10 migration — `archived_url VARCHAR(512) NULL` on `stream_session` | `V10__add_archived_url.sql` |
| 2 | `POST /v1/streams/{id}/archive` — sets `archived_url` from SRS DVR persistent path (owner-only, ENDED-only) | `StreamController.java`, `StreamService.java`, `StreamSessionEntity.java` |
| 3 | Three channel endpoints: `/identity` (1-row), `/home` (capped 15), `/about` (full scan) | `ChannelIdentityResponse.java`, `ChannelHomeResponse.java`, `ChannelAboutResponse.java`, `StreamService.java`, `StreamController.java` |
| 4 | `BroadcastQueryBuilder` + `BroadcastQuery` — R2DBC query builder with server-enforced status, ILIKE search, CASE sort, views NULLS LAST | `BroadcastQueryBuilder.java`, `BroadcastQuery.java` |
| 5 | `GET /channels/{username}/broadcasts/recent` — top 10 ENDED, created_at DESC | `StreamController.java`, `StreamService.java` |
| 6 | `GET /channels/{username}/broadcasts?keyword=&sort=&order=&page=&size=` — paginated, filterable, status IN (ENDED,LIVE,SCHEDULED) | `StreamController.java`, `StreamService.java`, `BroadcastPageResponse.java`, `BroadcastPageMeta.java` |
| 7 | Deleted `ChannelResponse.java` — replaced by 3 focused DTOs | — |
| 8 | SRS DVR config — `dvr.enabled on`, `dvr_plan session`, write to persistent volume | `custom.conf` |
| 9 | V11 — `views BIGINT NOT NULL DEFAULT 0` on `stream_session` (ADR-0008 Phase 1) | `V11__add_stream_views.sql` |
| 10 | V12 — `stream_view_event` analytics table with UNIQUE (stream_id, user_id) | `V12__create_stream_view_events.sql` |
| 11 | Per-user hash view tracking (`stream:view:{streamId}:{viewerId}`) with Redis HSETNX dedup, self-view exclusion, IP fallback | `StreamService.java`, `StreamController.java` |
| 12 | Rewritten `ViewCountFlushService` — SCAN hashes → HGETALL → INSERT ON CONFLICT → recompute views → DEL | `ViewCountFlushService.java` |
| 13 | `ViewCountProperties` — configurable view TTL (default 24h) | `ViewCountProperties.java`, `StreamApplication.java`, `application.yml` |
| 14 | Retention job — `@Scheduled` cleanup of unarchived DVR files older than 7 days (deferred to Phase 4.0 with SRS compose) | N/A for now |

**API contracts:**

```
# Recent broadcasts rail (no user input — server enforces archived + limit 10)
GET /v1/channels/{username}/broadcasts/recent
→ 200 [ StreamSummaryResponse ]

# Broadcasts paginated list
GET /v1/channels/{username}/broadcasts?keyword=&sort=created_at&order=desc&page=0&size=24
→ 200 { data: StreamSummaryResponse[], meta: { total, page, size } }

# Archive a stream
POST /v1/streams/{id}/archive
→ 200 StreamResponse  (with archived_url populated)
→ 409 if not ENDED
→ 404 if not found
```

**Frontend deliverables:**

| # | Component | Type | Description |
|---|-----------|------|-------------|
| 1 | `RailComponent` | Molecule | Reusable horizontal scroll with header (title + "See more" link), drag-to-scroll, float chevrons, `ng-content` for flexible card projection. Added `seeMoreQueryParams` input for proper query param binding. |
| 2 | Channel routes | Routes | `/@username` → redirect to `/@username/home`; child routes `/home`, `/video`, `/about` |
| 3 | `ChannelPage` (refactor) | Page | Layout shell with `p-tablist` + `<router-outlet>` (no `p-tabpanels` wrapper — incompatible with router outlet). Reads identity only (`ChannelIdentityResponseDto`). Simplified `onTabChange` to single `navigateByUrl`. |
| 4 | `HomeTabComponent` | Page child | Calls `getChannelHome()`, renders existing home tab content. HTTP subscription in `ngOnInit` (not constructor). |
| 5 | `VideoTabComponent` | Page child | Default: 3 rails (uploads, broadcasts, playlists). Filtered: grid view with filter dropdown, search, sort, pagination. `seeMoreQueryParams` for proper query param binding. |
| 6 | `AboutTabComponent` | Page child | Calls `getChannelAbout()`, renders bio, social links, stats. HTTP subscription in `ngOnInit` (not constructor). |
| 7 | `video-tab.mocks.ts` | Lib | Mock data for upload + playlist rails — marked `TODO(Phase-8): remove when real API exists` |
| 8 | Contracts (3 new, 1 deleted) | DTOs | `ChannelIdentityResponseDto`, `ChannelHomeResponseDto`, `ChannelAboutResponseDto`; deleted `ChannelResponseDto` |

**Validate:** `./gradlew :stream-service:compileJava` (BUILD SUCCESSFUL), `ng build` (BUILD SUCCESSFUL), manual flow: visit `/@username` → tabs navigate via child routes without abort errors → "See More" links navigate with correct query params.

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
- [x] 2.5 — Frontend stream dashboard (card grid, lifecycle controls, publish-key management)
- [x] 2.5b — Channel page `/@username` (identity, session rail, category strip, About tab with bio/social links/stats, JSONB converter pattern)
- [x] 2.6 — Kafka integration testing (skipped — manual verification sufficient; defer automated Kafka tests to pre-production hardening)
- [ ] 2.7 — Schedule reminder batch (deferred — depends on notification + subscription)
- [x] 2.8 — Stream templates (deferred — revisit when streamer friction data justifies it; not core to stream domain yet)
- [x] 2.9 — SRS snapshot thumbnails ✅ (implemented 2026-07-15; see [retrospective](plans/srs-thumbnails-2.9-retrospective.md))
- [x] 2.5c — Channel page refactor: child routes, RailComponent, Video tab, archive flow (complete 2026-07-13; see [retrospective](plans/channel-2.5c-and-view-tracking-retrospective.md))

**Architecture Decisions — see [docs/adr/stream/](adr/stream/):**

| ADR | Decision |
|-----|----------|
| [0001](adr/stream/0001-stream-state-machine.md) | Stream state machine with entity domain methods, optimistic locking, one-live-stream rule (Revised 2026-07-07) |
| [0002](adr/stream/0002-kafka-event-publishing.md) | Reactive Kafka publisher with at-most-once delivery, deferred DLQ/outbox concerns |
| [0003](adr/stream/0003-categories-tags.md) | Managed `stream_category` lookup table + `TEXT[]` tags with GIN index |
| [0004](adr/stream/0004-srs-webhook-publish-token.md) | SRS webhook integration with JWT publish token, srsName/SHA-256 lookup, Sol3 contextual expiry (to be revised: single-TTL + Sol3 decisions) |
| [0005](adr/stream/0005-stream-thumbnails.md) | Stream thumbnails via SRS auto-snapshot; field+display in 2.5, capture in 2.9; custom-upload via MinIO deferred (Proposed) |
| [0007](adr/stream/0007-public-channel-read-and-channel-service-seam.md) | Authenticated channel read in stream-service with channel-service extraction seam; denormalized identity + safe cross-user projection + BroadcasterProfile (Accepted) |
| [0008](adr/stream/0008-view-count-analytics-pipeline.md) | View counting: Redis per-user Hash with dedup (Phase 1) → Kafka analytics pipeline (Phase 2). Includes IP fallback, self-view exclusion, `stream_view_event` analytics table, denormalized `views` column. (Accepted — Phase 1 implemented 2026-07-13) |
| [0009](adr/stream/0009-outbox-pattern.md) | Outbox pattern: transactional outbox table with `FOR UPDATE SKIP LOCKED` poller, at-least-once delivery replacing fire-and-forget. Notification consumer: Redis SETNX dedup + DLQ. (Accepted — implemented 2026-07-14, commits `2409fc1`–`142f32b`) |

#### 2.9 — SRS Snapshot Thumbnails ✅

**Status:** Implemented (2026-07-15).

**Why:** Phase 2.5 added the `thumbnail_url` field and UI display, but nothing populates
it — every card shows the placeholder. This item generates the actual thumbnail from the
live feed.

**What was implemented:**
- SRS container watchdog (`thumbnail-watchdog.sh`) polls SRS API every 30s, runs bundled ffmpeg (`/usr/local/srs/objs/ffmpeg/bin/ffmpeg`) to grab one frame per active stream
- Container entrypoint (`entrypoint.sh`) starts SRS + watchdog as background processes
- Thumbnail URL is deterministic: `{srsHlsHost}/thumbnails/{srsName}.jpg` — stream-service sets it immediately in `handlePublish()` after `goLive()`
- Compose mounts scripts directory, uses entrypoint as command
- Frontend `onerror` → placeholder fallback handles the ~30s gap before first frame (deferred to chat team)
- Single frame on start (not periodic refresh — sufficient for MVP)

**Depends on:** Phase 4.0 (SRS Docker service) — ✅ resolved

**ADR:** [0005](adr/stream/0005-stream-thumbnails.md) (Accepted 2026-07-15)

**Retrospective:** [srs-thumbnails-2.9-retrospective.md](plans/srs-thumbnails-2.9-retrospective.md)

**Validate:** stream goes LIVE → watchdog generates thumbnail within 30s → `thumbnail_url` populated → dashboard card shows real thumbnail instead of placeholder

---

## Phase 3 — Real-time Chat ⚡

**Status:** Done — all items complete. System messages, emoji picker, and @mentions shipped 2026-07-13.

**Goal:** Viewers in a stream room can chat. Messages persist to PostgreSQL with Redis as the hot cache. Room lifecycle is driven by stream events from Kafka.

### Architecture Decisions

Chat ADRs recorded across Phase 3 — see [docs/adr/chat/](adr/chat/):

| ADR | Decision |
|-----|----------|
| [0000](adr/chat/0000-architecture-foundation.md) | Layered reactive (`api/` → `application/` → `domain/` → `infrastructure/`) over DDD/Clean/Hexagonal — matches stream-service conventions |
| [0001](adr/chat/0001-cache-aside-redis-zset.md) | Cache-aside with Redis ZSET (epoch-millis scored) — PG is system of record, Redis is disposable hot cache |
| [0002](adr/chat/0002-jwt-derived-author-identity.md) | Author identity from JWT `sub` claim only — `SendMessageRequest` has no `author` field, eliminating impersonation |
| [0003](adr/chat/0003-cache-staleness-on-redis-restart.md) | Cache staleness on Redis restart — TTL + evict-on-reconnect + evict-on-write-failure |
| [0004](adr/chat/0004-two-layer-chat-authorization.md) | Two-layer chat authorization — PBAC capability (`ent`) + resource-state moderation (bans); grammar flattened in auth V10 |
| [0005](adr/chat/0005-moderation-domain-condition-triggered.md) | Moderation stays a chat kind — `moderation`-domain alternative is condition-triggered, not phase-deferred |
| [0006](adr/chat/0006-moderation-ux-capability-and-push-split.md) | Moderation UX split — shipped enforcement floor + deferred proactive push (Wave 2) |
| [0007](adr/chat/0007-proactive-push-infrastructure-gated.md) | Wave 2 proactive push is infrastructure-gated — Kafka broker ownership + notification-service foundation are hard prerequisites |
| [0008](adr/chat/0008-modify-ban-duration-viewerbanned-and-config-hardening.md) | Modify ban duration, `viewerBanned` on room metadata, config hardening (Kafka log noise, R2DBC pool, gateway timeouts) |
| [0009](adr/chat/0009-mention-precision-and-autocomplete.md) | @mention precision — backend is parsing authority, frontend autocomplete gates precision; three-tier suggestion system |

### Scaffold Artifacts

```
chat-service/src/main/java/com/streaming/chat/
├── api/
│   ├── ChatController.java              ← @AuthenticationPrincipal, delegates to ChatService
│   ├── ModerationController.java         ← GET/POST/DELETE /v1/rooms/{roomKey}/bans, gated by moderate
│   ├── JwtAttr.java                      ← static helpers: username(), verifiedStreamer() from JWT attr
│   ├── dto/
│   │   ├── SendMessageRequest.java       ← { @NotBlank String content } — no author field
│   │   ├── MessageResponse.java          ← from(ChatMessage, roomKey)
│   │   ├── RoomResponse.java             ← externalKey, status, createdAt, archivedAt, viewerCanModerate, viewerBanned
│   │   ├── BanRequest.java               ← { bannedSubject, bannedUsername, reason, durationSeconds }
│   │   ├── BanResponse.java              ← id, roomId, bannedSubject, bannedUsername, bannedBySubject, bannedByUsername, reason, createdAt, expiresAt
│   │   └── BanDurationRequest.java       ← { durationSeconds } for PATCH /bans/{subject}
│   └── error/
│       ├── ChatApiError.java             ← structured error envelope with code + message
│       └── ChatExceptionHandler.java     ← maps BanNotFoundException→404, UserBannedException→403 CHAT_USER_BANNED
├── application/
│   ├── ChatService.java                  ← cache-aside orchestration (PG-first writes, Redis-first reads) + sendSystemMessage()
│   ├── RoomService.java                  ← getOrCreate + archive (idempotent)
│   ├── ModerationService.java            ← ban, listBans, activeBans, bannedSubjects, updateBanDuration, unban
│   ├── BanSendGuard.java                 ← Layer-2 (resource-state): PG-direct active ban check per send
│   └── SendGuard.java                    ← @ConditionalOnMissingBean no-op seam
├── domain/
│   ├── ChatRoom.java                     ← entity → chat.chat_room, Persistable<UUID>
│   ├── ChatMessage.java                  ← entity → chat.chat_message, Persistable<UUID>; create() + createSystem()
│   ├── ChatBan.java                      ← entity → chat.chat_ban; isActive(now) instance method
│   ├── RoomStatus.java                   ← ACTIVE, ARCHIVED with wireValue()
│   └── MessageType.java                  ← NORMAL, SUPER_CHAT, SYSTEM enum
├── messaging/
│   ├── StreamControlListener.java        ← STREAM_CREATED→getOrCreate+system message, STREAM_ENDED→archive+evict+system message
│   └── StreamEvent.java                  ← record (eventType, streamId, broadcasterSubject)
├── security/
│   ├── ChatAuthorization.java            ← requireAccess() + hasCapability(); PBAC-COMMON-CANDIDATE
│   ├── EntitlementMatcher.java           ← JWT ent claim parser + policy evaluator; PBAC-COMMON-CANDIDATE
│   ├── AuthAction.java                   ← PBAC action enum
│   ├── AuthResourceDomain.java           ← PBAC domain enum
│   ├── AuthResourceKind.java             ← PBAC kind enum
│   └── RequiredAuthority.java            ← @PreAuthorize annotation stub
├── infrastructure/
│   ├── persistence/
│   │   ├── ReactiveChatRoomRepository.java       ← findByExternalKey, existsByExternalKey
│   │   ├── ReactiveChatMessageRepository.java    ← findByRoomIdOrderByCreatedAtDesc, findByRoomIdAndCreatedAtBeforeOrderByCreatedAtDesc
│   │   └── ReactiveChatBanRepository.java        ← findByRoomIdAndBannedSubject, findAllByRoomIdAndExpiresAfterOrNull
│   └── cache/
│       ├── RedisMessageCache.java        ← ZSET per room (key: chat:room:{roomKey}:recent), 100-msg cap
│       └── RedisReconnectListener.java   ← evict-on-reconnect (ADR-0003 mechanism #2)
└── config/
    ├── SecurityConfig.java               ← anyExchange().authenticated()
    ├── JwtProperties.java                ← JWT issuer + HMAC secret config
    ├── ChatAuthenticationEntryPoint.java ← 401 WWW-Authenticate suppression
    ├── ChatPbacProperties.java           ← chat.pbac.enabled flag (CHAT_PBAC_ENABLED)
    ├── ChatCacheProperties.java          ← chat.cache.room.ttl (CHAT_CACHE_ROOM_TTL)
    ├── GuardConfig.java                   ← @ConditionalOnMissingBean no-op SendGuard default
    ├── R2dbcConfig.java                   ← JSONB converters (SocialLinks R/W)
    ├── StringToMessageTypeConverter.java  ← R2DBC reading converter
    └── MessageTypeToStringConverter.java  ← R2DBC writing converter

V1__bootstrap_chat_schema.sql             ← chat schema + chat_room + chat_message tables
V2__add_room_status.sql                   ← status + archived_at on chat.chat_room
V3__chat_message_types_and_ban.sql         ← message_type, gift_amount, gift_currency, chat_ban table, broadcaster_subject
V4__fix_message_type_varchar.sql           ← CHECK constraint alignment
V5__chat_ban_usernames.sql                ← banned_username, banned_by_username on chat_ban
V6__add_message_mentions.sql              ← mentions TEXT[] column + GIN index on chat_message
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
- [x] 3.3 — Room lifecycle from stream events + schema V3 + identity enrichment + cache warm-up (2026-07-09)
- [x] 3.4 — PBAC enforcement + moderation (2026-07-11) — moderation (bans) live; PBAC enforcement built, **ships dark** (`chat.pbac.enabled=false`). Grammar mismatch **resolved** via auth `V10__flatten_chat_pbac_grammar.sql` (2026-07-11); enable at runtime after a token-refresh window (see 3.4 notes + [chat-pbac-grammar-fix-retrospective.md](plans/chat-pbac-grammar-fix-retrospective.md))
- [x] 3.5 — Frontend chat experience (extended: virtual scroll, lazy load, smart scroll, optimistic send + avatars + timestamps; remaining: unit tests + OnPush)
- [x] 3.6 — Cache integration testing (2026-07-11) — Testcontainers suite written (cache-aside, TTL, evict-on-reconnect, evict-on-write-failure); **not yet executed — needs Docker host**
- [x] 3.7 — Cache warm-up completion (`getMessagesBefore` backfill gap)

### Deferred Validation (Docker-gated)

- 3.3 integration test: `STREAM_CREATED` Kafka event → room exists in chat DB → send message succeeds
- 3.4 integration test: user without `send` entitlement on room gets `403 AUTHZ_DENIED`
- 3.6 Testcontainers suite execution (`RedisMessageCacheTest`, `ChatServiceTest`) — written, never run
- Frontend Karma specs for `ChatPanelComponent` and `ChatService` — written, never run (needs Chrome/Karma)

### Phase 3 Checklist

Built as two parallel agent tracks over a shared Phase 0 foundation; see
[docs/plans/chat-3.4-3.6-blueprint.md](plans/chat-3.4-3.6-blueprint.md).

**Two-layer authorization** (see [ADR-0004](adr/chat/0004-two-layer-chat-authorization.md); blueprint §"locked decisions"):
- **Layer 1 — PBAC capability** (`ent` in JWT, in-memory): `com.streaming.chat.security`
  package ported from stream-service, every file `PBAC-COMMON-CANDIDATE` for the 6.2
  extraction. `ChatAuthorization.requireAccess` guards send (`chat:message send`),
  read (`chat:message read`), history (`chat:message read_history`), moderation
  (`chat:moderation moderate`), owner = `room.broadcasterSubject`. Message reads are
  unified under the `chat:message` kind; `chat:room read` is room-metadata only.
- **Layer 2 — ban (resource-state)**: `chat_ban` table (V3), `BanSendGuard` plugged into
  the Phase-0 `SendGuard` seam; active/unexpired ban → `403 CHAT_USER_BANNED` (distinct
  `code` from PBAC's `403 AUTHZ_DENIED`). PG-direct lookup; Redis ban cache deferred
  (`OPT(scale)` marker).
- Moderation REST API: `GET/POST/DELETE /v1/rooms/{roomKey}/bans`, gated by `moderate`.

**✅ Grammar mismatch RESOLVED (2026-07-11)** — the original blocker (ported matcher parses
`domain:kind:` as a 2-segment prefix, but auth seeded 4-segment `chat:message:room:*` /
`chat:moderation:room:*`, so `send`/`read_history`/`moderate` never matched; and viewers had
no `send`, streamers no self-moderation) was fixed by **option (a): flatten the seed**.
`V10__flatten_chat_pbac_grammar.sql` rewrites the three chat policies to 3-segment resources
(`chat:message:*`, `chat:moderation:self|*`) and closes the coverage gaps; live master data +
role assignments were reconciled in the running DB. Matcher unchanged. See
[ADR-0004](adr/chat/0004-two-layer-chat-authorization.md) (grammar update + Risks),
[ADR-0005](adr/chat/0005-moderation-domain-condition-triggered.md), and the
[fix retrospective](plans/chat-pbac-grammar-fix-retrospective.md). Enforcement still ships dark
— flip `CHAT_PBAC_ENABLED` after a token-refresh window.

**Deferred (markers in code):** Redis ban cache (`OPT(scale)` in `BanSendGuard`);
periodic reconciliation sweep (`TODO(3.x-deferred)` in `ChatService.cacheWrite`, see
ADR-0003 §Deferred).

### 3.3 Implementation Notes (2026-07-09)

**What was built:**

| Item | Description |
|------|-------------|
| auth-service V9 | `username` registered in `catalog_subject_attribute`; emitted in JWT `attr` |
| chat-service V3 | `chat_message.author_username`, `author_avatar_url`, `gift_amount`/`gift_currency` (superchat shell); `chat_room.broadcaster_subject`; `chat_ban` table |
| Identity enrichment | `ChatService.sendMessage()` reads `attr.username` from JWT → stores as `author_username` on `ChatMessage`; `MessageResponse` includes all new fields |
| Kafka consumer | `StreamControlListener` — `STREAM_CREATED` → `RoomService.getOrCreate()`, `STREAM_ENDED` → archive + cache eviction; `ChatService.sendMessage()` now requires a pre-existing room |
| Cache warm-up (3.7) | `getMessagesBefore()` PG fallback now backfills Redis asynchronously |
| Frontend identity | DiceBear avatars from `authorUsername`; display name preference; timestamps with hover UTC tooltip; system message rendering; superchat bubble style |

**What was deferred (schema ready, implementation later):**

| Feature | Schema Done? | Deferred To | Notes |
|---------|-------------|-------------|-------|
| Superchat (real payments) | ✅ `message_type`, `gift_amount`, `gift_currency` | Phase 5+ | Payment infra needed; `gift_amount` drives color intensity + pin duration; `giftMessage` dropped — redundant with `body` |
| User banning enforcement | ✅ `chat_ban` table | 3.4 | ✅ Done — `BanSendGuard` + moderation REST endpoints shipped 2026-07-11 |
| System messages (producer) | ✅ `message_type = SYSTEM` | 3.4 or later | ✅ Done — `ChatMessage.createSystem()`, `ChatService.sendSystemMessage()`, `StreamControlListener` wiring shipped 2026-07-14 |
| @mentions | ✅ V6 migration (`TEXT[] mentions`) | Later | ✅ Done — backend regex parsing + `getParticipants` endpoint + p-autocomplete UI + three-tier suggestions shipped 2026-07-14 |
| Emoji input | ✅ (no schema needed) | Later | ✅ Done — p-overlayPanel + 50-emoji EMOJI_LIST + cursor save/restore shipped 2026-07-14 |
| Reply threading | ❌ (needs `parent_message_id`) | Later | Schema impact review needed |
| Real avatar upload (MinIO) | ❌ | Phase 4+ | ADR-0006; DiceBear is the fallback for now |
| `ChangeDetectionStrategy.OnPush` | N/A | — | ✅ Done — applied to ChatPanelComponent 2026-07-13 |
| Unit tests for ChatPanelComponent | N/A | — | ✅ Done — `chat-panel.component.spec.ts` + `chat.service.spec.ts` written 2026-07-14 (compile-only gate; Karma needs Chrome) |

### How to Resume (cold start)

1. Read [chat ADR-0000](adr/chat/0000-architecture-foundation.md) — architecture + third-party catalog
2. Read this checklist — what's done vs. remaining
3. V3 migration includes all deferred schema; no ALTER needed later
4. DiceBear avatar is the fallback; real avatars need `author_avatar_url` populated at write time (same pattern as `author_username`)
5. Stream team's Phase A (`username` in JWT) was implemented by chat team (auth-service V9); stream team owns aligning their plan + the `broadcaster_username` denormalization in stream-service

---

## Phase 4 — Viewer Experience ✅

**Status:** Complete — browse page, watch page, HLS player, SRS compose, chat panel integration, viewer presence (SSE push + frontend display), and heartbeat harvest service all shipped. Commits: `6cec5c5`–`d485685` (2026-07-17).

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
| 4.1 | **Frontend: Browse/discovery page** — list live streams with thumbnails, filter by category, search. Channel page (`/@username`) is already built (2.5b) — this page links into it. | 2.3, 2.5b |
| 4.2 | **Frontend: Stream viewing page** — HLS player (hls.js), embedded chat panel (`ChatPanelComponent` already built), stream info sidebar | 2.4, 3.5, 4.1 |
| 4.3 | **Playback URL generation** — stream service returns HLS URL per stream, gateway proxies or redirects to SRS | 2.4 |
| 4.4 | **Viewer count / presence** — Redis-based ephemeral presence per room (`SETEX` with TTL), shown in UI | 4.2 |

### Phase 4 Checklist

- [x] 4.0 — Infrastructure: SRS Docker Compose service + health check + RTMP/HLS verification (root `compose.yaml` with all infra services, `57c55b7`)
- [x] 4.1 — Browse/discovery page (cursor pagination, category filter, `e9ded9d`)
- [x] 4.2 — Stream viewing page (HLS player via hls.js + embedded chat panel, `5f7c1b3`, `734f466`)
- [x] 4.3 — Playback URL generation (HLS `.m3u8` URL in `PublishKeyResponse`, wired in watch page)
- [x] 4.4 — Viewer presence (REST heartbeat + viewer count endpoints ✅; SSE push + frontend display + session card counts ✅ — see [Option A blueprint](plans/option-a-viewer-presence-fanout-blueprint.md) A1; implemented 2026-07-17)
- [x] 4.4b — Heartbeat harvest service (time-series viewer analytics, minute-bucket aggregation ✅ — see [ADR-0010](adr/stream/0010-viewer-heartbeat-analytics-pipeline.md) and [Option A blueprint](plans/option-a-viewer-presence-fanout-blueprint.md) A4; implemented 2026-07-17)

---

## Phase 5 — Notifications ✅

**Status:** Complete — notification-service foundation: domain entity, persistence, REST API (cursor-paginated bell list, unread count, mark-as-read, mark-all-as-read), SSE delivery (`SseConnectionRegistry`, 30s heartbeat, gateway timeout exclusion), Kafka consumer with Redis SETNX dedup + DLQ (3-retry backoff), subscription model (follow/unfollow with DB-constraint idempotency, polymorphic targets), delivery preferences (per-channel toggles), `NotificationDispatcher` facade (persist → SSE → outbox), outbox + email skeleton (mirrors stream-service `FOR UPDATE SKIP LOCKED`). Frontend: toast/bell prebuild (5.0), follow button on channel page, bell dropdown with history + mark-all-as-read, notification settings page (5.4). Follower fan-out ✅ — `StreamControlListener.onStreamStarted()` wired with `getSubscribers()` → `createForFollower()` → `deliverToMany()` (inline for MVP). Dedup key scoping ✅ — `dedup:{topic}:{consumerGroupId}:{eventId}` per ADR common/0003. **Deferred:** email template rendering, subscribe (paid membership) button.

**Goal:** Users get notified about followed streamers going live, chat mentions, moderation actions, etc. The notification service is the platform's general notification hub — moderation push is its first client, not its only shape.

### Architecture Decisions

Notification ADR — see [docs/adr/notification/](adr/notification/):

| ADR | Decision |
|-----|----------|
| [0000](adr/notification/0000-architecture-foundation.md) | Layered reactive (`api/` → `application/` → `domain/` → `infrastructure/`) matching stream/chat conventions. Two inbound channels (Kafka consumers per topic), two outbound channels (REST + SSE). General hub pattern — moderation is first client, not only shape. |
| [0001](adr/notification/0001-subscription-model-and-notification-boundary.md) | **Accepted.** Table split: `notification_preference` (delivery) + `subscription` (polymorphic follow targets). Notification projection boundary — service stores "who wants notifications about X," not Follow vs Subscribe tiers. Implemented 2026-07-16. |
| [0002](adr/notification/0002-notification-delivery-architecture.md) | **Accepted.** Concrete `NotificationDispatcher` facade (persist → SSE → outbox). Outbox-driven email + fan-out (designed now, inline for MVP). DB constraint for subscription idempotency. Implemented 2026-07-16. |
| [common/0003](adr/common/0003-cross-service-event-dedup-key-scoping.md) | **Accepted.** Cross-service event dedup key scoping — `dedup:{topic}:{consumerGroupId}:{eventId}`. Implemented 2026-07-17. |

### Work Items

| # | Item | Depends on |
|---|------|-----------|
| 5.0 | ✅ **Frontend: Toast + notification card prebuild** — `ToastService`, `NotificationService` scaffold, `NotificationCard` molecule (reusable toast + bell card), `UserProfilePicture` atom, `NotificationToast` host (bottom-right with sound), `NotificationBell` header button. DTO aligned with notification-service plan (category + action-based). Mock-triggerable via bell click. See [retrospective](plans/notification-toast-infrastructure-retrospective.md). | — |
| 5.0b | ✅ **Kafka consumer infrastructure** — Redis SETNX dedup (24h TTL) + DLQ with 3-retry backoff, routes 5 event types (`STREAM_STARTED`, `STREAM_ENDED`, `STREAM_CREATED`, `STREAM_SCHEDULED`, `STREAM_CANCELLED`). `StreamEvent` relocated to `com.streaming.common.messaging` for cross-service reuse. Handlers are stubs (log only). Commit range: `142f32b`, `496c162`. | 2.3 |
| 5.1a | ✅ **Notification core (domain + persistence + REST)** — `Notification` entity with V2 migration, `NotificationCategory` enum with R2DBC converters, `ReactiveNotificationRepository` (cursor pagination, unread count, ownership-scoped), `NotificationService` (createFromStreamEvent, getNotifications, markAsRead, getUnreadCount), REST API (`GET /v1/notifications`, `GET /v1/notifications/unread-count`, `POST /v1/notifications/{id}/read`), error handling (`NotificationApiError`, `NotificationExceptionHandler`). See [retrospective](plans/notification-foundation-5.1-retrospective.md). 15 files — 13 created, 2 modified. | — |
| 5.1b | ✅ **Subscription + preference + dispatcher + outbox + email** — Schema redesign: split `channel_subscription` → `notification_preference` (delivery preferences) + `subscription` (polymorphic follow targets: `target_type` + `target_id`). Concrete `NotificationDispatcher` facade (persist → SSE → outbox). Outbox-driven email via Spring Mail + `OutboxPoller` (mirrors stream-service `FOR UPDATE SKIP LOCKED`). Follow/Unfollow REST API with DB-constraint idempotency. Fan-out architecture designed (outbox-driven `FanOutJob`), inline for MVP. See [ADR-0001](adr/notification/0001-subscription-model-and-notification-boundary.md), [ADR-0002](adr/notification/0002-notification-delivery-architecture.md), [blueprint](plans/notification-5.1b-subscription-dispatcher-blueprint.md), [design retro](plans/notification-5.1b-design-session-retrospective.md), [implementation retro](plans/notification-5.1b-implementation-retrospective.md). 20 new files, 5 modified. Implemented 2026-07-16. | 5.1a |
| 5.2a | ✅ **StreamControlListener → NotificationService wiring** — Replaced 5 stub handlers with `notificationService.createFromStreamEvent()` calls. STREAM_STARTED/STREAM_ENDED persist notifications; STREAM_CREATED/SCHEDULED/CANCELLED are debug-level no-ops. Injected `NotificationService` into `StreamControlListener`. | 2.3, 5.1a |
| 5.2b | **Follower fan-out + SSE delivery** — ~~subscription lookup on stream events → notify all followers~~ (deferred); ✅ `SseConnectionRegistry` (in-memory, multi-tab `CopyOnWriteArraySet<Sinks.Many>`), ✅ `GET /v1/notifications/stream` (`text/event-stream`, JWT-scoped, 30s heartbeat), ✅ `NotificationService` wired to push via SSE after persist, ✅ gateway per-route `response-timeout: -1`, ✅ frontend DTO reconciliation + REST wiring + SSE `fetch-event-source` connection + bell unread badge | 5.1b, 5.2a |
| 5.3 | **Email adapter** — (Merged into 5.1b — outbox-driven email via Spring Mail, dispatched by `OutboxPoller`) | 5.1b |
| 5.4 | **Frontend: Notification settings + follow button + bell dropdown** — Channel page Follow button (stateful: loading/following/follow, checkSubscription on load, optimistic UI), notification bell dropdown (cursor-paginated history, skeleton/empty/load-more states, mark-as-read), notification settings page (`/settings/notifications`, lazy-loaded, following list + delivery channel toggles), subscription service (8 HTTP methods for subscription + preference APIs), `broadcasterSubject` added to channel identity endpoint (ADR-0007 amendment). Wire click actions on notification cards (`stream.started` → `/channel/:id`). See [blueprint](plans/notification-5.4-frontend-subscription-ui-blueprint.md), [retrospective](plans/notification-5.4-frontend-implementation-retrospective.md). 9 new files, 11 modified. Implemented 2026-07-16 (uncommitted). | 5.1 |

### Phase 5 Checklist

- [x] 5.0 — Frontend toast + notification card + bell prebuild (uncommitted, on `feat/chat-moderation-ux`)
- [x] 5.0b — Kafka consumer infrastructure: Redis SETNX dedup + DLQ + event routing (`142f32b`, `496c162`)
- [x] 5.1a — Notification core: domain + persistence + REST API (2026-07-15)
- [x] 5.1b — Subscription + preference CRUD + dispatcher + outbox + email adapter (implemented 2026-07-16; see [ADR-0001](adr/notification/0001-subscription-model-and-notification-boundary.md), [ADR-0002](adr/notification/0002-notification-delivery-architecture.md), [blueprint](plans/notification-5.1b-subscription-dispatcher-blueprint.md), [implementation retro](plans/notification-5.1b-implementation-retrospective.md))
- [x] 5.2a — StreamControlListener → NotificationService wiring (2026-07-15)
- [x] 5.2b — SSE delivery (2026-07-15): `SseConnectionRegistry` + SSE controller + gateway timeout + frontend wiring
- [x] 5.2b — Dedup key scoping refactor — changed unscoped `dedup:stream-event:{eventId}` to scoped `dedup:{topic}:{consumerGroupId}:{eventId}` per [ADR common/0003](adr/common/0003-cross-service-event-dedup-key-scoping.md). Implemented 2026-07-17.
- [x] 5.2b — Follower fan-out (subscription lookup on STREAM_STARTED → notify all followers). Architecture designed in ADR-0002 §4; inline for MVP, outbox-driven at scale. Implemented 2026-07-17.
- [x] 5.3 — Email adapter skeleton (merged into 5.1b — outbox-driven email via Spring Mail, `EmailAdapter` skeleton, actual SMTP dispatch deferred to Phase 5.3 proper)
- [x] 5.4 — Frontend notification settings + follow button + bell dropdown (implemented 2026-07-16; see [blueprint](plans/notification-5.4-frontend-subscription-ui-blueprint.md), [retrospective](plans/notification-5.4-frontend-implementation-retrospective.md))
- [x] 5.4b — mark-all-as-read button (backend: `POST /v1/notifications/mark-all-read` bulk endpoint `d96bed3`; frontend: button in dropdown `a8b708d`)

---

## Phase 6 — Production Hardening ⚡

**Status:** In Progress — Redis infrastructure hardened, structured logging deployed, refresh tokens migrated to Redis. Kafka outbox pattern ✅ — transactional outbox with `FOR UPDATE SKIP LOCKED` poller shipped (`2409fc1`, `81ec12a`). Notification consumer idempotency (Redis SETNX + DLQ) shipped (`142f32b`). Idempotency keys ✅ — gateway `IdempotencyFilter` + frontend `IdempotencyService` + auth interceptor retry (5 commits, `741237d`..`cb7144c`). Shared pbac-common ✅ — extracted into Gradle submodule (`2f71586`). Authorization gap remediation ✅ — 7 gaps closed across 5 services (`4eeb252`..`20685e9`). Remaining items (6.3–6.6) are planned but not started.

**Goal:** The platform is safe, scalable, and maintainable for production use.

### Work Items

| # | Item | Depends on |
|---|------|-----------|
| 6.0a | ✅ **Redis infrastructure hardening** — pinned image (7.2.4-alpine), AOF+RDB persistence, password auth, memory limits (256MB allkeys-lru), RedisInsight (2.44.0), json-file log rotation, restart policy | — |
| 6.0b | ✅ **Refresh token → Redis migration** — replaced JPA pessimistic-lock rotation with atomic Lua script; deleted RefreshTokenEntity/Repository/MaintenanceService; auth-service now uses Redis as primary store for ephemeral credentials. See [ADR auth/0001](adr/auth/0001-redis-refresh-token-storage.md) | 6.0a |
| 6.0c | ✅ **Kafka outbox pattern** — transactional outbox (`outbox` table in same TX as entity change), `OutboxPoller` with `FOR UPDATE SKIP LOCKED` and fixed-delay scheduling, at-least-once delivery replacing fire-and-forget. See [ADR stream/0009](adr/stream/0009-outbox-pattern.md). Notification consumer: Redis SETNX dedup (24h TTL) + DLQ with 3-retry backoff. Commit range: `2409fc1`–`142f32b`. | 2.3 |
| 6.1 | ✅ **Idempotency keys** — gateway `IdempotencyFilter` (WebFilter, @Order(3), fail-open, 2xx-only, Base64 body), frontend `IdempotencyService` (`newKey()` + static `options()`), 5 services + 8 components wired, auth interceptor POST retry enabled. See [blueprint](blueprints/phase-6.1-idempotency-keys.md), [pattern doc](IDEMPOTENCY-PATTERN.md). Commits: `741237d`..`cb7144c` (5 commits, 26 files). | — |
| 6.2 | ✅ **Shared `pbac-common` library** — extracted duplicated `JwtProperties` + `ReactiveJwtDecoder` + `EntitlementMatcher` + `Structured401AuthenticationEntryPoint` from stream/chat/notification/gateway into a shared Gradle module (`pbac-common/`). Commit: `2f71586` (46 files, net -657 lines). | 2.2 |
| 6.2a | ✅ **Authorization gap remediation** — closed 7 gaps (1 CRITICAL, 3 HIGH, 3 MEDIUM): SRS on_unpublish token validation, chat PBAC enabled-by-default with prod hard-fail, watch endpoint PBAC, participants endpoint PBAC, 403 handler for StreamAccessDeniedException, gateway webhook POST-only scoping, internal token audit logging. Commits: `4eeb252`..`20685e9` (6 commits, 13 files). See [retrospective](docs/plans/authorization-gap-remediation-retrospective.md). | 6.2 |
| 6.3 | ✅ **Rate limiting** — gateway-level sliding-window-log rate limiter (`RateLimitFilter` @Order(2), Redis ZSET + Lua script, 200 req/60s per IP, fail-open, 429 structured JSON). See [retrospective](docs/plans/phase-6.3-rate-limiting-retrospective.md) and [ADR common/0002](adr/common/0002-redis-ephemeral-data-store.md). Uncommitted (3 new, 3 modified). | 6.0a |
| 6.4 | ✅ **WebSocket upgrade for chat** — full-duplex WebSocket (send + receive) with Redis Pub/Sub fan-out, REST fallback. Raw WebFlux `ReactiveWebSocketHandler` (not STOMP). Gateway WS route with `response-timeout: -1`. Frontend `ChatWebSocketService` with exponential backoff reconnect. Commits: `37ab6c8`, `dd896ad`, `fa16ae9`. See [ADR-0010](adr/chat/0010-websocket-real-time-messaging.md). | 3.5 |
| 6.5 | 🔵 **Security hardening** (DEFERRED 2026-08-01) — TLS everywhere, secrets management (env vars → vault), CSP headers, CSRF audit, dependency CVE scanning. Production hardening — revisit before deployment. | — |
| 6.6 | 🔵 **Observability** (DEFERRED 2026-08-01) — ~~structured JSON logging~~ ✅, Micrometer Tracing (traceId/spanId propagation), Micrometer metrics (Prometheus), Grafana dashboard, centralized log backend (Loki or ELK). Production hardening — revisit before deployment. | — |

### Phase 6 Checklist

- [x] 6.0a — Redis infrastructure hardening
- [x] 6.0b — Refresh token → Redis migration
- [x] 6.0c — Kafka outbox pattern (stream-service) + consumer idempotency + DLQ (notification-service)
- [x] 6.1 — Idempotency keys (`741237d`..`cb7144c`)
- [x] 6.2 — Shared `pbac-common` library (`2f71586`)
- [x] 6.2a — Authorization gap remediation (`4eeb252`..`20685e9`)
- [x] 6.3 — Rate limiting (uncommitted — 3 new files, 3 modified)
- [x] 6.4 — WebSocket chat (`37ab6c8`, `dd896ad`, `fa16ae9`)
- [ ] 6.5 — Security hardening 🔵 DEFERRED (2026-08-01)
- [ ] 6.6 — Observability 🔵 DEFERRED (2026-08-01)
  - [x] Structured JSON logging (logstash-logback-encoder, all 6 services, [ADR common/0001](adr/common/0001-structured-json-logging.md), [LOGGING-ARCHITECTURE.md](LOGGING-ARCHITECTURE.md))
  - [x] Logging architecture documentation ([LOGGING-ARCHITECTURE.md](LOGGING-ARCHITECTURE.md), [TRACE-PROPAGATION.md](TRACE-PROPAGATION.md))
  - [x] `streaming.service.instance-id` — unified instance identity across Eureka + logs
  - [x] Local dev profile — human-readable logs via `SPRING_PROFILES_ACTIVE=local`
  - [ ] Micrometer Tracing (traceId/spanId propagation across HTTP + Kafka) 🔵 DEFERRED
  - [ ] Micrometer metrics (Prometheus endpoint) 🔵 DEFERRED
  - [ ] Grafana dashboard 🔵 DEFERRED
  - [ ] Centralized log backend (Loki/Grafana or ELK) 🔵 DEFERRED

---

### 6.7 — Gap Remediation (Phase 2-5 Production Hardening) ⚡

**Status:** Active — see [remediation blueprint](plans/phase-2-5-gap-remediation-blueprint.md) and [gap analysis retrospective](plans/phase-2-5-production-gap-analysis-retrospective.md)

**Why:** A 6-agent production readiness audit (2026-07-28) identified 50+ gaps across phases 2-5:
8 CRITICAL, 16 HIGH, 19 MEDIUM, 7 LOW, plus 8 systematic weaknesses. Phases 2-5 are functionally complete but not production-ready.

**Goal:** Close the delta between "checkbox done" and "production ready" before any deployment.

#### Remediation Tracks

| Track | Focus | Effort | Priority |
|-------|-------|--------|----------|
| A — Security Triage | Remove hardcoded secrets, add catch-all exception handlers, restrict health endpoints, rotate compromised keys | 1 day | 🔴 CRITICAL |
| B — Outbox & State Machine Integrity | Wire `@Transactional`, fix SCHEDULED cancel gap, fix double-subscribe in chat consumer, fix `goLiveFromSchedule()` bypass | 1 day | 🔴 CRITICAL |
| C — Notification Hardening | Add smoke tests, fix fan-out blocking, implement real email or remove skeleton, fix `OutboxService.enqueue()` fire-and-forget | 2-3 days | 🔴 CRITICAL |
| D — Observability Foundation | Wire Micrometer metrics + Prometheus endpoint, wire trace propagation, add Grafana dashboard scaffold | 2-3 days | 🟠 HIGH |
| E — Infrastructure Maturity | Redis Lua scripting, disable auto-create-topics, connection pool config, SCAN limits, SSE buffer bounds + zombie drain | 1-2 days | 🟠 HIGH |
| F — Error Handling Standardization | Catch-all handlers in 3 services, WARN logging for security events, exception-passing convention (`.getMessage()` → pass `ex`), shared base handler in pbac-common | 1 day | 🟠 HIGH |
| G — Test Execution | CI Docker-based test suite, minimum smoke tests for notification-service, verify existing chat/stream tests pass | Ongoing | 🟡 MEDIUM |

#### Phase 6.7 Checklist

- [ ] Track A — Security triage (skipped per user directive — pet project; hardcoded secrets acceptable risk)
- [x] Track B — Outbox & state machine integrity ✅ **Tier 1 complete** (B1: R2DBC TransactionalOperator wired; B2: SCHEDULED cancel gap fixed; B3: goLiveFromSchedule bypass fixed; B4: dropped — false alarm; B5: chat consumer refactored to Mono<Void> + blockOptional)
- [x] Track C — Notification hardening ✅ **Tier 1 complete** (C2: fan-out offloading via subscribeOn(boundedElastic); C4: OutboxService.enqueue() returns Mono<Void>; C5: DTO validation + catch-all + WebExchangeBindException handlers)
- [ ] Track D — Observability foundation 🔵 DEFERRED (2026-08-01)
- [ ] Track E — Infrastructure maturity (E3: Redis SCAN chunked; E4: SSE backpressure bounds) 🔵 DEFERRED (2026-08-01)
- [ ] Track F — Error handling standardization (F1: logging in handlers; F2: getMessage() → pass ex; F3: shared base handler in pbac-common) 🔵 DEFERRED (2026-08-01)
- [ ] Track G — Test execution pipeline 🔵 DEFERRED (2026-08-01)
- [x] Track A partial — A6: SRS webhook shared-secret validation (query-param secret) ✅
- [ ] Track A remaining — A1-A5 skipped (pet project)

**Tier 1 retrospective:** [phase-6.7-tier1-retrospective.md](plans/phase-6.7-tier1-retrospective.md)

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
| MinIO object storage | S3-compatible blob store for custom stream cover-art upload (ADR-0006, planned) and future VOD assets |
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
- [PRIMENG-MENU-STABLE-REFERENCE.md](PRIMENG-MENU-STABLE-REFERENCE.md) — PrimeNG menu stable array reference pattern
- [docs/adr/insight/](adr/insight/) — AI/LLM layer ADRs (all Proposed)
