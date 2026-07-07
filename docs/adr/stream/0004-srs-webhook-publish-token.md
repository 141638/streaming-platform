# ADR-0004: SRS Webhook Integration & Publish Token Architecture

**Status:** Accepted (Revised 2026-07-08)
**Date:** 2026-07-07
**Domain:** Stream Service

## Context

When a streamer starts OBS, the media flows through SRS (Simple Realtime Server) via RTMP. SRS must validate that the streamer is authorized to publish before accepting the connection. Additionally, the stream service must detect when a stream starts and ends (to transition state and fire Kafka events).

Prior architecture discussions considered:
- Using the stream key hash alone for validation (vulnerable to key leakage)
- Proxying HLS playback through stream-service (unnecessary bandwidth)
- Service-account JWT for SRS webhook authentication (SRS is a C++ server, not an OAuth2 client)

### Revision Notes (2026-07-08)

During Phase 2.4b-e implementation, three design gaps were discovered:

1. **Dual TTL is unreachable**: OBS only ever receives one token (issued at DRAFT creation). No automated mechanism delivers a status-aware "LIVE token" to OBS — `GET /publish-key` requires manual copy-paste. A separate LIVE TTL serves no purpose.
2. **Unbounded stream duration**: Sol2 (long TTL) just kicks the can down the road — streams can run for days. The JWT expiry should not be the limiting factor on stream length.
3. **srsName rotation kills playback**: If `GET /publish-key` rotates the srsName during LIVE, the HLS URL changes and every viewer's player dies.

These drove the Sol3 contextual expiry model and srsName-stable rotation documented below.

## Decision

### 1. Three Separate Identifiers

| Identifier | Scope | Storage | Format |
|-----------|-------|---------|--------|
| **Stream ID** | Public REST API | `stream_session.id` | UUID (e.g., `abc123-def-...`) |
| **SRS Stream Name** | RTMP/HLS URLs | Plaintext in `stream_session.srs_name` + SHA-256 in `stream_session.stream_key_hash` | UUID without dashes (e.g., `a1b2c3d4e5f6...`) |
| **Publish Token** | RTMP auth | Never stored (self-validating JWT) | HS256 JWT with `exp`, `sub`, `streamId`, `srsName` |

The stream ID is public (visible in REST URLs, notifications). The SRS stream name is semi-private (embedded in RTMP/HLS URLs; harder to discover but visible in network traffic). The publish token is the cryptographic proof of authorization.

**Why store srsName as plaintext?** SHA-256 is irreversible. Without the plaintext, `GET /publish-key` cannot reconstruct the RTMP/HLS URLs. The `srs_name` column (added in V5) stores the UUID alongside the hash.

### 2. Publish Token (JWT in RTMP Query String)

A JWT embedded in the RTMP URL as a query parameter:

```
rtmp://srs:1935/live/a1b2c3d4-...?token=eyJhbGciOiJIUzI1NiJ9...
                       └── srsName ──┘ └──── publish token ────┘
```

**Token claims:**
```json
{
  "sub": "streamer-uuid",
  "streamId": "abc-123",
  "srsName": "a1b2c3d4-...",
  "exp": 1760000000,
  "iat": 1759992800
}
```

**Token TTL — single 2 hours:**

| Stream Status | TTL | Rationale |
|--------------|-----|-----------|
| Any | 2 hours | OBS only ever receives one token (issued at DRAFT creation). A separate LIVE TTL would never be delivered to OBS — the only way OBS gets a new token is manual key rotation via the dashboard. The TTL gates the initial DRAFT→LIVE authorization window; reconnects are handled by Sol3. |

**Why not dual TTL (2h DRAFT / 15m LIVE)?** During implementation review it was discovered that the "LIVE token" is unreachable: nothing in the system automatically re-issues a token when DRAFT→LIVE transition occurs. OBS holds the original DRAFT-issued token for the entire lifecycle. A shorter LIVE TTL would only cause reconnects to fail without a corresponding mechanism to deliver the refreshed token to OBS.

### 3. Sol3: Contextual Expiry Validation

The publish token's `exp` claim is enforced **contextually** based on stream status:

| Stream Status | exp enforced? | Rationale |
|--------------|--------------|-----------|
| DRAFT | **Yes** | Authorization gate — a leaked token can only start a new stream within the TTL window |
| LIVE | **No** | Reconnect gate — the stream was already authorized when the token was fresh. Skipping exp allows unbounded stream duration and OBS reconnection with the original token. The DB status check (`WHERE status = 'live'`) is the authoritative gate. |
| ENDED / CANCELLED | N/A | Rejected before expiry is checked (DB status check) |

This is called **Sol3** in the design discussions. It cleanly separates two concerns:
- **Authorization** (should this streamer be allowed to start?) — governed by TTL
- **Session validity** (is this stream still active?) — governed by DB status

