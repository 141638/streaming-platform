---
name: session-retro
description: End-of-session retrospective that reconciles implementation against plans, updates stale ADRs and planning docs, captures undocumented deferrals, and produces a structured retro document. Invoke via /retro at the end of a feature-building session.
metadata:
  origin: project
---

# Session Retrospective

At the end of every feature-building session, perform a structured retrospective that reconciles what was built against what was planned, updates stale documentation, and captures architectural decisions made during implementation.

## Substance Gate (READ FIRST)

Not every session produces novel patterns, architectural decisions, or plan deviations. Before entering the full five-phase workflow, ask:

> Did this session introduce a decision not already documented? A pattern not already captured? A deviation from an existing plan?

If the answer to all three is **no**, perform the **database-design compliance check** (see Phase 1 §6). If that check also passes clean, skip directly to Phase 5 and report: **"No pattern deemed noticeable or needs updating."** Do not create or modify any documents. This prevents noise documents that dilute the value of real retrospectives.

**Exception — database schema changes always warrant a full check:** If the session touched Flyway migrations, R2DBC entities, or column type changes, the database-design compliance check is MANDATORY (Phase 1 §6). A schema compliance gap IS a "pattern not captured" finding and triggers the full workflow.

## When to Activate

- User says "retro", "retrospective", "/retro", "wrap up the session", "let's do a review of what we did"
- End of a multi-commit feature-building session
- Before switching to a new feature or phase
- After any session where ADRs, the implementation plan, or planning docs were touched

## The Six Questions

Every retro must answer these six questions:

1. **What was implemented** according to the planning ahead (blueprint, scope review, implementation plan)?
2. **What was deferred** (planned but not implemented) and where is it tracked?
3. **What was deferred but NOT documented** — gaps found during the retro itself?
4. **What architectural decisions** were made during implementation that need a new ADR or pattern doc?
5. **What existing docs are now stale** and need updating?
6. **Which architecture/reference docs need updating** based on what changed this session?

## Workflow

### Phase 1: Survey (read-only)

```
1. Read the master implementation plan (docs/IMPLEMENTATION-PLAN.md)
2. Read all planning docs under docs/plans/
3. Read all relevant ADRs (scan docs/adr/ for the services touched)
4. Read the git log for this session's commits (git log --oneline -30)
5. List all migration files, new source files, and new frontend components
6. DATABASE-DESIGN COMPLIANCE CHECK (mandatory when entity/Flyway touched):
   a. Read .claude/rules/common/database-design.md
   b. For each Flyway migration touched → verify every Tier 2 column type
      (JSONB, TEXT[], custom ENUM, BYTEA) has a corresponding R2DBC converter
      pair registered in R2dbcConfig:
        - @WritingConverter returning io.r2dbc.postgresql.codec.Json (not String)
        - @ReadingConverter accepting io.r2dbc.postgresql.codec.Json (not String)
        - Both converters registered in getCustomConverters() or R2dbcCustomConversions bean
   c. For each R2DBC entity touched → verify no impedance mismatch:
        - JSONB column + String entity field (without converter) → COMPLIANCE GAP
        - TEXT[] column + Set<String>/List<String> entity field → COMPLIANCE GAP
        - Custom ENUM column + no @ReadingConverter/@WritingConverter → COMPLIANCE GAP
   d. Flag any gaps found — these become §3 "deferred but undocumented" or
      §4 "architectural decision" entries in the retrospective
7. ARCHITECTURE & REFERENCE DOCS SCAN:
   a. Read the Architecture & Reference Docs Update Matrix (Phase 3b below)
   b. For each document in the matrix, check whether its trigger conditions match
      any change from this session's commits, new files, or touched services
   c. Build a candidate list: [document | trigger matched | likely scope of update]
   d. Read the candidate documents to verify staleness before modifying
```

Use parallel agents or reads to survey quickly. Do not modify anything in this phase.

### Phase 2: Draft the Retrospective

