# ADR-0004: Incremental Capability Ladder for the AI/LLM Layer

**Date**: 2026-07-08
**Status**: proposed
**Deciders**: hieuht, Claude
**Domain**: Insight Service (AI / LLM layer)

> **This is the foundational ADR for the AI layer — read it first.** It defines
> *why* we add LLM features, and the *order* in which we add them. ADRs 0001–0003
> describe the structural decisions that this sequence relies on.

## Context

We want to add LLM-powered capabilities to the streaming platform, and we want the
work to double as a learning vehicle for LLM-backed systems. The most-hyped pattern
is **RAG** (Retrieval-Augmented Generation), so the instinct is to "add RAG." That
instinct is a trap for two reasons:

1. **RAG is the highest-moving-parts feature, not the entry point.** A single RAG
   query chains an embedding model + a vector store + retrieval tuning + a
   generation call. Starting there means debugging four subsystems at once — a wrong
   answer could be bad retrieval, bad chunking, the wrong embedding model, or a bad
   prompt, and you can't tell which.
2. **Most of the LLM value a streaming platform wants isn't RAG at all.**
   Moderation is classification. Auto-titles/tags are extraction. "Catch me up on
   chat" is summarization. None of these need a vector store.

Mapping candidate features to LLM patterns makes the ordering obvious:

| Feature | LLM pattern | RAG? | Notes |
|---|---|---|---|
| Chat moderation (toxicity/spam) | Classification | No | Consumes existing chat/Kafka flow |
| Auto stream title/tags | Summarization / extraction | No | Structured (JSON) output |
| "Catch me up" chat summary | Summarization | No | Great demo, low complexity |
| Semantic VOD search | Embeddings + vector search | Partial (retrieval only) | No generation step |
| "Ask this stream" Q&A | **RAG** | Yes | Needs transcripts (ASR) |

Note the genuinely RAG-shaped feature ("ask this stream") also depends on
**transcripts**, which require an ASR step on the media/recording pipeline — a
prerequisite that doesn't exist yet. RAG is therefore the *destination*, not the
starting line.

Two hard-won principles drive the ordering:

- **The LLM is a non-deterministic, sometimes-unavailable, sometimes-wrong network
  dependency.** Timeouts, fallbacks, caching, and "what if it returns garbage JSON"
  matter more than the prompt. Our reactive (WebFlux) stack already forces this
  mindset — lean into it.
- **RAG failures are usually retrieval failures, not model failures.** The answer is
  wrong because the wrong chunks were retrieved. This is *why* we build retrieval in
  isolation (where recall is measurable) before layering generation on top.

## Decision

Build the AI layer as a **four-rung capability ladder**, delivered in order. Each rung
teaches exactly one new concept and ships a working, demoable feature. RAG is rung 4 —
reached only after every other moving part is understood and in place.

| Rung | Capability | New concept introduced | Example feature | RAG? |
|------|-----------|------------------------|-----------------|------|
| **1** | Plain LLM call | Prompt design, **token streaming** (`Flux<String>` end-to-end), structured JSON output, treating the LLM as an untrusted dependency (timeout/retry/fallback) | "Catch me up" chat summary; auto title/tags | No |
| **2** | Classification | Async LLM in a Kafka pipeline, batching, cost control at volume, never blocking the hot path | Chat moderation | No |
| **3** | Embeddings + retrieval (no generation) | Embedding models, chunking, similarity search, measurable recall | Semantic VOD search | No |
| **4** | Full RAG | Grounded generation with citations on top of rung-3 retrieval | "Ask this stream" Q&A with timestamp jumps | **Yes** |

Rung 1 is the recommended starting feature: it fits the WebFlux stack beautifully
(token streaming maps to `Flux`), demos well, and teaches the core primitives with the
fewest moving parts.

## Alternatives Considered

### Alternative 1: "Add RAG first" (the hype-driven path)
- **Pros**: Directly builds the most-talked-about capability; one feature covers embeddings + retrieval + generation.
- **Cons**: Four new subsystems debugged simultaneously; depends on transcripts that don't exist; a wrong answer is un-diagnosable without isolating retrieval first.
- **Why not**: Highest complexity for the first step, and its prerequisite (ASR transcripts) isn't built. Guarantees a frustrating start and teaches nothing cleanly.

### Alternative 2: One big "AI features" phase, all at once
- **Pros**: Single planning effort; ships a lot of surface area together.
- **Cons**: Couples independent features; no clean learning progression; a stall in embeddings blocks the trivial summary feature that could have shipped in isolation.
- **Why not**: Violates the platform's own incremental-delivery ethos. The rungs are independently valuable and independently shippable.

### Alternative 3: Skip rungs 1–2, jump to embeddings (rung 3)
- **Pros**: Semantic search is genuinely useful and doesn't need generation.
- **Cons**: Skips the foundational lesson — operating the LLM as an unreliable dependency (timeouts, fallbacks, structured output) — which every later rung depends on.
- **Why not**: Rung 1 is where the reusable operational patterns (streaming, retry, fallback, cost) are established. Later rungs reuse them.

## Consequences

### Positive
- Each rung is independently valuable, demoable, and shippable — matches the platform's incremental-delivery posture.
- Debugging is tractable: one new subsystem per rung.
- RAG becomes *easy* to build because retrieval (rung 3) is already understood and measured before generation is added.
- The learning curve mirrors the delivery curve — the stated secondary goal is met.

### Negative
- The most-hyped feature (RAG) ships last, which can feel slow if the goal were purely "have RAG."
- Rung 4 has an external prerequisite (ASR transcripts) tracked outside this ladder.

### Risks
- **Scope creep pulling RAG earlier.** *Mitigation*: this ADR is the contract — RAG is rung 4, gated on rungs 1–3 and on transcripts existing.
- **Rungs 1–2 shipping without cost controls, then surprising us at volume.** *Mitigation*: cost control and fallback are explicit rung-1/rung-2 deliverables, not afterthoughts.

## References

- [ADR-0001](0001-insight-service-architecture.md) — service structure
- [ADR-0002](0002-llm-provider-port.md) — provider-agnostic LLM port
- [ADR-0003](0003-pgvector-over-dedicated-vector-db.md) — vector storage
- [INSIGHT-SERVICE-SKETCH.md](../../INSIGHT-SERVICE-SKETCH.md) — module + data-flow sketch
- [IMPLEMENTATION-PLAN.md — Phase 7](../../IMPLEMENTATION-PLAN.md#phase-7--ai--llm-layer-insight-service-)
