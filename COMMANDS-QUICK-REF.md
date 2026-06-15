# Commands Quick Reference

> 10 slash commands for the streaming platform project.

---

## Core Workflow

| Command | What it does |
|---------|-------------|
| `/plan` | Create implementation plan with architecture design, task breakdown, risk assessment — **waits for confirm before touching code** |
| `/implement` | Flexible implementation — `--mode=full` (plan→implement→review), `--mode=quick` (single task, default), `--mode=step` (phase-by-phase). Add `--tdd` for test-first. |
| `/review` | Code review of local changes or GitHub PR. Severity: CRITICAL/HIGH/MEDIUM/LOW |
| `/perform` | Quick generation/prototyping — no pipeline, no tests, no gates |
| `/consult` | Debug partner and technical advisor — read-only analysis, root-cause investigation |

---

## Utilities

| Command | What it does |
|---------|-------------|
| `/build-fix` | Detect and fix build errors — delegates to java-build-resolver or typescript-reviewer |
| `/docs` | Look up current library/API documentation via Context7 |

---

## Self-Learning

| Command | What it does |
|---------|-------------|
| `/learn` | Extract reusable patterns from the current session |
| `/learn-eval` | Extract patterns + self-evaluate quality before saving |
| `/skill-create` | Analyze git history → generate a reusable skill |

---

## Quick Decision Guide

```
Starting a feature?              → /plan first, then /implement --mode=full
Single change?                   → /implement (auto quick mode)
Quick prototype?                 → /perform
Code just written?               → /review
Build broken?                    → /build-fix
Need API/library docs?           → /docs <framework>
Something broken, don't know why? → /consult
Want to capture what you learned? → /learn-eval
Processing a Japanese BD?        → doc-analyzer agent, then /plan
```

---

## Mode Flags for `/implement`

```
/implement                          # Auto-detect: single file→quick, multi-file→prompts
/implement --mode=full <feature>    # Full pipeline: plan → implement → review
/implement --mode=quick <task>      # Just do it, no ceremony
/implement --mode=step <feature>    # Phase-by-phase, review between each
/implement --tdd                    # Write tests first (red-green-refactor)
/implement --review                 # Run code-reviewer after implementation
/implement --be                     # Backend-only (Java/Spring agents)
/implement --fe                     # Frontend-only (Angular/TypeScript agents)
```

---

## Heavy Pipeline (Preserved)

For complex microservice projects needing full orchestration, see `../agent-harness-template/`:
- `orch-*` commands — gated Research→Plan→TDD→Review→Commit pipeline
- `multi-*` commands — multi-model planning (Codex + Gemini)
- `prp-*` commands — formal PRD→Plan→Implement→PR workflow