Write `docs/plans/<feature>-retrospective.md` (or update it if one already exists). Use this structure:

```markdown
# [Feature Name] — Implementation Retrospective

**Date:** YYYY-MM-DD
**Status:** Complete (or partial — list deferred items)

## 1. What was implemented (vs the original plan)
[Table: planned item | commit(s) | notes]

## 2. What was deferred (documented, with tracking reference)
[Table: item | deferred to | tracking doc | reason]

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)
[Table: item | context | recommended action]

## 4. Architectural decisions made during implementation (candidates for new ADRs)
[Numbered list: decision, why it matters, recommendation (ADR vs pattern doc vs note)]

## 5. Documents to update (stale vs current state)
[Table: document | current status | what's stale | action to take]

## 6. Architecture & reference docs updated
[Table: document | trigger | what changed | one-line summary]

## 7. Updated execution order (actual vs planned)
[Table: planned commit | actual commit | status]

## 8. Key risks carried forward
[Numbered list with mitigation references]
```

### Phase 3: Update Stale Documents

For each stale document identified in Phase 2, apply the update:

1. **ADRs:** If a Proposed ADR was fully implemented, mark it **Accepted** with the current date. Add new decision sections for architectural choices made during implementation. Update the References and source files list to match reality.
2. **Scope review / blueprint:** Mark status (e.g., "Ready for review" → "Implemented"). Update feature tables to reflect what was actually built (not just planned). Add commit hashes for traceability.
3. **Implementation plan:** Add new completed work items. Update the checklist. Update Phase readiness context. Add new ADRs to the ADR table. Update the "Last updated" date.

### Phase 3b — Update Architecture & Reference Docs

**Purpose.** The docs root (`docs/*.md`) holds architecture overviews, pattern references, and roadmaps that must stay aligned with what's actually built. Unlike ADRs (which capture a decision at a point in time), these docs are **living reference** — they drift when new services, protocols, patterns, or production gaps ship without a corresponding doc update.

**The update matrix below defines exactly when each document needs a refresh.** Not every session touches every doc — the triggers are scoped by service, component, and change type. A session that only touches `chat-service` won't trigger an update to `AUTH-INTERCEPTOR-PATTERN.md`.

#### Architecture & Reference Docs Update Matrix

For each document, consult the **trigger** column to decide whether the session's changes warrant an update. If a trigger matches, read the document to verify staleness, then apply the minimal update described in the **update scope** column.

---

**A. ARCHITECTURE.md — System Architecture Overview**

| Trigger | Update Scope |
|---------|-------------|
| New service added or service role significantly changed | Update §3 component table row + system context diagram (§2) |
| New infrastructure dependency introduced (e.g., new Docker service, new DB, new message broker) | Add/update row in §3 Supporting Infrastructure table |
| New protocol or communication pattern added to a service (e.g., WebSocket, SSE, gRPC) | Update component role description in §3 table + data flow in §5 + glossary in §9 |
| Service scaling/deployment posture changes (e.g., stateful → stateless) | Update §6 Scaling and Deployment Posture |
| Security posture changes (e.g., new auth mechanism, new TLS boundary) | Update §7 Security and POC Boundaries table |
| New glossary term needed for a concept now in use | Add to §9 Glossary |
| Demo script changes (new service to show, new flow) | Update §8 Suggested Demo Script |
| **Service count or relationship changes** — re-read §3 and §5 to verify accuracy | Spot-check and fix any stale descriptions |

**Decision rule:** Re-read ARCHITECTURE.md after every session that touches a new service, adds infrastructure, or changes how services communicate. If any paragraph describes something that no longer exists or is missing something that now does, update it.

---

**B. SERVICE-ARCHITECTURE.md — Per-Service Architectural Styles & Data Strategy**

