# ADR-0001: Insight Service as a Standalone Hexagonal Service

**Date**: 2026-07-08
**Status**: proposed
**Deciders**: hieuht, Claude
**Domain**: Insight Service (AI / LLM layer)

## Context

The AI/LLM capabilities ([ADR-0004](0004-capability-ladder.md)) need a home in the
codebase. The options are: bolt them onto an existing service (chat or stream), or
create a new control-plane service. The AI layer has distinct properties that shape
this decision:

- It depends on **external, unreliable, non-deterministic** infrastructure (an LLM
  provider, later an embedding endpoint) that no existing service touches.
- It has **two swappable edges** — the LLM provider and the vector store — that are
  textbook cases for the ports-and-adapters pattern the platform already uses
  selectively ([SERVICE-ARCHITECTURE.md §1](../../SERVICE-ARCHITECTURE.md)).
- Its ingestion (transcribe → chunk → embed → store) is naturally **event-driven and
  off the request path**, mirroring the existing notification-service Kafka-consumer
  shape.
- Its cost and latency profile (seconds-long calls, per-token billing) is unlike any
  existing service and should not be able to degrade chat or stream hot paths.

## Decision

Create a **new `insight-service`** control-plane service rather than extending an
existing one. Use the platform's **layered-reactive style with explicit hexagonal
ports** at the two swappable edges (LLM provider, vector store), consistent with how
stream-service applies ports for SRS/Kafka. Ingestion is **Kafka-driven** (consume
existing lifecycle topics); synchronous features (summary, Q&A) are exposed via REST
through the gateway.

Package layout follows the established convention
(`api/` → `application/` → `domain/` → `infrastructure/`), matching stream-service and
chat-service so a developer moving between services re-learns nothing.

## Alternatives Considered

### Alternative 1: Extend chat-service (summaries/moderation live near chat)
- **Pros**: Chat summary and moderation are chat-adjacent; no new service to register/deploy.
- **Cons**: Pulls an unreliable, high-latency, per-token-billed dependency into the chat hot path; blurs chat-service's bounded context; couples chat scaling to LLM scaling.
- **Why not**: Violates separation of concerns and risks LLM latency/outages degrading real-time chat.

### Alternative 2: Extend stream-service (it already owns lifecycle + transcripts-to-be)
- **Pros**: RAG ingestion is triggered by stream lifecycle events stream-service already emits.
- **Cons**: stream-service is the platform's most security-sensitive service (publish tokens, PBAC); adding a large, experimental AI surface there increases blast radius and review burden.
- **Why not**: Keep the security-critical service small and stable; the AI layer is experimental and should evolve independently.

### Alternative 3: Full Clean/Onion architecture for the new service
- **Pros**: Maximum decoupling of domain from framework.
- **Cons**: Two conventions in one platform is worse than one "good enough" convention (the same reasoning as [chat ADR-0001](../chat/0001-layered-reactive-architecture.md)).
- **Why not**: Platform consistency wins. Layered-reactive + targeted ports already achieves the decoupling that matters (the two swappable edges).

## Consequences

### Positive
- **Isolation**: LLM latency/outages/cost stay contained; they cannot degrade chat or stream hot paths.
- **Consistency**: Same package layout and patterns as existing services.
- **Clean learning surface**: A greenfield service is an ideal place to practice hexagonal ports without disturbing production-critical code.
- **Independent evolution**: Experimental AI features iterate without touching security-critical services.

### Negative
- **One more service** to register in Eureka, route in the gateway, add to compose, and operate.
- **Cross-service data access**: needs read access to transcripts/metadata owned elsewhere — via events or APIs, not shared tables (platform avoids cross-schema FKs). Upstream data sources documented below.

### Upstream Data Sources (available when insight-service is built)

| Source | Owned By | Access | Purpose |
|--------|----------|--------|---------|
| `stream_viewer_snapshot` | stream-service (ADR-0010) | REST API or DB read | Viewer analytics: peak concurrency, retention curves, category trends |
| `stream_session` | stream-service | REST API | Stream metadata for summaries, titles, tags |
| `chat_message` | chat-service | REST API or Kafka | Chat history for summarization, moderation input |
| Kafka `stream.control` | stream-service | Kafka consumer | RAG ingestion triggers (stream lifecycle events) |

### Risks
- **Premature service split if only rung 1 ever ships.** *Mitigation*: rung 1 alone still benefits from isolation (unreliable dependency); the split pays off immediately, not just at RAG.
- **Duplication of `JwtProperties`/`SecurityConfig`/entry-point** as with other services. *Mitigation*: adopt `pbac-common` once extracted (Phase 6.2) instead of a new divergent copy.

## References

- [ADR-0002](0002-llm-provider-port.md) — the LLM provider port
- [ADR-0003](0003-pgvector-over-dedicated-vector-db.md) — vector storage decision
- [ADR-0004](0004-capability-ladder.md) — capability ladder / delivery order
- [stream/ADR-0010](../stream/0010-viewer-heartbeat-analytics-pipeline.md) — viewer heartbeat analytics pipeline (upstream data source)
- [SERVICE-ARCHITECTURE.md](../../SERVICE-ARCHITECTURE.md) — per-service style guidance
- [chat/0001-layered-reactive-architecture.md](../chat/0001-layered-reactive-architecture.md) — precedent for the style choice
