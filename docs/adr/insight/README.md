# Insight Service ADRs — Analytics & AI/LLM Layer

Architectural Decision Records for the `insight-service` bounded context: two
sub-domains that share infrastructure but evolve independently:

- **Analytics & Engagement** — view/like/sub event ingestion, channel/category
  scoring, personalized suggestions, streamer peak-hour analytics.
- **AI / LLM** — chat moderation, stream summarization, semantic search,
  retrieval-augmented Q&A ("ask this stream").

> **Status note.** Every ADR in this directory is **Proposed**. `insight-service`
> is deferred to **[Phase 7](../../IMPLEMENTATION-PLAN.md#phase-7--ai--llm-layer-insight-service-)**,
> with Analytics (Phase A) deliverable earlier since it depends only on
> stream-service Kafka events, not on channel-service or an LLM provider.
> Statuses flip to **accepted** when implementation begins.

| ADR | Title | Status |
|-----|-------|--------|
| [0000](0000-architecture-foundation.md) | Architecture Foundation — Two Sub-Domains, Event-Driven Ingestion, Phased Rollout | proposed |
| [0001](0001-insight-service-architecture.md) | Insight Service as a Standalone Hexagonal Service | proposed |
| [0002](0002-llm-provider-port.md) | LLM Provider Behind a Port (Provider-Agnostic Adapter) | proposed |
| [0003](0003-pgvector-over-dedicated-vector-db.md) | pgvector over a Dedicated Vector Database | proposed |
| [0004](0004-capability-ladder.md) | Incremental Capability Ladder (Plain Call → Classify → Embed → RAG) | proposed |

## Reading order

1. **0000** — the *what* and *why*: two sub-domains, event model, scoring, rollout phases (start here).
2. **0004** — the AI capability delivery sequence (LLM features only).
3. **0001** — where the code lives and how it interoperates with existing services.
4. **0002** — how the LLM provider stays swappable.
5. **0003** — where vectors are stored and why not a new datastore.

## Related

- [INSIGHT-SERVICE-SKETCH.md](../../INSIGHT-SERVICE-SKETCH.md) — rough module/data-flow sketch
- [SERVICE-ARCHITECTURE.md](../../SERVICE-ARCHITECTURE.md) — per-service architectural styles
- [ARCHITECTURE.md](../../ARCHITECTURE.md) — dual-plane system overview
