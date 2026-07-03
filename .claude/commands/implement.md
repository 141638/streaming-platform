---
name: implement
description: Flexible implementation — full pipeline, quick single-task, or step-by-step. The primary workhorse command.
argument-hint: "[--mode=full|quick|step] [--tdd] [--review] <task description>"
---

# /implement — Flexible Implementation

The primary workhorse. Three modes to match how you want to work.

## Mode Selection

| Flag | Mode | When |
|------|------|------|
| `--mode=full` | Full pipeline | Important features — plan → implement → review |
| `--mode=quick` | Quick task | Single change, no ceremony (default for small tasks) |
| `--mode=step` | Step-by-step | Phase at a time, review between each |
| (none) | Auto-detect | Single file → quick, multi-file → prompts for mode |

## Flags

| Flag | Effect |
|------|--------|
| `--tdd` | Write tests first (red-green-refactor) |
| `--review` | Run code-reviewer after implementation |
| `--be` | Backend-only (use Java/Spring agents) |
| `--fe` | Frontend-only (use Angular/TypeScript agents) |

## Mode: Full Pipeline

```
/implement --mode=full Add OAuth2 login flow
```

1. **Plan** — Delegate to `planner` agent → [GATE: user approves]
2. **Implement** — Execute each task. If `--tdd`: write failing test → make it pass → refactor
3. **Review** — Run `code-reviewer`. `security-reviewer` auto-triggered on auth/input/DB changes
4. **Report** — Summary of changes, tests written, files touched

## Mode: Quick

```
/implement --mode=quick Add a health check endpoint to stream-service
```

1. Understand the task
2. Read relevant existing code
3. Implement the change
4. Run build/tests to verify
5. Report what was done

No plan, no formal review, no gates. Just get it done.

## Mode: Step-by-Step

```
/implement --mode=step Refactor the chat service to use Redis Streams
```

1. Plan and present Phase 1 → wait for approval
2. Implement Phase 1 → show results → wait before Phase 2
3. Continue until all phases complete

You review each phase before the next begins.

## Inner Structure Decisions

When implementing, follow the project's established architecture:
- **Backend**: Layered reactive (Controller → Service → Repository) with hexagonal ports where multiple implementations exist. See `docs/SERVICE-ARCHITECTURE.md`.
- **Frontend**: Atomic design (pages → organisms → molecules → atoms). See `rules/angular/patterns.md`.
- **API contracts**: Consistent envelope format per `skills/api-design/SKILL.md`.

## After Implementation

- `/review` for a formal code review
- `/build-fix` if something breaks
- `/consult` to debug issues
