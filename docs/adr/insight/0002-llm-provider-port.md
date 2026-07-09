# ADR-0002: LLM Provider Behind a Port (Provider-Agnostic Adapter)

**Date**: 2026-07-08
**Status**: proposed
**Deciders**: hieuht, Claude
**Domain**: Insight Service (AI / LLM layer)

## Context

Every rung of the capability ladder ([ADR-0004](0004-capability-ladder.md)) calls an
LLM provider. The provider landscape moves fast: models are deprecated, prices change,
and the "best" model for summarization may differ from the best for classification.
We also want to run cheaply (or with a local model) during development and swap to a
hosted model for real features. If provider SDK calls are scattered through the
application layer, all of that becomes a rewrite.

This service is also a deliberate exercise in hexagonal architecture
([ADR-0001](0001-insight-service-architecture.md)), and the LLM provider is the
cleanest possible teaching example of a port: one behavior, several
implementations, an obvious reason to swap.

## Decision

Define a **provider-agnostic `LlmPort`** in the `domain`/`application` layer expressed
in terms the domain understands (prompt, messages, streaming tokens, structured
output) — **not** in any vendor's SDK types. Concrete providers are `infrastructure`
**adapters** selected by configuration. The application layer depends only on the port.

The port's responsibilities (shape to be finalized at implementation — see the sketch,
not compilable here):

- **Complete** — single-shot text completion (rung 1).
- **Stream** — token streaming as a reactive `Flux<String>` (rung 1; maps to WebFlux/SSE end-to-end).
- **Structured** — force schema-constrained (JSON) output for tags/classification (rungs 1–2).
- **Embed** — text → vector, likely a sibling `EmbeddingPort` for separation (rung 3).

Cross-cutting concerns (timeout, retry with backoff, fallback response, cost/token
accounting) live in the application layer **around** the port, so they apply uniformly
regardless of which adapter is wired — this is the "treat the LLM as an unreliable
dependency" lesson from [ADR-0004](0004-capability-ladder.md) made structural.

The default/first adapter choice (hosted vs. local, and which model) is intentionally
**left open** and will be recorded as a follow-up ADR when Phase 7 starts, so the
decision reflects the model landscape at that time rather than today's.

## Alternatives Considered

### Alternative 1: Call the provider SDK directly from the application layer
- **Pros**: Fastest to write; no abstraction to design.
- **Cons**: Vendor lock-in; swapping providers/models is a cross-cutting rewrite; cost/retry logic gets copy-pasted per call site; defeats the hexagonal learning goal.
- **Why not**: The whole point of this service is a swappable, unreliable edge — that is exactly what a port is for.

### Alternative 2: Adopt a heavy abstraction framework (e.g. an LLM orchestration library) up front
- **Pros**: Batteries-included: retries, provider adapters, RAG helpers out of the box.
- **Cons**: Large dependency and its own abstractions to learn; obscures the primitives we're trying to learn; opinionated in ways that may fight the reactive stack.
- **Why not**: YAGNI at rung 1, and it hides the very mechanics this project exists to teach. Reconsider at rung 4 if RAG plumbing gets heavy — as an adapter behind the port, not a replacement for it.

### Alternative 3: Provider-specific ports (one interface per vendor)
- **Pros**: Each interface can expose vendor-unique features.
- **Cons**: Application layer must know which vendor it's talking to — the abstraction leaks and swapping is no longer transparent.
- **Why not**: Defeats the purpose; the port must be vendor-neutral to be swappable.

## Consequences

### Positive
- Swapping provider/model is a **config + one adapter**, not an application rewrite.
- Local/cheap model in dev, hosted model in prod, behind the same interface.
- Timeout/retry/fallback/cost live in **one place** and apply to every provider.
- Textbook hexagonal port — serves the learning goal directly.

### Negative
- The port is a **lowest-common-denominator** surface; vendor-unique features need deliberate extension rather than being available by default.
- One layer of indirection between the app and the raw SDK.

### Risks
- **Port shape guessed wrong before real use.** *Mitigation*: keep it minimal (complete/stream/structured/embed); expand when a concrete rung demands it, not speculatively.
- **Structured-output support varies by provider.** *Mitigation*: the port models the *intent* (schema-constrained output); adapters implement it with whatever mechanism the provider offers (native JSON mode, tool-calling, or prompt-enforced + validated).

## References

- [ADR-0001](0001-insight-service-architecture.md) — hexagonal service structure this port lives in
- [ADR-0003](0003-pgvector-over-dedicated-vector-db.md) — the sibling vector-store port
- [ADR-0004](0004-capability-ladder.md) — which rung needs which port method
- [INSIGHT-SERVICE-SKETCH.md](../../INSIGHT-SERVICE-SKETCH.md) — port shape sketch (non-compilable)
