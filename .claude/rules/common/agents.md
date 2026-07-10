# Agent Orchestration

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
| planner | Implementation planning | Complex features, refactoring |
| architect | System design | Architectural decisions |
| tdd-guide | Test-driven development | New features, bug fixes |
| code-reviewer | Code review | After writing code |
| security-reviewer | Security analysis | Before commits |
| build-error-resolver | Fix build errors | When build fails |
| e2e-runner | E2E testing | Critical user flows |
| refactor-cleaner | Dead code cleanup | Code maintenance |
| doc-updater | Documentation | Updating docs |
| session-retro | Session-end retrospective | End of feature-building sessions |
| feature-commit | Feature-by-feature committing | When grouping and committing changes |
| rust-reviewer | Rust code review | Rust projects |
| harmonyos-app-resolver | HarmonyOS app development | HarmonyOS/ArkTS projects |

## Immediate Agent Usage

No user prompt needed:
1. Complex feature requests - Use **planner** agent
2. Code just written/modified - Use **code-reviewer** agent
3. Bug fix or new feature - Use **tdd-guide** agent
4. Architectural decision - Use **architect** agent
5. End of feature-building session - Use **session-retro** agent (or `/retro` command)

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
