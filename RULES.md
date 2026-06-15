# Rules

## Must Always
- Follow established project patterns before inventing new ones.
- Write tests for new code paths — TDD when using `/implement --tdd`.
- Validate inputs at system boundaries and keep security checks intact.
- Prefer immutable updates over mutating shared state.
- Keep contributions focused, reviewable, and well-described.
- Commit with conventional commits format.

## Must Never
- Include sensitive data (API keys, tokens, secrets, absolute paths) in output.
- Submit untested changes that break the build.
- Bypass security checks or validation hooks.
- Duplicate existing functionality without a clear reason.
- Silently swallow errors — log context server-side, show user-friendly messages in UI.

## Agent Format
- Agents live in `agents/*.md`.
- YAML frontmatter with `name`, `description`, `tools`, and `model`.
- File names lowercase with hyphens, matching the agent name.
- Descriptions communicate when the agent should be invoked.

## Skill Format
- Skills live in `skills/<name>/SKILL.md`.
- YAML frontmatter with `name`, `description`, and `origin`.
- `origin: ECC` for first-party skills, `origin: community` for imported skills.
- Skill bodies include practical guidance, examples, and "When to Use" sections.

## Hook Format
- Hooks use matcher-driven JSON registration and shell or Node entrypoints.
- Matchers should be specific, not broad catch-alls.
- Exit `1` only when blocking behavior is intentional; otherwise exit `0`.

## Commit Style
- Use conventional commits: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`, `perf:`, `ci:`.
- Keep changes modular. Explain user-facing impact in PR summaries.
