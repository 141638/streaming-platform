---
name: conditional-skill-loading
description: "Prevent agent context overflow by loading only the skills relevant to the current task, using a boolean task→skill matrix instead of loading all skills."
user-invocable: false
origin: auto-extracted
---

# Conditional Skill-Loading Matrix

**Extracted:** 2026-08-02
**Context:** Agents with 4+ skills that can overflow context if loaded unconditionally

## Problem

Agents wired with many skills (6+) risk context overflow when all are loaded for every task. However, different task types (REST endpoint vs Flyway migration vs auth config) need different subsets. Loading everything is wasteful; loading too little misses critical conventions.

## Solution

Add a **boolean task→skill matrix** to the agent definition. Each row is a task type; each column is a skill. A checkmark means "load this skill for this task." The agent classifies the task first, then loads only the checked columns.

### Matrix format

```markdown
## Skill Loading Matrix

Not all skills are needed for every task. Load only what the task requires — context is finite.

| Task | skill-a | skill-b | skill-c | skill-d |
|------|:---:|:---:|:---:|:---:|
| **REST endpoint** | ✅ | ✅ | — | ✅ |
| **DB migration** | — | — | ✅ | — |
| **Auth config** | ✅ | — | — | ✅ |
| **Surgical bug fix** | ✅ | — | — | — |
| **Greenfield feature** | ✅ | ✅ | ✅ | ✅ |

**CRITICAL**: Never load all skills for every task. Consult this matrix and load only the checked columns.
```

### Design rules

1. **Always-loaded column**: 1-2 skills that every task needs (e.g., coding standards, project architecture). These get a checkmark in every row except purely mechanical tasks (like a standalone migration).

2. **Conditional columns**: Skills gated by domain (database, auth, caching). Only checked when the task touches that domain. A Flyway migration doesn't need the REST API skill.

3. **Surgical fix row**: A minimal row for small bug fixes — loads only the always-loaded skill(s). Keeps context small for quick fixes.

4. **Greenfield row**: A "build from scratch" row that checks all columns. Use sparingly — only when the task genuinely spans the full stack.

### Agent process update

Replace "load all skills in order" with a classify-then-load pattern:

```markdown
## Process

1. **Classify the task** → Which row in the matrix matches? Load ONLY the checked skills.
2. **Orient** → Read service ADR → load matched skills → read relevant project rules
3. **Implement** → Follow conventions from loaded skills
4. **Verify** → Build + tests
```

This replaces the anti-pattern of listing all skills in a fixed load order regardless of task.

## When to Use

- An agent references 4+ skills and context budget is tight
- Different task types genuinely need different subsets
- You find yourself writing "load if X" prose for each skill — the matrix is cleaner
- The agent definition is growing unwieldy with conditional loading descriptions

## When NOT to Use

- 1-3 skills: the matrix overhead isn't worth it — just load all
- All tasks need all skills equally: the matrix adds no signal
- The skills are tiny (<50 lines each): context overflow isn't a real risk
