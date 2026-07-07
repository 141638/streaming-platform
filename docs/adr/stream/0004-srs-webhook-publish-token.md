# ADR-0004: SRS Webhook Integration & Publish Token Architecture

**Status:** Accepted
**Date:** 2026-07-07
**Domain:** Stream Service

## Context

When a streamer starts OBS, the media flows through SRS (Simple Realtime Server) via RTMP. SRS must validate that the streamer is authorized to publish before accepting the connection. Additionally, the stream service must detect when a stream starts and ends (to transition state and fire Kafka events).

Prior architecture discussions considered:
- Using the stream key hash alone for validation (vulnerable to key leakage)
- Proxying HLS playback through stream-service (unnecessary bandwidth)
- Service-account JWT for SRS webhook authentication (SRS is a C++ server, not an OAuth2 client)

## Decision

### 1. Three Separate Identifiers

| Identifier | Scope | Storage | Format |
|-----------|-------|---------|--------|
| **Stream ID** | Public REST API | `stream_session.id` | UUID (e.g., `abc123-def-...`) |
| **SRS Stream Name** | RTMP/HLS URLs | SHA-256 stored in `stream_session.stream_key_hash` | UUID (e.g., `a1b2c3d4-...`) |
| **Publish Token** | RTMP auth | Never stored (self-validating JWT) | HS256 JWT with `exp`, `sub`, `streamId`, `srsName` |

The stream ID is public (visible in REST URLs, notifications). The SRS stream name is semi-private (embedded in RTMP/HLS URLs; harder to discover but visible in network traffic). The publish token is the cryptographic proof of authorization.

### 2. Publish Token (JWT in RTMP Query String)

A short-lived JWT embedded in the RTMP URL as a query parameter:

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

**Token TTL:**

| Stream Status | TTL | Rationale |
|--------------|-----|-----------|
| DRAFT | 2 hours | Streamer may take hours to set up OBS |
| LIVE | 15 minutes | Short window limits exposure if leaked; sufficient for OBS reconnect |

**Re-issuance:** `GET /streams/{id}/publish-key` generates a fresh SRS name + new publish token every call. Old SRS name is invalidated (key rotation). No limit on how many times this can be called while status is DRAFT or LIVE.

### 3. Webhook Flow

```
OBS ──RTMP──→ SRS
               │
               │ SRS parses: app="live", stream="a1b2c3d4-...", param="token=eyJ..."
               │ SRS does NOT validate the token. It passes everything through.
               │ SRS pauses RTMP acceptance until webhook responds.
               │
               ▼
SRS ──POST /v1/webhooks/srs/on_publish──→ Stream Service
  Body: {
    "action": "on_publish",
    "stream": "a1b2c3d4-...",        ← SRS stream name from RTMP path
    "param":  "token=eyJ..."          ← query string from RTMP URL
  }
               │
               ▼
Stream Service validation:
  1. Extract publish token from body.param
  2. Verify JWT signature (HS256, same HMAC key as user tokens)
  3. Check exp not passed
  4. Check srsName in JWT matches body.stream
  5. SHA-256(body.stream) → look up stream_key_hash in DB
  6. Verify stream exists, status is DRAFT or LIVE
  7. If DRAFT: entity.goLive(), fire STREAM_STARTED
  8. Return 200 OK
               │
               ▼
SRS receives 200 → accepts RTMP → starts HLS encoding
SRS receives non-200 → rejects RTMP → OBS shows connection error
```

**On unpublish (OBS stops):**
```
SRS ──POST /v1/webhooks/srs/on_unpublish──→ Stream Service
  → entity.end(), fire STREAM_ENDED
  → Return 200
```

### 4. Webhook Authentication

For Phase 2.4, the webhook endpoint is on the internal Docker network. Authentication uses:

**A shared secret** (`X-Webhook-Secret` header), set via environment variable on both SRS and stream-service.

```yaml
# docker-compose
stream-service:
  environment:
    SRS_WEBHOOK_SECRET: "${SRS_WEBHOOK_SECRET}"
```

SRS does not support custom HTTP headers in `http_hooks` natively, nor can it obtain/refresh JWTs. A shared secret is the pragmatic choice for Phase 2.4.

