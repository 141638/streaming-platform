# Production Readiness Audit — Multi-Agent Fan-Out Pattern

**Extracted:** 2026-07-28
**Context:** Auditing a multi-service platform for production readiness gaps after phases 2-5 were marked "done" but didn't feel solid.

## Problem

When a platform has multiple completed phases with many services, it's hard to know what was missed. Single-developer code review misses cross-cutting issues (error handling consistency, security defaults, infrastructure maturity). Phase-specific review misses systemic patterns (e.g., "every service logs `ex.getMessage()` instead of passing `ex`").

## Solution

Fan out 6 specialized agents in parallel, structured as **4 vertical (per-phase) + 2 horizontal (cross-cutting)**:

### Vertical Agents — Deep Code Audit Per Phase

For each completed phase, launch a `code-explorer` agent with a focused prompt covering:
1. Architecture compliance (does the code match the ADR?)
2. Error handling gaps (swallowed errors, missing exception handlers)
3. Missing tests (what test files should exist but don't?)
4. Edge cases (what happens on double-submit, crash mid-operation, null input?)
5. Deferred items still in code (TODO markers, skeleton implementations)

Each agent prompt must:
- Name specific directories to search
- Name specific files to read (not just patterns to grep)
- Ask for concrete file:line references (never generic advice)
- Cover the phase's unique concerns (e.g., Phase 2 = outbox TX boundary, Phase 3 = cache-aside correctness)

### Horizontal Agents — Cross-Cutting Concerns

1. **Silent-failure hunter** (`silent-failure-hunter` agent): Search ALL services for `.subscribe()` without error handler, `.onErrorResume()` that swallows, `.block()` in reactive code, `catch(Exception)` too broad, logging that drops stack traces.

2. **Security reviewer** (`security-reviewer` agent): Search ALL services for hardcoded secrets, missing input validation, CORS misconfiguration, health endpoint exposure, error response info leaks, cookie security flags.

### The Prompt Template

Each agent prompt should follow this structure:
```
Focus on these specific areas and report concrete gaps:
1. [Area 1] — specific files to read
2. [Area 2] — specific patterns to search for
...
Report back with SPECIFIC file paths, line numbers, and concrete findings.
Do NOT give generic advice. Every finding must reference actual code.
```

### Consolidation

After all agents report, deduplicate findings and classify by:
- **Severity**: CRITICAL (fix before deploy) / HIGH (fix before production) / MEDIUM (fix when convenient) / LOW (polish)
- **Category**: Correctness / Security / Observability / Error Handling / Tests / Config
- **Systematic vs. Individual**: A finding that repeats across 3+ services is a systematic weakness, not a one-off bug

## When to Use

- Before declaring any multi-phase project "production ready"
- When you have a feeling "something's missing" but can't articulate what
- After completing 2+ phases of a multi-service platform
- Before a security review or penetration test
- When preparing for a production deployment

## Expected Output

A gap analysis retrospective with:
- Findings table (severity, service, file:line, concrete description)
- Systematic weaknesses section (patterns spanning services)
- Remediation blueprint with specific files, changes, and validation steps
- Dependency graph showing which fixes must come first

## Example

From the streaming platform audit (2026-07-28):
- 4 phase agents found 67 findings across stream/chat/viewer/notification services
- Silent-failure hunter found 32 findings across 5 services
- Security reviewer found 20 findings across 5 services + configs
- After deduplication: 50+ unique findings (8 CRITICAL, 16 HIGH, 19 MEDIUM, 7 LOW, 8 systematic)
- Produced [gap analysis retrospective](phase-2-5-production-gap-analysis-retrospective.md) + [7-track remediation blueprint](phase-2-5-gap-remediation-blueprint.md)
