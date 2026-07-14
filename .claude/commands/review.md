---
description: Code review — local uncommitted changes or GitHub PR.
argument-hint: "[pr-number | pr-url | blank for local review]"
---

# /review — Code Review

Comprehensive review of code changes. Local diff or GitHub PR.

**Before you begin**, record telemetry:
`node .claude/scripts/telemetry-track.mjs --command review`

## Local Review Mode (default)

Review uncommitted changes:

1. **Gather** — `git diff` and `git diff --staged` to see all changes
2. **Read** — Read each changed file in full, plus surrounding context
3. **Check** — Security (secrets, injection, XSS, auth gaps), correctness (logic, nulls, edge cases), quality (naming, nesting, error handling), patterns (matches project conventions)
4. **Report** — Findings by severity with file:line references and fix suggestions

## PR Review Mode

When given a PR number or URL:

```
/review 42
/review https://github.com/owner/repo/pull/42
```

1. **Fetch** — `gh pr view` + `gh pr diff`
2. **Context** — Read project rules, check for related plans/PRDs
3. **Review** — Full file contents at PR head, not just diff hunks
4. **Validate** — Run type-check, lint, tests
5. **Report** — Findings + decision (APPROVE / REQUEST CHANGES / BLOCK)
6. **Publish** — Post review to GitHub (optional, confirm first)

## Severity Levels

| Severity | Meaning | Action |
|----------|---------|--------|
| CRITICAL | Security vuln, data loss | Must fix before merge |
| HIGH | Bug likely to cause issues | Should fix before merge |
| MEDIUM | Code quality, best practice | Fix recommended |
| LOW | Style nit, suggestion | Optional |

## Approval Criteria

- **Approve**: No CRITICAL or HIGH issues (zero findings = valid approval)
- **Warning**: HIGH issues only
- **Block**: CRITICAL issues found

Don't manufacture findings. A clean diff should be approved cleanly.
