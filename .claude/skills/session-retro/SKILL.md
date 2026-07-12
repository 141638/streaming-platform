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

If the answer to all three is **no**, skip directly to Phase 5 and report: **"No pattern deemed noticeable or needs updating."** Do not create or modify any documents. This prevents noise documents that dilute the value of real retrospectives.

## When to Activate

- User says "retro", "retrospective", "/retro", "wrap up the session", "let's do a review of what we did"
- End of a multi-commit feature-building session
- Before switching to a new feature or phase
- After any session where ADRs, the implementation plan, or planning docs were touched

## The Five Questions

Every retro must answer these five questions:

1. **What was implemented** according to the planning ahead (blueprint, scope review, implementation plan)?
2. **What was deferred** (planned but not implemented) and where is it tracked?
3. **What was deferred but NOT documented** — gaps found during the retro itself?
4. **What architectural decisions** were made during implementation that need a new ADR or pattern doc?
5. **What existing docs are now stale** and need updating?

## Workflow

### Phase 1: Survey (read-only)

```
1. Read the master implementation plan (docs/IMPLEMENTATION-PLAN.md)
2. Read all planning docs under docs/plans/
3. Read all relevant ADRs (scan docs/adr/ for the services touched)
4. Read the git log for this session's commits (git log --oneline -30)
5. List all migration files, new source files, and new frontend components
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

## 6. Updated execution order (actual vs planned)
[Table: planned commit | actual commit | status]

## 7. Key risks carried forward
[Numbered list with mitigation references]
```

### Phase 3: Update Stale Documents

For each stale document identified in Phase 2, apply the update:

1. **ADRs:** If a Proposed ADR was fully implemented, mark it **Accepted** with the current date. Add new decision sections for architectural choices made during implementation. Update the References and source files list to match reality.
2. **Scope review / blueprint:** Mark status (e.g., "Ready for review" → "Implemented"). Update feature tables to reflect what was actually built (not just planned). Add commit hashes for traceability.
3. **Implementation plan:** Add new completed work items. Update the checklist. Update Phase readiness context. Add new ADRs to the ADR table. Update the "Last updated" date.

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

## What Makes a Good Retrospective

### Do
- Reference specific commit hashes for every implemented item
- Link every deferred item to its tracking doc (ADR, scope review, etc.)
- Be honest about what was NOT done — undocumented deferrals become lost work
- Recommend the right format for new decisions (ADR vs pattern doc vs note)
- Update documents inline, not just describe what needs updating

### Don't
- Skip the undocumented-gaps sweep — this is the highest-value part
- Leave ADRs in "Proposed" state when they were implemented
- Write the retro without reading the actual commits and files
- Create ADRs for UX decisions or trivial choices
- Update documents with placeholder or vague descriptions

## Integration

| Skill/Agent | Relationship |
|-------------|-------------|
| architecture-decision-records | The retro uses ADR conventions (status lifecycle, format). ADR skill handles individual decision capture; retro handles bulk reconciliation. |
| doc-updater agent | The retro may delegate individual doc updates to doc-updater if many files need changes. |
| planner agent | The retro compares actual implementation against the planner's original blueprint. |