**Why not Sol2 (long TTL, e.g. 24h-7d)?** Stream duration is unbounded — a stream could run for days or weeks. Any fixed TTL is arbitrary. Sol3 eliminates the TTL as a constraint on stream length entirely.

### 4. Key Rotation

`POST /streams/{id}/publish-key` rotates the publish key. Behavior depends on stream status:

| Stream Status | srsName | Publish JWT | Rationale |
|--------------|---------|-------------|-----------|
| DRAFT | **New UUID** | **New JWT** | No active viewers — full rotation is safe |
| LIVE | **Unchanged** | **New JWT** | Rotating srsName changes the HLS URL → kills playback for all current viewers. Only the JWT is refreshed. |

`GET /streams/{id}/publish-key` returns the current key info with the token masked as `****`. Only `POST` reveals the raw token.

**Why not always rotate srsName?** During LIVE, the HLS playback URL is `http://srs:8080/live/{srsName}.m3u8`. Changing srsName instantly breaks every viewer's stream. JWT-only rotation preserves playback while still allowing the streamer to invalidate a leaked token.

### 5. Webhook Flow

```
OBS ──RTMP──→ SRS
               │
               │ SRS parses: app="live", stream="a1b2c3d4-...", param="token=eyJ..."
               │ SRS does NOT validate the token. It passes everything through.
               │ SRS pauses RTMP acceptance until webhook responds.
               │
               ▼
SRS ──POST /api/streams/v1/webhooks/srs/on_publish──→ Gateway ──→ Stream Service
  Body: {
    "action": "on_publish",
    "stream": "a1b2c3d4-...",        ← SRS stream name from RTMP path
    "param":  "token=eyJ..."          ← query string from RTMP URL
  }
               │
               ▼
Stream Service validation (Sol3):
  1. Extract publish token from body.param
  2. Verify JWT signature (HS256, same HMAC key as user tokens)
  3. Check srsName in JWT matches body.stream
  4. SHA-256(body.stream) → look up stream_key_hash in DB
  5. Verify stream exists and status is DRAFT or LIVE (reject ENDED/CANCELLED)
  6. If DRAFT: enforce exp → goLive(), fire STREAM_STARTED
  7. If LIVE: skip exp → return 200 (reconnect, no state change)
  8. Return 200 OK
               │
               ▼
SRS receives 200 → accepts RTMP → starts HLS encoding
SRS receives non-200 → rejects RTMP → OBS shows connection error
```

**On unpublish (OBS stops):**
```
SRS ──POST /api/streams/v1/webhooks/srs/on_unpublish──→ Gateway ──→ Stream Service
  → If LIVE: entity.end(), fire STREAM_ENDED
  → If non-LIVE: idempotent no-op
  → Return 200
```

### 6. Webhook Routing & Authentication

**Routing:** Webhook calls go through the **gateway** (`/api/streams/v1/webhooks/**`), not directly to stream-service. Rationale: the gateway's Retry filter (3 retries with 50-500ms backoff) protects against transient stream-service unavailability — critical because SRS pauses RTMP acceptance until the webhook responds.

Gateway and stream-service security configs both permit `/v1/webhooks/**` without JWT validation. The webhook paths are in the gateway's public filter chain (`@Order(0)`).

**Authentication (Phase 2):** Publish JWT validation + internal Docker network isolation. The `X-Webhook-Secret` shared secret described in the original version of this ADR was deferred — SRS does not support custom HTTP headers in `http_hooks` natively. A shared secret in a query parameter would be logged in URLs. For Phase 2, the JWT publish token (validated by `PublishTokenService.validateForPublish()`) is the authentication mechanism. Both SRS and stream-service run on the internal Docker network.

**Future path (Phase 4+):** Deploy a lightweight sidecar in front of SRS that fetches a service-account JWT from auth-service and injects it as an `Authorization: Bearer` header on webhook requests. The existing service-account token infrastructure (`AccessTokenIssuanceService.issueForServiceAccount()`) is already built for this.

### 7. Playback URL

Viewers connect directly to SRS for video. The playback URL is returned in `PublishKeyResponse` (not `StreamResponse`):

```json
{
  "streamId": "abc-123",
  "srsName": "a1b2c3d4e5f6...",
  "rtmpUrl": "rtmp://srs:1935/live/a1b2c3d4e5f6?token=eyJ...",
  "playUrl": "http://srs:8080/live/a1b2c3d4e5f6.m3u8",
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresAt": "2026-07-08T12:00:00Z"
}
```

**Why PublishKeyResponse and not StreamResponse?** The srsName is only meaningful to the streamer (for OBS configuration) and viewers (for HLS playback). In Phase 2, viewer discovery doesn't exist yet, so exposing the HLS URL in stream list/detail responses is unnecessary. The streamer gets it from `GET /publish-key`. In Phase 4 (viewer discovery), the playback URL can be added to public stream responses.

