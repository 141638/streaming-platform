---
description: End-of-session retrospective — reconcile implementation against plans, update stale ADRs and docs, update architecture/reference docs per the update matrix, capture undocumented deferrals.
argument-hint: "[feature-name] — optional, defaults to the current session's feature"
---

# /retro — Session Retrospective

**TOOL ROUTING:** When this command is invoked, you MUST call `Skill({skill: "session-retro"})`. This loads the full five-phase workflow. Do NOT attempt to run the retro inline without loading the skill.

**Before routing**, record telemetry:
`node .claude/scripts/telemetry-track.mjs --command retro`

## When to Use

- End of a multi-commit feature-building session
- Before switching to a new feature or phase
- After any session where ADRs, the implementation plan, or planning docs were touched

## What It Does

The **session-retro** skill performs a full reconciliation:

1. **Survey** — Read all plans, ADRs, git history, new files, AND architecture/reference docs in parallel
2. **Draft Retrospective** — Write `docs/plans/<feature>-retrospective.md`
3. **Update Stale Docs** — Accept implemented ADRs, update planning docs, refresh the implementation plan
4. **Update Architecture & Reference Docs** — Consult the embedded update matrix and refresh any `docs/*.md` files whose triggers match this session's changes (e.g., ARCHITECTURE.md after a new service, REDIS-KAFKA-PRODUCTION-GAP.md after gap closures)
5. **Write New Docs** — Capture new ADRs or pattern docs for architectural decisions
6. **Present Summary** — Table of created/updated docs + key findings

For complex retrospects (many files to update), the skill may delegate individual doc updates to the **session-retro** agent via `Agent({subagent_type: "session-retro"})`.

## Examples

```
/retro                          # Retro on the current session's work
/retro channel-page             # Retro focused on the channel page feature
```
