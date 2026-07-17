# Stream Service ADRs

Architectural Decision Records for the stream bounded context.

| ADR | Title | Status |
|-----|-------|--------|
| [0000](0000-architecture-foundation.md) | Architecture Foundation — Stream Service | Accepted |
| [0001](0001-stream-state-machine.md) | Stream State Machine | Accepted (Revised 2026-07-07) |
| [0002](0002-kafka-event-publishing.md) | Kafka Event Publishing | Accepted |
| [0003](0003-categories-tags.md) | Categories and Tags | Accepted |
| [0004](0004-srs-webhook-publish-token.md) | SRS Webhook Integration & Publish Token Architecture | Accepted |
| [0005](0005-stream-thumbnails.md) | Stream Thumbnails via SRS Auto-Snapshot (MinIO upload deferred) | Accepted |
| [0007](0007-public-channel-read-and-channel-service-seam.md) | Authenticated Channel Read in Stream-Service, with a channel-service Extraction Seam | Accepted |
| [0008](0008-view-count-analytics-pipeline.md) | View Count Analytics Pipeline — Redis-Now (Phase 1: per-user hash with dedup + analytics table), Kafka-Batch Later (Phase 2) | Accepted (Phase 1 implemented) |
| [0009](0009-outbox-pattern.md) | Kafka Outbox Pattern — Transactional outbox with FOR UPDATE SKIP LOCKED poller, at-least-once delivery | Accepted (implemented) |
| [0010](0010-viewer-heartbeat-analytics-pipeline.md) | Viewer Heartbeat Analytics Pipeline — Minute-bucket aggregation from Redis presence keys, stream-service owned | Proposed |
