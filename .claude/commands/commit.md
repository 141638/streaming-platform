---
description: Group working-tree changes into feature-by-feature commits — each independently reviewable, scoped by domain, with clear conventional-commit messages.
argument-hint: "[--push] — optionally push after committing"
---

# /commit — Feature-by-Feature Commits

**TOOL ROUTING:** When this command is invoked, you MUST call `Skill({skill: "feature-commit"})`. This loads the full grouping algorithm and scope conventions. Do NOT attempt to commit inline without loading the skill.

## When to Use

- After implementing a feature across multiple files
- When `git status` shows changes across several domains
- Before pushing to remote — always group first

## What It Does

The **feature-commit** skill performs a six-step process:

1. **Inventory** — `git status` + `git diff --stat` to list every changed file
2. **Cluster** — Group files by the *reason* they changed (each group = one commit)
3. **Order** — Arrange commits logically: schema → domain → API → frontend contracts → components → polish → docs
4. **Write message** — Conventional commit format: `<type>(<scope>): <description>`
5. **Verify** — Confirm each commit is independently coherent
6. **Execute** — Stage, commit each group sequentially, report summary

## Scope Convention

| Scope | Domain |
|-------|--------|
| `stream` | Backend stream-service |
| `chat` | Backend chat-service |
| `auth` | Backend auth-service |
| `ui` | Angular frontend |
| `adr` | Architecture decision records |
| `plans` | Planning documents |

## Examples

```
/commit                 # Group and commit all changes
/commit --push          # Group, commit, and push to remote
```
