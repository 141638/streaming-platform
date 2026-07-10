---
name: skill-create
description: Create skills, agents, and commands for Claude Code. For skills/agents/commands described by the user, write the appropriate files under .claude/{skills,agents,commands}/. Also supports the git-history analysis mode for extracting patterns from commits. Write operations to .claude/skills/**, .claude/agents/**, and .claude/commands/** are pre-authorized — no permission prompt needed.
allowed_tools: ["Bash", "Read", "Write", "Grep", "Glob"]
---

# /skill-create — Skill, Agent & Command Authoring

Create or update Claude Code skills, agents, and commands. Supports two modes:

1. **Manual authoring** (primary) — the user describes a workflow; you write the SKILL.md, agent `.md`, and command `.md` files
2. **Git analysis** (secondary) — parse git history to detect coding patterns and generate SKILL.md files

## File Locations

| Artifact | Path | Format |
|----------|------|--------|
| Skill | `.claude/skills/<name>/SKILL.md` | YAML frontmatter (`name`, `description`, `metadata`) + Markdown body |
| Agent | `.claude/agents/<name>.md` | YAML frontmatter (`name`, `description`, `tools`, `model`) + Markdown body |
| Command | `.claude/commands/<name>.md` | YAML frontmatter (`description`, `argument-hint`) + Markdown body |

All three paths are **pre-authorized for Write** — no permission prompt.

## Manual Authoring Mode

When the user describes a workflow they want automated:

1. **Identify the scope** — is this a skill (knows how to do something), an agent (specialized role with tools), or a command (user-facing slash command)? Usually you need all three.
2. **Check existing conventions** — scan `.claude/skills/`, `.claude/agents/`, `.claude/commands/` for format patterns to mirror
3. **Write all three files** in parallel:
   - **Skill** (`SKILL.md`): the detailed workflow, when to activate, step-by-step process, anti-patterns, integration notes
   - **Agent** (`.md`): role definition, tools list, model preference, core responsibilities, quality checklist
   - **Command** (`.md`): description, argument hint, when to use, examples
4. **Register the agent** — update `.claude/rules/common/agents.md` to add the new agent to the Available Agents table and, if appropriate, the Immediate Agent Usage triggers
5. **Confirm** — report what was created and how to invoke it

## Manual Authoring Conventions

### Skill frontmatter
```yaml
---
name: <kebab-case>
description: <one-line summary of what the skill does and when to use it>
metadata:
  origin: project
---
```

### Agent frontmatter
```yaml
---
name: <kebab-case>
description: <one-line role summary. Use at end of session / via /command.>
tools: ["Bash", "Read", "Write", "Edit", "Grep", "Glob"]
model: <sonnet | opus | haiku>  # sonnet for most; opus for deep-reasoning roles; haiku for mechanical
---
```

Agent body must include:
- **Prompt Defense Baseline** section (standard block)
- **Role statement** — "You are a ..."
- **Core Responsibilities** — numbered list
- **Process** — step-by-step workflow
- **Quality Checklist** — verification items

### Command frontmatter
```yaml
---
description: <one-line description shown in command palette>
argument-hint: "<optional args hint>"
---
```

### Command body — REQUIRED: explicit tool routing

The first section after the title MUST contain an explicit **TOOL ROUTING** directive. Commands are text-based — there is no hard binding between a command and a skill/agent. Claude reads the command file and decides which tools to call. If the routing instruction is vague ("invokes the X skill"), Claude might follow the instructions inline without actually loading the skill.

**Every command MUST open with an unambiguous directive:**

```markdown
# /command-name — Short Title

**TOOL ROUTING:** When this command is invoked, you MUST call `Skill({skill: "<skill-name>"})`. This loads the full workflow. Do NOT attempt to run this inline without loading the skill.
```

For commands that primarily use an agent (not a skill):

```markdown
**TOOL ROUTING:** When this command is invoked, you MUST call `Agent({subagent_type: "<agent-name>", description: "<short task description>"})`. Do NOT attempt this work inline.
```

For commands that use both:

```markdown
**TOOL ROUTING:** 
1. First, call `Skill({skill: "<skill-name>"})` to load the workflow.
2. The skill may then delegate to `Agent({subagent_type: "<agent-name>"})` for execution.
```

**Anti-pattern — vague routing (do NOT do this):**
```markdown
# /foo — Does something

Invokes the **foo** skill, which performs a workflow...   ← Claude might skip the Skill() call
```

### Registration in agents.md
Add a row to the Available Agents table:
```
| <name> | <short purpose> | <when to use> |
```
Add to Immediate Agent Usage if the agent should trigger automatically.

## Git Analysis Mode (secondary)

When the user explicitly asks to analyze git history:

```bash
/skill-create                    # Analyze current repo
/skill-create --commits 100      # Analyze last 100 commits
```

Follows the original analysis pipeline: gather git data → detect patterns → generate SKILL.md.

## Related Commands

- `/retro` — session-end retrospective (uses session-retro skill)
- `/commit` — feature-by-feature committing (uses feature-commit skill)
