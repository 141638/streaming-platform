---
description: Create an implementation plan before writing code. WAIT for user CONFIRM before touching code.
argument-hint: "[feature description]"
---

# /plan — Implementation Planning

Create a step-by-step implementation plan before writing any code.

## When to Use

- Starting a new feature
- Making architectural changes
- Working on complex refactoring
- Multiple files/components affected

## What Happens

1. **Understand** — Restate the requirement, ask clarifying questions if ambiguous
2. **Survey** — Check existing patterns in the codebase that the implementation should mirror
3. **Design** — Propose architecture: atomic design division (frontend) or DDD/hexagonal layering (backend), API contracts, data models
4. **Break Down** — Ordered phases with specific files, dependencies, and risks
5. **Present** — Show the plan and WAIT for explicit confirmation

## Output Format

```markdown
# Plan: [Feature Name]

## Summary
[2-3 sentences]

## Patterns to Mirror
| Category | Source | Pattern |
|----------|--------|---------|
| Naming | path:line | convention |
| Errors | path:line | convention |
| Tests | path:line | convention |

## Files to Change
| File | Action | Why |
|------|--------|-----|

## Tasks
### Task 1: [Name]
- **Action**: What to do
- **Files**: Exact paths
- **Validate**: Command to verify correctness

## Risks
| Risk | Likelihood | Mitigation |
|------|-----------|------------|

## Validation
```bash
# Commands to verify the implementation
```
```

## After Planning

- Use `/implement --mode=full` to execute with the full pipeline
- Use `/implement --mode=step` to execute phase-by-phase with review
- Use `/implement` for quick single-task execution

**CRITICAL**: Do NOT write any code until the user confirms the plan.
