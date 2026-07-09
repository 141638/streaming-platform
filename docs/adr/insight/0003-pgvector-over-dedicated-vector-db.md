# ADR-0003: pgvector over a Dedicated Vector Database

**Date**: 2026-07-08
**Status**: proposed
**Deciders**: hieuht, Claude
**Domain**: Insight Service (AI / LLM layer)

## Context

Rungs 3 and 4 of the capability ladder ([ADR-0004](0004-capability-ladder.md)) —
semantic VOD search and "ask this stream" RAG — need to store and similarity-search
embedding vectors. The obvious question is whether to introduce a dedicated vector
database (Qdrant, Weaviate, Milvus, pgvector-as-a-service, etc.) or use the PostgreSQL
we already run.

Relevant platform facts:

- The platform already operates **PostgreSQL** as its system of record, with a
  schema-per-service convention ([SERVICE-ARCHITECTURE.md §3–4](../../SERVICE-ARCHITECTURE.md)).
- [ARCHITECTURE.md §6](../../ARCHITECTURE.md) explicitly states stateful concerns
  should stay in **PostgreSQL, Redis, and Kafka** — the platform is deliberately
  conservative about adding stateful systems.
- This is a POC with a single-developer operational budget. Every new stateful system
  means another thing to provision, health-check, back up, secure, and learn to
  operate (the platform's own five-step third-party-service lifecycle).
- Expected data volume at these rungs is modest (thousands to low-hundred-thousands of
  chunks), well within pgvector's comfortable range.

## Decision

Use the **`pgvector` extension on the existing PostgreSQL** for embedding storage and
similarity search. Give `insight-service` its own schema (e.g. `insight`), consistent
with the schema-per-service convention. Access it behind a **`VectorStorePort`**
([ADR-0002](0002-llm-provider-port.md) established the port pattern) so that if we
later outgrow pgvector, swapping to a dedicated vector DB is an adapter change, not an
application rewrite.

Reach for a dedicated vector database **only** when a concrete limit is hit — millions
of vectors, high query QPS, or advanced filtering/sharding pgvector can't serve — and
record that move in a follow-up ADR that states the measured trigger.

## Alternatives Considered

### Alternative 1: Dedicated vector DB (Qdrant / Weaviate / Milvus) from the start
- **Pros**: Purpose-built ANN indexing; scales to very large corpora; rich metadata filtering.
- **Cons**: A whole new stateful system to provision, secure, back up, and learn; contradicts ARCHITECTURE.md's "keep stateful concerns in PG/Redis/Kafka" posture; overkill for POC volumes.
- **Why not**: Its main lesson would be "operate another datastore" — which this project doesn't need yet. The port keeps this option open for when volume justifies it.

### Alternative 2: In-memory / flat-file vector index
- **Pros**: Zero infrastructure; trivial to start.
- **Cons**: Not durable; doesn't survive restarts; no concurrent access; misrepresents how retrieval works in a real system.
- **Why not**: Teaches the wrong operational model and can't back a real feature.

### Alternative 3: Redis vector search (RediSearch)
- **Pros**: Redis is already in the stack; supports vector similarity.
- **Cons**: Our Redis is deliberately a **disposable cache** ([common ADR-0002](../common/0002-redis-ephemeral-data-store.md)) with no persistence guarantee — embeddings are expensive to recompute and want durability; mixing durable vectors into a disposable cache violates that design.
- **Why not**: Contradicts the established role of Redis on this platform. Embeddings belong in the durable store.

## Consequences

### Positive
- **No new stateful system** — reuses PostgreSQL, honoring the platform's operational posture.
- One backup/restore/security story covers relational data *and* vectors.
- Embeddings can be **joined** with relational metadata (stream, timestamp, author) in a single query — genuinely convenient for "jump to 14:32" style citations.
- Learning stays focused on embeddings/retrieval, not on operating a new datastore.

### Negative
- pgvector's ANN indexing is less specialized than purpose-built engines; at large scale, recall/latency tuning is more manual.
- Heavy vector workloads share the primary database's resources (noisy-neighbor risk at scale).

### Risks
- **Outgrowing pgvector silently.** *Mitigation*: the `VectorStorePort` makes migration an adapter swap; define the trigger (vector count / QPS / latency SLO) when rung 3 ships, and log when approaching it.
- **Extension availability in the Docker image.** *Mitigation*: use a `pgvector`-enabled Postgres image (e.g. `pgvector/pgvector`), added via the standard five-step service lifecycle when rung 3 begins.

## References

- [ADR-0001](0001-insight-service-architecture.md) — service that owns the `insight` schema
- [ADR-0002](0002-llm-provider-port.md) — the port pattern reused for `VectorStorePort`
- [ADR-0004](0004-capability-ladder.md) — rungs 3–4 that need vectors
- [ARCHITECTURE.md §6](../../ARCHITECTURE.md) — "keep stateful concerns in PG/Redis/Kafka"
- [common/0002-redis-ephemeral-data-store.md](../common/0002-redis-ephemeral-data-store.md) — why Redis is not the home for durable vectors