**Future path (noted for later ADR):** Deploy a lightweight sidecar/gateway in front of SRS that fetches a service-account JWT from auth-service (`POST /v1/internal/service-tokens` with `principalSubject: "svc:srs-webhook"`) and injects it as an `Authorization: Bearer` header on webhook requests. The existing service-account token infrastructure (`AccessTokenIssuanceService.issueForServiceAccount()`) is already built for this.

### 5. Playback URL

Viewers connect directly to SRS for video. The stream service returns the playback URL in `StreamResponse`:

```json
{
  "id": "abc-123",
  "status": "live",
  "playUrl": "http://srs:8080/live/a1b2c3d4-....m3u8",
  "rtmpUrl": null
}
```

Video data never passes through the stream service. The SRS stream name in the HLS URL is semi-private (a UUID, harder to discover than the public stream ID). For private streams in a future phase, an `on_play` webhook with a playback token can be added.

### 6. AuthAction for Webhook

`AuthAction.VALIDATE_PUBLISH("validate_publish")` already exists in the PBAC framework. The webhook controller does not use PBAC (it uses shared secret auth), but the internal call to `StreamService` for the transition uses this action for audit trail.

### 7. Publish Key at Creation Time

All streams created in DRAFT status receive a publish key immediately (reverses V3 migration that made `stream_key_hash` nullable):

```
POST /streams { title }  →  DRAFT + publish key (in response)
POST /streams { title, scheduledAt }  →  SCHEDULED + NO publish key

SCHEDULED streams receive a key only when the streamer clicks "Go Live":
POST /streams/{id}/go-live  →  SCHEDULED→DRAFT + fresh publish key
```

The V3 migration (`DROP NOT NULL on stream_key_hash`) is reversed via V5. The hash column becomes NOT NULL again because all DRAFT and LIVE streams always have a key.

### Alternatives Considered

| Approach | Verdict | Reason |
|----------|---------|--------|
| Hash-only validation (no JWT) | Rejected | srsName is visible in HLS URLs; anyone who knows it could publish |
| Proxy HLS through stream-service | Rejected | Unnecessary bandwidth; stream service handles metadata, SRS handles media |
| Service-account JWT for SRS | Deferred | SRS cannot natively obtain/refresh JWTs; shared secret for Phase 2.4, sidecar for later |
| One-time key (no re-issuance) | Rejected | Streamer may lose key or need to reconnect; re-issuance is essential |
| srsName = streamId | Rejected | Stream ID is public in REST URLs; separate UUID limits discoverability |

## Consequences

- **Positive**: Publish token is self-validating (JWT signature + claims) — no DB query needed for auth, only for stream lookup
- **Positive**: srsName is rotatable without affecting the public stream ID
- **Positive**: Video delivery is direct SRS→Browser; stream service never handles media
- **Positive**: Webhook auto-transitions (on_publish→goLive, on_unpublish→end) eliminate manual start/end in production flow
- **Positive**: Key re-issuance via `GET /publish-key` solves the lost-key problem
- **Positive**: Existing service-account JWT infrastructure is the target state for webhook auth (already built, just waiting for a sidecar)
- **Negative**: Shared secret webhook auth is less secure than JWT (mitigated by internal Docker network isolation)
- **Negative**: srsName UUID is visible in HLS URLs (acceptable — it's a UUID, not a secret, and is rotatable)
- **Negative**: `stream_key_hash` column stores SHA-256 which is irreversible — the plain srsName must be regenerated if lost (acceptable — GET /publish-key handles this)

## References

- [ADR-0001: Stream State Machine (Revised)](0001-stream-state-machine.md)
- [ADR-0002: Kafka Event Publishing](0002-kafka-event-publishing.md)
- `SrsWebhookController.java` — webhook endpoint (Phase 2.4)
- `StreamService.goLiveFromSchedule()` — SCHEDULED→DRAFT go-live action
- `V5__reverse_stream_key_hash_not_null.sql` — migration
- Auth Service: `AccessTokenIssuanceService.issueForServiceAccount()` — future webhook auth