| Trigger | Update Scope |
|---------|-------------|
| New backend service created (Gradle module scaffolded) | Add row to §1 style table |
| Existing service changes its primary architectural style (e.g., layered → hexagonal) | Update §1 table row + rationale |
| New data strategy introduced (e.g., cache-aside, CQRS, event sourcing) | Add/update §2 or new section |
| New Flyway layout created for a service | Add to §3 Flyway layout table |
| Database split or new schema created | Update §4 or add new section |
| Cross-service data pattern changes (e.g., new denormalization strategy) | Update relevant section |

**Decision rule:** Read SERVICE-ARCHITECTURE.md when a new backend service is scaffolded or when an existing service adopts a different architectural pattern.

---

**C. IMPLEMENTATION-PLAN.md — Master Implementation Plan**

| Trigger | Update Scope |
|---------|-------------|
| Phase completed or status changed | Update phase status badge + checklist + "Last updated" date + "Current phase" header |
| New work items added to a phase | Add numbered item under the phase |
| New ADR published | Add to phase's Architecture Decisions table |
| Third-party service lifecycle step completed | Update Infrastructure Status table |
| Deferred items change status (deferred → done, or new deferral) | Update checklist + deferral notes |

**Decision rule:** ALWAYS check IMPLEMENTATION-PLAN.md after any session that shipped code. This is the most frequently updated doc — the "Last updated" date and phase status should never be stale.

---

**D. LOGGING-ARCHITECTURE.md — Logging Strategy & Roadmap**

| Trigger | Update Scope |
|---------|-------------|
| New backend service created (needs logback-spring.xml deployed) | Add row to §1 Services table |
| Logging format or field changed | Update §1 field reference table |
| Phase 2 (distributed tracing) progresses | Update §2 status + implementation details |
| Phase 3 (centralized log backend) progresses | Update §3 status + decision criteria |
| Spring Boot upgrade that changes structured logging support | Update "Future Considerations" section |
| **Service count changes** (new service, removed service) | Update §1 Services table |

**Decision rule:** Check when a new backend service is created or when observability Phase 2/3 work ships.

---

**E. TRACE-PROPAGATION.md — Distributed Tracing Architecture**

| Trigger | Update Scope |
|---------|-------------|
| Phase 2 tracing implemented (Micrometer + Brave added to services) | Update status header + mark checklist items done |
| New trace propagation mechanism (e.g., gRPC, new message broker) | Add new step/section |
| Header format changes (e.g., W3C→custom) | Update header formats table |
| Frontend traceparent generation added | Update Step 1 with implementation notes |
| New backend service added (needs tracing dependency) | Add to implementation checklist |

**Decision rule:** Only check when tracing/monitoring work ships. Most sessions won't trigger this.

---

**F. INSIGHT-SERVICE-SKETCH.md — Future Insight Service Design Sketch**

| Trigger | Update Scope |
|---------|-------------|
| New insight ADR published (adr/insight/) | Add reference in Related section |
| Design decision changes (e.g., pgvector → dedicated vector DB) | Update relevant section + rationale |
| Phase 7 implementation starts (service scaffolded) | Rewrite status header; move from sketch to implemented |
| New rung detail designed | Update per-rung data flow section |
| LLM provider or embedding model chosen | Update "Deliberately open" section with decision |

**Decision rule:** Only check when insight-service ADRs are written or Phase 7 work begins. Rarely triggered before Phase 7.

---

**G. REDIS-KAFKA-PRODUCTION-GAP.md — Redis & Kafka Production Readiness Gap Analysis**