Video data never passes through the stream service. SRS handles media delivery directly.

### 8. Publish Key at Creation Time

All streams created in DRAFT status receive a publish key immediately (reverses V3 migration that made `stream_key_hash` nullable):

```
POST /streams { title }  →  DRAFT + srsName + publish JWT (in entity, not response)
POST /streams { title, scheduledAt }  →  SCHEDULED + NO publish key

GET  /streams/{id}/publish-key → view key info (token masked)
POST /streams/{id}/publish-key → rotate key (DRAFT=full, LIVE=JWT-only)

SCHEDULED streams receive a key only via go-live:
POST /streams/{id}/go-live  →  SCHEDULED→DRAFT + fresh publish key
```

The V3 migration (`DROP NOT NULL on stream_key_hash`) is reversed via V5. The `stream_key_hash` column becomes NOT NULL, and a new `srs_name VARCHAR(36)` column stores the plain SRS stream name for URL reconstruction.

### Alternatives Considered

| Approach | Verdict | Reason |
|----------|---------|--------|
| Hash-only validation (no JWT) | Rejected | srsName is visible in HLS URLs; anyone who knows it could publish |
| Proxy HLS through stream-service | Rejected | Unnecessary bandwidth; stream service handles metadata, SRS handles media |
| Service-account JWT for SRS | Deferred | SRS cannot natively obtain/refresh JWTs; sidecar planned for Phase 4 |
| One-time key (no re-issuance) | Rejected | Streamer may lose key or need to reconnect; re-issuance is essential |
| srsName = streamId | Rejected | Stream ID is public in REST URLs; separate UUID limits discoverability |
| Dual TTL (2h DRAFT / 15m LIVE) | **Rejected during implementation** | OBS only ever receives one token at DRAFT creation. A LIVE-specific TTL never reaches OBS — nothing in the system auto-delivers a refreshed token on DRAFT→LIVE transition. The shorter TTL would cause reconnect failures without a corresponding delivery mechanism. |
| Long fixed TTL (Sol2: 24h-7d) | **Rejected during implementation** | Stream duration is unbounded — any fixed TTL is arbitrary and will fail for streams exceeding it. Shifts the problem rather than solving it. |
| **Sol3: contextual expiry** | **Accepted** | Enforce `exp` for DRAFT (authorization gate), skip `exp` for LIVE (reconnect gate). DB status check is authoritative. Enables unbounded stream duration with a reasonable TTL. |
| srsName-stable rotation during LIVE | **Accepted** | Full rotation (srsName + JWT) during LIVE kills HLS playback for all viewers. JWT-only rotation preserves playback while still allowing token invalidation. |

## Consequences

- **Positive**: Sol3 enables unbounded stream duration — the JWT TTL no longer limits how long a stream can run
- **Positive**: srsName-stable rotation means key rotation never interrupts active viewers
- **Positive**: Publish token is self-validating (JWT signature + claims) — no DB query needed for auth, only for stream lookup
- **Positive**: srsName is rotatable (in DRAFT) without affecting the public stream ID
- **Positive**: Video delivery is direct SRS→Browser; stream service never handles media
- **Positive**: Webhook auto-transitions (on_publish→goLive, on_unpublish→end) eliminate manual start/end in production flow
- **Positive**: Gateway routing provides retry resilience for webhook calls
- **Positive**: Plaintext `srs_name` column enables URL reconstruction without reversing SHA-256
- **Negative**: Sol3 breaks JWT semantic purity — `exp` is contextual rather than absolute. A token that is "expired" can still reconnect to a LIVE stream. Some security auditors may object.
- **Negative**: No shared secret on webhook endpoints in Phase 2 (mitigated by internal Docker network isolation + JWT publish token validation)
- **Negative**: srsName UUID is visible in HLS URLs (acceptable — it's a UUID, not a secret, and is rotatable in DRAFT)
- **Negative**: `stream_key_hash` is irreversible — DB-level queries by srsName go through the hash, not the plaintext

## References

- [ADR-0001: Stream State Machine (Revised)](0001-stream-state-machine.md)
- [ADR-0002: Kafka Event Publishing](0002-kafka-event-publishing.md)
- `PublishTokenService.java` — JWT issuance + Sol3 validation
- `SrsWebhookController.java` — webhook endpoints (on_publish, on_unpublish)
- `StreamService.goLiveFromSchedule()` — SCHEDULED→DRAFT go-live action
- `StreamService.handlePublish()` — Sol3 webhook handler
- `StreamService.handleUnpublish()` — idempotent end handler
- `V5__reverse_stream_key_hash_not_null.sql` — migration (NOT NULL + srs_name column)
- Gateway `SecurityConfig.java` — webhook paths in public filter chain
- Auth Service: `AccessTokenIssuanceService.issueForServiceAccount()` — future webhook auth
