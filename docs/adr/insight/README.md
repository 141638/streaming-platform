# Insight Service ADRs — AI / LLM Layer

Architectural Decision Records for the `insight-service` bounded context: the AI/LLM
layer that adds summarization, classification (moderation), semantic search, and
retrieval-augmented Q&A ("ask this stream") to the platform.

> **Status note.** Every ADR in this directory is **Proposed**. `insight-service`
> is deferred to **[Phase 7](../../IMPLEMENTATION-PLAN.md#phase-7--ai--llm-layer-insight-service-)**
> and has **no implementation yet**. These records exist to capture the design
> decisions early — while the context is fresh — so the eventual build starts from
> agreed shape rather than a blank page. Statuses flip to **accepted** when Phase 7
> implementation begins.

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-insight-service-architecture.md) | Insight Service as a Standalone Hexagonal Service | proposed |
| [0002](0002-llm-provider-port.md) | LLM Provider Behind a Port (Provider-Agnostic Adapter) | proposed |
| [0003](0003-pgvector-over-dedicated-vector-db.md) | pgvector over a Dedicated Vector Database | proposed |
| [0004](0004-capability-ladder.md) | Incremental Capability Ladder (Plain Call → Classify → Embed → RAG) | proposed |

## Reading order

1. **0004** — the *why* and the delivery sequence (start here for the big picture).
2. **0001** — where the code lives and how it interoperates with existing services.
3. **0002** — how the LLM provider stays swappable.
4. **0003** — where vectors are stored and why not a new datastore.

## Related

- [INSIGHT-SERVICE-SKETCH.md](../../INSIGHT-SERVICE-SKETCH.md) — rough module/data-flow sketch
- [SERVICE-ARCHITECTURE.md](../../SERVICE-ARCHITECTURE.md) — per-service architectural styles
- [ARCHITECTURE.md](../../ARCHITECTURE.md) — dual-plane system overview
