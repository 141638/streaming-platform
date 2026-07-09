# ADR-0005: Stream Thumbnails via SRS Auto-Snapshot (MinIO Upload Deferred)

**Status:** Proposed
**Date:** 2026-07-08
**Domain:** Stream Service

## Context

Stream discovery UIs (the Phase 2.5 dashboard, and the Phase 4 viewer browse page)
need a thumbnail per stream — without one, every card is a title on a blank rectangle.
The data model has no thumbnail concept today: no column on `stream_session`, no field
on any DTO.

The platform also has **no blob/object storage** of any kind — only PostgreSQL, Redis,
Kafka, and SRS. [ARCHITECTURE.md §6](../../ARCHITECTURE.md) deliberately keeps stateful
concerns in PG/Redis/Kafka. Storing binary images is a genuinely new capability, not a
small field addition, so we separate *what a thumbnail is* from *how a custom one gets
uploaded*.

Two distinct kinds of thumbnail exist:
1. **Live snapshot** — a frame captured from the running stream. Requires no user upload
   and only exists once a stream is LIVE.
2. **Custom cover art** — an image the streamer uploads ahead of time. Requires object
   storage and an upload endpoint.

## Decision

Adopt **SRS auto-snapshot** as the thumbnail source for now, and **defer custom
cover-art upload** (which needs object storage) to a later phase with its own ADR.

Split the work by cost:

- **Phase 2.5 (now):** Add a nullable `thumbnail_url VARCHAR(512)` column
  (migration V6), an entity field, and `thumbnailUrl` on `StreamResponse` /
  `StreamSummaryResponse`. The field is **client-read-only** — create/update requests do
  not accept it. The frontend renders a **placeholder image** while the value is `NULL`.
- **Phase 2.9 (split out, depends on Phase 4.0 SRS):** Wire SRS to generate a snapshot
  from the live feed (via the SRS `exec`/ffmpeg or snapshot hook) and have stream-service
  persist the resulting URL into `thumbnail_url`. The image is served from the media tier
  (like HLS), not proxied through Spring.
- **Later (separate ADR-0006, planned):** Custom cover-art upload backed by **MinIO**
  (S3-compatible) object storage — a new Docker Compose service following the standard
  five-step lifecycle, plus an upload endpoint. Not started; anticipated here so the
  migration path is explicit.

### Alternatives Considered

| Approach | Verdict | Reason |
|----------|---------|--------|
| MinIO object storage now | Deferred | Adds a new stateful infra service + upload endpoint; largest scope; not needed to unblock discovery UI. Gets its own ADR-0006 when prioritized. |
| URL-only field (paste external URL) | Rejected (for now) | Cheapest, but pushes hosting onto the streamer and invites SSRF/mixed-content concerns; no real product value over a snapshot. |
| Base64 image in a Postgres column | Rejected | Bloats rows, no CDN path, violates the "PG is for metadata" posture. |
| SRS auto-snapshot | **Chosen** | No new infra, no upload handling; reuses the SRS media tier already coming in Phase 4; natural fit for live thumbnails. |

## Consequences

- **Positive:** Unblocks discovery UI immediately with a cheap schema + field + display.
  No new infrastructure. Reuses the media tier. Keeps the MinIO decision open and
  documented.
- **Negative:** No thumbnail exists until a stream has gone LIVE and produced a snapshot —
  DRAFT / SCHEDULED / ENDED-without-snapshot streams show a placeholder. There is no
  custom cover art until the deferred MinIO work lands.
- **Risks:**
  - *SRS snapshot needs ffmpeg in the SRS image.* Mitigation: verify in Phase 2.9;
    `ossrs/srs` bundles ffmpeg — confirm the tag then.
  - *Snapshot refresh cadence undecided* (once-on-live vs periodic). Mitigation: decide in
    Phase 2.9; the column stores whatever URL the flow produces regardless of cadence.

## References

- Related ADRs: [0004-srs-webhook-publish-token.md](0004-srs-webhook-publish-token.md)
  (SRS integration precedent); ADR-0006 (planned) — MinIO custom cover-art upload
- Source files: `V6__add_thumbnail_url.sql`, `StreamSessionEntity.java`,
  `StreamResponse.java`, `StreamSummaryResponse.java`
- External docs: [ARCHITECTURE.md §6](../../ARCHITECTURE.md) — stateful-concerns posture;
  [IMPLEMENTATION-PLAN.md — Phase 2.9](../../IMPLEMENTATION-PLAN.md#29--srs-snapshot-thumbnails)
