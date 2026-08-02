# Agent Orchestration

## Agent Design Standards

When creating or modifying agents, follow these rules. They exist to prevent context overflow, skill duplication, and stale external references.

### Knowledge Architecture Layering

Agents must use a 3-layer knowledge model — never inline domain knowledge:

```
Foundation skills (what)     →  Project skills (how WE use it)  →  Rules (our conventions)
─────────────────────────        ────────────────────────────        ─────────────────────
General Java/Spring patterns     R2DBC entities, WebFlux reactive    rules/java/*.md
Adapted for our stack            Project architecture, workflow      rules/common/*.md
```

- **Layer 1 — Foundation skills**: General technology knowledge (Java idioms, Spring patterns, security). These must be project-owned copies with adaptation headers, not external `origin: ECC` references. See `skill: conditional-skill-loading` for the rationale.
- **Layer 2 — Project skills**: How THIS project applies the foundation (architecture decisions, R2DBC patterns, workflow). These reference foundation skills and rules — they never duplicate their content.
- **Layer 3 — Rules**: Concise conventions that reference skills for details. Rules say *what*; skills say *how*.

**Anti-pattern**: An agent with 200+ lines of inline conventions. The agent should be a thin orchestrator with a skill-loading matrix, not a knowledge dump.

### Conditional Skill-Loading Matrix (MANDATORY for 4+ skills)

Every agent that loads 4 or more skills MUST include a boolean task→skill matrix. This prevents context overflow by loading only the skills relevant to the current task.

Reference: `skill: conditional-skill-loading` for the full pattern and design rules.

```markdown
## Skill Loading Matrix

| Task | skill-a | skill-b | skill-c | skill-d |
|------|:---:|:---:|:---:|:---:|
| **Task type 1** | ✅ | ✅ | — | ✅ |
| **Task type 2** | ✅ | — | ✅ | — |
| **Surgical fix** | ✅ | — | — | — |

**CRITICAL**: Never load all skills for every task. Classify the task, consult the matrix, load only checked columns.
```

Design rules for the matrix:
- **1-2 always-loaded columns**: Skills every non-trivial task needs (coding standards, architecture)
- **Conditional columns**: Skills gated by domain (database, auth, caching)
- **Surgical fix row**: Minimal load for small bug fixes
- **Greenfield row**: All columns checked — use sparingly, only when the task genuinely spans the full stack

### Skill Ownership

All skills an agent loads must be **project-owned** (`origin: project`). Never wire an agent to an external `origin: ECC` skill directly. Instead:

1. Copy the external skill into `.claude/skills/<name>/`
2. Change `origin` to `project`
3. Add an adaptation header explaining what applies and what doesn't for this project's stack
4. Add translation tables where the skill's examples use a different framework (JPA→R2DBC, MVC→WebFlux, servlet→reactive security)

This ensures skills can be committed to git and survive external repo changes/deletions.

## Service Context Loading

Before modifying files in any backend service, read that service's architecture ADR-0000
as the first survey step. These ADRs document the architectural style, package structure,
entity patterns, and third-party integration patterns — reading them prevents agents from
violating conventions or re-discovering what's already documented.

| Service directory | ADR |
|-------------------|-----|
| `stream-service/` | `docs/adr/stream/0000-architecture-foundation.md` |
| `chat-service/` | `docs/adr/chat/0000-architecture-foundation.md` |
| `auth-service/` | `docs/adr/auth/0000-architecture-foundation.md` |

Skip if the same ADR-0000 was already read earlier in the same session.

## Available Agents

Located in `~/.claude/agents/`:

| Agent | Purpose | When to Use |
|-------|---------|-------------|
| angular-developer | Angular frontend implementation — scaffolding, components, services, forms, routing, styling, and testing | Any Angular/frontend implementation work |
| java-backend-developer | Java Spring Boot backend implementation — REST APIs, services, R2DBC repositories, domain models, Flyway migrations, Redis, Kafka | Any Java/Spring Boot backend implementation work |
| planner | Implementation planning | Complex features, refactoring |
| architect | System design | Architectural decisions |
| tdd-guide | Test-driven development | New features, bug fixes |
| code-reviewer | Code review | After writing code |
| security-reviewer | Security analysis | Before commits |
| build-error-resolver | Fix build errors | When build fails |
| e2e-runner | E2E testing | Critical user flows |
| refactor-cleaner | Dead code cleanup | Code maintenance |
| doc-updater | Documentation | Updating docs |
| session-retro | Session-end retrospective — reconciles implementation against plans, updates stale ADRs + planning docs + architecture/reference docs, captures deferrals | End of feature-building sessions |
| feature-commit | Feature-by-feature committing | When grouping and committing changes |
| rust-reviewer | Rust code review | Rust projects |
| harmonyos-app-resolver | HarmonyOS app development | HarmonyOS/ArkTS projects |

## Immediate Agent Usage

No user prompt needed:
1. Complex feature requests - Use **planner** agent
2. Angular/frontend implementation - Use **angular-developer** agent
3. Java/backend implementation - Use **java-backend-developer** agent
4. Code just written/modified - Use **code-reviewer** agent
5. Bug fix or new feature - Use **tdd-guide** agent
6. Architectural decision - Use **architect** agent
7. End of feature-building session - Use **session-retro** agent (or `/retro` command)

## Parallel Task Execution

ALWAYS use parallel Task execution for independent operations:

```markdown
# GOOD: Parallel execution
Launch 3 agents in parallel:
1. Agent 1: Security analysis of auth module
2. Agent 2: Performance review of cache system
3. Agent 3: Type checking of utilities

# BAD: Sequential when unnecessary
First agent 1, then agent 2, then agent 3
```

## Multi-Perspective Analysis

For complex problems, use split role sub-agents:
- Factual reviewer
- Senior engineer
- Security expert
- Consistency reviewer
- Redundancy checker

## Delegation Transparency

**Purpose:** Make agent delegation visible and auditable so the user can optimize agent/skill effectiveness over time.

### Before Delegation

Every time a task is delegated to a named agent, print a one-line notice to the console:

```
🤖 Delegating to <agent-name>: <one-line summary of the task>
```

### After Completion

When a delegated agent completes work that involved **modifying code or files**, print a summary report:

```
📋 Agent Report: <agent-name>
   ✅ <file> — <what changed, 1 line>
   ✅ <file> — <what changed, 1 line>
   ❌ <file> — <issue encountered>   (if any)
```

Skip the report when the agent only performed read-only analysis (search, review, exploration) with no modifications. The report is for mutating work only.

### Why

Tracking delegation patterns reveals:
- Which agents are most/least used — prune underused ones
- Whether the right agent was picked for the task type — tune the trigger table
- Whether an agent's output consistently needs manual cleanup — refine its prompt or retire it