| Trigger | Update Scope |
|---------|-------------|
| Gap closed (any R# or K# item completed) | Mark gap ✅ Done, add commit hash, note date |
| New gap identified during implementation | Add to appropriate gap table (Redis §2.2 or Kafka §3.2) |
| Priority roadmap re-ordered | Update §5 priority tables |
| New linked ADR published that addresses a gap | Update gap row with ADR reference |
| Interview readiness section needs refresh | Update "You can discuss now" sections |

**Decision rule:** Check whenever Redis or Kafka code changes. This doc is living — it should never show a gap as open if the gap was closed this session.

---

**H. PBAC-AUTHORIZATION.md — PBAC Domain Model & JWT Design**

| Trigger | Update Scope |
|---------|-------------|
| PBAC grammar changed (new resource type, new action, scope pattern change) | Update §2 resource types table, §3 action catalog, §4 policy examples |
| New service adopts PBAC enforcement | Update §6 enforcement matrix |
| JWT claim format changed | Update §5 JWT payload design |
| Authorization model changes (e.g., new constraint type) | Update relevant section |
| PBAC-common library extraction ships | Update architecture notes referencing shared lib |

**Decision rule:** Check when security/authorization code changes or when a new service wires PBAC.

---

**I. PBAC-ENFORCEMENT-PATTERN.md — PBAC Enforcement Implementation Pattern**

| Trigger | Update Scope |
|---------|-------------|
| Enforcement pattern changes (new layer, new annotation) | Update pattern section |
| New service implements PBAC enforcement (add to "known implementations") | Add to implementations list |
| Framework upgrade that changes reactive security context API | Update code examples |
| New edge case or gotcha discovered | Add to pitfalls section |

**Decision rule:** Check when PBAC enforcement code changes or a new service replicates the pattern.

---

**J. R2DBC-JSONB-CONVERTER-PATTERN.md — R2DBC JSONB Column Mapping Pattern**

| Trigger | Update Scope |
|---------|-------------|
| New JSONB converter pair created (new domain type) | Add to existing implementations list |
| Converter pattern changes (new approach, new wire type) | Update solution section |
| Framework upgrade changes R2DBC converter API | Update code templates |
| New gotcha or pitfall discovered | Add to common pitfalls table |
| New service adopts the pattern | Add to "Applicable to" or implementations list |

**Decision rule:** Check when any Flyway migration adds a JSONB column or when a new converter pair is written.

---

**K. IDEMPOTENCY-PATTERN.md — Idempotency Key Pattern**

| Trigger | Update Scope |
|---------|-------------|
| New idempotency mechanism added (e.g., new header, new storage backend) | Add to pattern section |
| New service adopts the pattern | Add to existing implementations |
| Pattern changes (e.g., TTL strategy, key format) | Update relevant section |
| New failure mode or gotcha discovered | Add to pitfalls |

**Decision rule:** Check when idempotency work ships or a new service adopts idempotency keys.

---

**L. AUTH-INTERCEPTOR-PATTERN.md — Angular Auth Interceptor Pattern**

| Trigger | Update Scope |
|---------|-------------|
| Interceptor logic changes (new retry condition, new token source) | Update architecture diagram + code flow |
| Angular version upgrade that changes HTTP interceptor API | Update code examples (functional vs class-based) |
| New auth flow added (e.g., device flow, biometric) | Add to pattern variants |

**Decision rule:** Check when frontend auth code changes. Rarely triggered outside auth work.

---

**M. REFRESH-TOKEN-ROTATION.md — Refresh Token Rotation Pattern**

| Trigger | Update Scope |
|---------|-------------|
| Rotation mechanism changes (e.g., family-based → sliding window) | Rewrite design section |
| Storage backend changes (e.g., PG → Redis) | Update implementation notes |
| New replay detection mechanism | Update design section |

**Decision rule:** Check when auth-service token handling changes. Rarely triggered.

---

**N. RXJS-SINGLE-FLIGHT-PATTERN.md — RxJS Single-Flight Deduplication Pattern**

| Trigger | Update Scope |
|---------|-------------|
| Pattern applied to a new operation beyond `AuthService.refresh()` | Add to "Existing implementations" |
| Angular/RxJS version changes `shareReplay` or `firstValueFrom` behavior | Update code example |
| New failure mode or gotcha discovered | Add to pitfalls |

**Decision rule:** Check when the pattern is replicated to a new service call. Rarely triggered.

---

**O. PRIMENG-MENU-STABLE-REFERENCE.md — PrimeNG Menu Stable Array Reference Pattern**

| Trigger | Update Scope |
|---------|-------------|
| PrimeNG major version upgrade (e.g., 19 → 20) | Verify pattern still applies; update code examples if API changed |
| New PrimeNG component exhibits the same getter→new-reference problem | Add to affected components list |
| Angular change detection strategy changes | Update root cause analysis |

**Decision rule:** Only check on PrimeNG version bumps. Very rarely triggered.

---

#### Reconciliation Workflow

For each document flagged by the Phase 1 survey (step 7) as having a matched trigger:

1. **Read** the document to confirm staleness — don't trust the signal alone
2. **Update** with minimal, precise edits:
   - Update status badges, dates, and "Last updated" fields
   - Add new rows to tables (services, implementations, checklist items)
   - Mark gaps/checklist items as done with commit hashes
   - Add new glossary terms
   - Remove or strike through information that's now incorrect
3. **Keep edits scoped** — only update the sections the trigger says to update. Don't rewrite the whole document
4. **Record** each update in the Phase 2 retrospective §6 table

**Skip rules:**
- If the trigger matched but the document already reflects reality → skip (no-op)
- If the trigger is "rarely triggered" and the session didn't touch that area → skip
- If the update would be purely cosmetic (rephrasing, reformatting) → skip

### Phase 4: Write New Docs

For each architectural decision or pattern discovered during implementation:

1. **ADR-worthy** (framework choice, schema design, service boundary, auth strategy) → Write a new ADR in `docs/adr/<domain>/NNNN-title.md` following the ADR template.
2. **Pattern-worthy** (reusable implementation pattern, gotcha fix) → Write a pattern reference doc in `docs/<PATTERN-NAME>.md` with a template, common pitfalls, and existing implementations.
3. **Note-worthy only** → Document in the retrospective and in the implementation plan.

### Phase 5: Present the Summary

Output a concise summary table:

```
## Retro Complete

### Documents created
| File | Purpose |

### Documents updated
| File | What changed |

### Architecture & reference docs updated
| File | Trigger | What changed |

### Key findings
- Shipped beyond plan: ...
- Deferred & documented: ...
- Deferred & undocumented (now tracked): ...
- New patterns/ADRs: ...
```

## Document Detection

When surveying, look for these stale-document signals:

| Signal | Meaning |
|--------|---------|
| ADR status is "Proposed" but feature is shipped | Needs acceptance |
| Planning doc date is before the session | Needs update |
| Checklist items are unchecked but work is done | Needs checkmarks |
| ADR mentions only planned items, not implemented ones | Needs new decision sections |
| Feature table has 🟡 Shell where implementation is real | Needs status update |
| "Last updated" date on IMPLEMENTATION-PLAN.md is stale | Needs refresh |
| Flyway migration adds JSONB/TEXT[]/ENUM/BYTEA but no converter pair registered | **Schema compliance gap** — triggers full workflow; document in §3 or §4 |
| R2DBC entity field type mismatches its DB column (String↔JSONB, Set↔TEXT[], etc.) | **Schema compliance gap** — fix or add converters; document in retrospective |
| `@WritingConverter` for JSONB returns `String` instead of `io.r2dbc.postgresql.codec.Json` | **Wire-type bug** — CRITICAL; will cause `jsonb vs character varying` error at persist time |

### Architecture & Reference Doc Staleness Signals

| Signal | Likely Document | Meaning |
|--------|----------------|---------|
| New backend service scaffolded this session | ARCHITECTURE.md, SERVICE-ARCHITECTURE.md, LOGGING-ARCHITECTURE.md, IMPLEMENTATION-PLAN.md | All need new service rows |
| Service role significantly changed | ARCHITECTURE.md | Update §3 component table |
| New protocol added (WebSocket, SSE, gRPC) | ARCHITECTURE.md | Update component role + glossary |
| New infrastructure service added to Compose | ARCHITECTURE.md | Update §3 infrastructure table |
| New Gradle module created | SERVICE-ARCHITECTURE.md | Add §1 style table row |
| Observability work shipped (logging, tracing, metrics) | LOGGING-ARCHITECTURE.md, TRACE-PROPAGATION.md | Update phase status + checklists |
| Redis or Kafka code changed this session | REDIS-KAFKA-PRODUCTION-GAP.md | Check if any gap was closed |
| Production gap closed (any R# or K# resolved) | REDIS-KAFKA-PRODUCTION-GAP.md | Mark gap ✅ Done with commit hash |
| PBAC grammar or auth model changed | PBAC-AUTHORIZATION.md | Update resource types / policies |
| New service adopts PBAC enforcement | PBAC-AUTHORIZATION.md, PBAC-ENFORCEMENT-PATTERN.md | Add to enforcement matrix + implementations |
| JSONB column added via Flyway migration | R2DBC-JSONB-CONVERTER-PATTERN.md | Verify converter exists; add to implementations |
| New idempotency mechanism deployed | IDEMPOTENCY-PATTERN.md | Add to pattern description |
| Angular auth code changed | AUTH-INTERCEPTOR-PATTERN.md | Verify interceptor pattern still accurate |
| Auth token handling changed | REFRESH-TOKEN-ROTATION.md | Verify rotation design still accurate |
| RxJS single-flight pattern applied to new operation | RXJS-SINGLE-FLIGHT-PATTERN.md | Add to implementations list |
| PrimeNG major version upgraded | PRIMENG-MENU-STABLE-REFERENCE.md | Verify pattern still applies |
| Phase 7 (insight service) design decisions made | INSIGHT-SERVICE-SKETCH.md | Update sketch with new decisions |
| ARCHITECTURE.md §3 describes something stale (wrong service count, missing protocol, old component name) | ARCHITECTURE.md | Spot-fix the stale paragraph |
| IMPLEMENTATION-PLAN.md "Last updated" is not today after a code-shipping session | IMPLEMENTATION-PLAN.md | Update date + phase status |
| A gap-analysis doc lists a gap as open that was closed this session | REDIS-KAFKA-PRODUCTION-GAP.md | Mark gap closed |
| A pattern doc references a source file that no longer exists | That pattern doc | Update source file references |

## What Makes a Good Retrospective

### Do
- Reference specific commit hashes for every implemented item
- Link every deferred item to its tracking doc (ADR, scope review, etc.)
- Be honest about what was NOT done — undocumented deferrals become lost work
- Recommend the right format for new decisions (ADR vs pattern doc vs note)
- Update documents inline, not just describe what needs updating
- Check the architecture/reference docs matrix — a stale ARCHITECTURE.md after adding a new service is a missed signal

### Don't
- Skip the undocumented-gaps sweep — this is the highest-value part
- Leave ADRs in "Proposed" state when they were implemented
- Write the retro without reading the actual commits and files
- Create ADRs for UX decisions or trivial choices
- Update documents with placeholder or vague descriptions
- Skip the architecture/reference docs sweep — a stale ARCHITECTURE.md makes every future session start with wrong assumptions

## Integration

| Skill/Agent | Relationship |
|-------------|-------------|
| architecture-decision-records | The retro uses ADR conventions (status lifecycle, format). ADR skill handles individual decision capture; retro handles bulk reconciliation. |
| doc-updater agent | The retro may delegate individual doc updates to doc-updater if many files need changes. |
| planner agent | The retro compares actual implementation against the planner's original blueprint. |
| `common/database-design.md` rule | **Mandatory compliance check** whenever Flyway migrations or R2DBC entities are touched. The retro verifies Tier 2 types have converters and entity fields match column types. |
| Architecture & Reference Docs Update Matrix (embedded above, Phase 3b) | Defines when each `docs/*.md` file needs updating. Consulted during Phase 1 survey and executed during Phase 3b. |
