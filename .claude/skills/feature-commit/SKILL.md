---
name: feature-commit
description: Group working-tree changes into feature-by-feature or phase-by-phase commits — each independently reviewable, scoped by domain, with clear conventional-commit messages. Never lumps unrelated changes together. Invoke via /commit.
metadata:
  origin: project
---

# Feature-by-Feature Committing

Group changes into focused, independently reviewable commits organized by feature or phase. Each commit tells a single coherent story. Never lump unrelated changes into a monolithic "everything" commit.

> **Reference:** This skill covers the *grouping discipline* — how to split a messy working tree into coherent commits for THIS project. For general git mechanics (commit message format, merge vs rebase, conflict resolution, branch management), see the [[git-workflow]] skill.

## The Core Rule

**One commit = one coherent change.** A reviewer should understand the diff without cross-referencing other commits.

```
WRONG: "feat: channel page + fix chat bugs + update deps"
       → 47 files, 3 unrelated domains, impossible to revert one piece

RIGHT: "feat(stream): denormalize broadcaster identity on stream_session"
       → 4 files (migration, entity, service, JWT helper), all about identity
```

## Scope Convention

This project uses domain-based scopes. The scope is the **first decision** when grouping — it determines which files belong together.

| Scope | Domain | Example files |
|-------|--------|---------------|
| `stream` | Backend stream-service | `*Entity.java`, `*Repository.java`, `StreamService.java`, migrations |
| `chat` | Backend chat-service | `ChatService.java`, `ChatController.java`, chat migrations |
| `auth` | Backend auth-service | Auth controllers, JWT, login flow |
| `ui` | Frontend Angular | Components, services, contracts, styles |
| `adr` | Architecture decision records | `docs/adr/**` |
| `plans` | Planning documents | `docs/plans/**` |

**Split by domain when a feature spans both sides:**
```
feat(stream): add broadcaster profile endpoint
feat(ui): add About tab with bio + social links editing
```
Not one `feat: channel page About tab` lumping both.

**Documentation sub-scopes:**
```
docs(adr): accept ADR-0007 after channel page implementation
docs(plans): update scope review with About tab completion status
docs: add R2DBC JSONB converter pattern reference
```

> **Commit message format:** `<type>(<scope>): <description>` — imperative, lowercase, no period, max 72 chars. For the full type table (`feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`) and good/bad examples, see [[git-workflow]] §"Commit Messages".

## Grouping Algorithm

### Step 1: Inventory

```bash
git status --short
git diff --stat
```

List every changed file grouped by directory/domain.

### Step 2: Cluster by reason

Group files that changed for the **same reason**. For each candidate group, answer three questions:

| Question | Gate |
|----------|------|
| Why did these files change? | Must be exactly ONE reason |
| Can this group stand alone? | Build/tests pass with only these files committed? |
| Is this group independently reviewable? | Can someone understand it without the next commit? |

**Clustering patterns proven in this project:**

| Pattern | Files that travel together | Commit example |
|---------|---------------------------|----------------|
| Backend feature (small) | Migration + entity + repo + service + controller | `feat(stream): denormalize broadcaster identity` |
| Backend feature (large) | Split: (migration+entity) → (repo+service) → (controller+DTO) | Three sequential `feat(stream):` commits |
| Config-converter pair | `*ReadingConverter.java` + `*WritingConverter.java` + config registration | `fix(stream): use Json wire type for JSONB converters` |
| Frontend feature | Contract DTO + service method + component + template + style | `feat(ui): add category strip component` |
| Frontend polish | CSS + template tweaks on the SAME component only | `refactor(ui): tighten session-rail card layout` |
| Docs | ADR + its README index update | `docs(adr): add ADR-0000 for insight service` |
| Unrelated DTOs | Each DTO gets its own commit | Separate `feat(ui):` per contract |

### Step 3: Order commits

Commits tell the implementation story from foundation to polish:

```
1. Schema / config      ← migrations, converters, R2dbcConfig
2. Domain logic         ← entities, repositories, services
3. API surface          ← controllers, DTOs
4. Frontend contracts   ← DTO interfaces
5. Frontend components  ← organisms, molecules, pages
6. UI polish            ← CSS tweaks, alignment fixes
7. Documentation        ← ADRs, plans, pattern docs
```

This is a guideline. Small self-contained backend features (migration→controller, 4-6 files) ship as one commit.

### Step 4: Verify before committing

For each candidate commit:

- [ ] Does the diff tell **one coherent story**?
- [ ] Would `git revert <this-commit>` leave the codebase sensible?
- [ ] Are there leftover files in the working tree that belong to THIS commit?
- [ ] Will the build pass with only this commit? (backend: `compileJava` minimum)

### Step 5: Execute sequentially

Commit one group at a time. Check `git status` after EACH commit to confirm the working tree is shrinking correctly.

```bash
# Group 1: schema
git add <migration> <entity>
git commit -m "feat(stream): denormalize broadcaster identity on stream_session"
git status  # ← confirm remaining files don't belong to group 1

# Group 2: domain + API
git add <repo> <service> <controller> <dto>
git commit -m "feat(stream): add authenticated channel read endpoint"
git status

# ... repeat until working tree is clean
```

> **Git mechanics:** For amending, unstaging, stashing, and undoing mistakes during the commit process, see [[git-workflow]] §"Common Workflows" and the Quick Reference table.

## When to Lump (exhaustive — no other cases)

Only these three scenarios justify combining multiple logical changes into one commit:

| Scenario | Why lumping is correct |
|----------|----------------------|
| **Atomic dependency** | B literally won't compile without A (entity + repo + service + controller for the same feature) |
| **Converter pair** | `@ReadingConverter` + `@WritingConverter` for the same domain type — fixing one without the other breaks the read or write path |
| **Migration + entity** | The Flyway migration and the entity class mapping to it — the entity has no table without the migration |

Everything else gets its own commit.

## Anti-Patterns

| Anti-pattern | Why it's bad | Fix |
|-------------|-------------|-----|
| One giant commit | Impossible to review, revert, or bisect | Cluster by feature group |
| Backend + frontend in one commit | Different reviewers, different build concerns | Split by scope (`stream` / `ui`) |
| Vague description (`fix: bugs`) | Zero traceability | Name the specific bug or change |
| Formatting + logic together | Whitespace noise hides the real diff | Format first as `style:`, then commit logic |
| Staging single files without checking the group | Leaves orphans from the same feature | Always `git status` after each commit |
| Skipping build verification | Bad early commit breaks later ones | At minimum `compileJava` / `ng build` between commits |

## Post-Commit Flow

| Step | When | See |
|------|------|-----|
| Code review | After grouping, before pushing — review each commit's diff | `code-reviewer` agent |
| Push | After all commits + review pass | [[git-workflow]] §"Pull Request Workflow" |
| Retro | Compare actual commits against planned execution order | [[session-retro]] |

## References

- [[git-workflow]] — Commit message format, merge/rebase, conflict resolution, branch management, git configuration, common workflows
- [[session-retro]] — End-of-session reconciliation; uses commit history as ground truth
- `.claude/rules/common/git-workflow.md` — Project git rules (conventional commits, PR workflow)
