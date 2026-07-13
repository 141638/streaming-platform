# Streaming Platform — Agent Instructions

Optimized agent harness for the **Live Streaming Platform** POC. Tailored for Java 21 / Spring Boot / Angular / PostgreSQL / Redis / Kafka stack with a flexible, partner-oriented workflow.

**Base:** ECC v2.0.0 (streamlined)

## Core Principles

1. **Partner, Not Autopilot** — Agents suggest and assist; you decide. No auto-triggered pipelines.
2. **Flexible Process** — Full TDD pipeline available, but quick single-task mode is the default.
3. **Pattern-First** — Follow existing project conventions before inventing new ones.
4. **Security-Aware** — Validate inputs, protect secrets, review auth changes.
5. **Immutability** — Create new objects, never mutate existing ones.

## Available Agents

| Agent | Purpose | Model | When to Use |
|-------|---------|-------|-------------|
| planner | Implementation planning | opus | Complex features, refactoring — via `/blueprint` or `/implement --mode=full` |
| architect | System design & scalability | opus | Architectural decisions, service boundaries, used when user ask about architecture decision in `/consul` or `/blueprint` or full flow in `/implement --mode=full` |
| doc-analyzer | Japanese BD processing | opus | Processing xlsx/md basic designs, generating detail designs. For complex document, consult with `/consult` or `/architecture` |
| code-reviewer | Code quality & maintainability | sonnet | Via `/review` |
| security-reviewer | Vulnerability detection | sonnet | Auth/input/DB changes |
| tdd-guide | Test-driven development | sonnet | Via `/implement --tdd` |
| build-error-resolver | Fix build/type errors | sonnet | Via `/build-fix` |
| code-explorer | Codebase understanding | sonnet | Before modifying unfamiliar code |
| java-reviewer | Java/Spring Boot review | sonnet | Backend code review |
| java-build-resolver | Gradle build errors | sonnet | Java build failures |
| typescript-reviewer | TypeScript/Angular review | sonnet | Frontend code review |
| database-reviewer | PostgreSQL/schema specialist | sonnet | Schema design, query optimization |
| refactor-cleaner | Dead code cleanup | sonnet | Code maintenance |
| doc-updater | Documentation | haiku | Updating docs |
| e2e-runner | Playwright E2E testing | sonnet | Critical user flows |
| silent-failure-hunter | Reactive stream debugging | sonnet | Swallowed exceptions, reactive pipeline issues |
| performance-optimizer | Performance tuning | sonnet | Query optimization, bundle size, latency |

## Commands

| Command | Purpose |
|---------|---------|
| `/blueprint` | Sketch plan and implementation if approved — wait for confirm |
| `/implement` | Flexible implementation (full/quick/step modes) |
| `/review` | Code review (local or PR) |
| `/perform` | Quick generation, no ceremony |
| `/consult` | Debug partner, technical advisor, architecture |
| `/build-fix` | Fix build errors |
| `/docs` | Look up library/API documentation |
| `/learn` | Extract reusable patterns from session |
| `/learn-eval` | Extract patterns with quality gate |
| `/skill-create` | Generate skills from git history |

## When to Use Which Agent

Not automatic — agents are invoked **on your explicit request** or via commands:

- Complex feature → `/blueprint` first, then `/implement --mode=full` if the plan is approved
- Single task → `/implement` (auto-detects quick mode)
- Generate/prototype → `/perform`
- Debug/decide/architecture → `/consult`
- After writing code → `/review`
- Build broken → `/build-fix`
- Japanese BD received → `doc-analyzer` agent, then `/plan`

## Project Architecture

- **Backend**: Java 21 / Spring Boot / Gradle — layered reactive (WebFlux + R2DBC) with hexagonal ports
- **Frontend**: Angular with PrimeNG — atomic design methodology
- **Infrastructure**: PostgreSQL, Redis, Kafka, SRS (media server) — Docker Compose for local dev
- **Docs**: `docs/ARCHITECTURE.md`, `docs/SERVICE-ARCHITECTURE.md`, `docs/REDIS-KAFKA-PRODUCTION-GAP.md`

## Coding Style

**Immutability (CRITICAL):** Always create new objects, never mutate. Return new copies.

**File organization:** Many small files over few large ones. 200-400 lines typical, 800 max. Organize by feature/domain.

**Error handling:** Handle errors at every level. User-friendly messages in UI. Detailed context server-side. Never silently swallow errors.

**Input validation:** Validate all user input at system boundaries. Fail fast with clear messages.

## Security Guidelines

- No hardcoded secrets (use environment variables)
- Parameterized queries (R2DBC `DatabaseClient` with bind parameters)
- Input validation at API boundaries (Bean Validation)
- JWT/PBAC for authorization (see `docs/PBAC-AUTHORIZATION.md`)
- CORS configured at gateway edge
- Error messages must not leak internal details

## Testing

- Unit tests for all public methods
- Integration tests for API endpoints (`@WebFluxTest`, `@DataR2dbcTest`)
- E2E tests for critical user flows (Playwright)
- TDD available via `/implement --tdd` — not mandatory by default

## Git Workflow

**Commit format:** `<type>: <description>` — Types: feat, fix, refactor, docs, test, chore, perf, ci

## Agent Format

- Agents live in `agents/*.md`
- YAML frontmatter with `name`, `description`, `tools`, `model`
- File names lowercase with hyphens, match agent name

## Shared Libraries

### `common` — Zero-dependency shared utilities

Located at `main/source/backend/common/`. Plain Java 21, **no framework dependencies** (no Spring, no third-party libs). Usable from any service without pulling a transitive dependency tree.

**AVAILABLE NOW — check here before reimplementing:**

| Class | Location | Purpose |
|-------|----------|---------|
| `HashUtils` | `com.streaming.common.crypto` | `sha256(String)` → `byte[]`, `sha256Hex(String)` → hex `String` |
| `ApiMessage` | `com.streaming.common.api` | `record(String service, String status)` — ping/health response |

**CONSTRAINT:** `common` must stay framework-free. Do NOT add Spring annotations, `@ConfigurationProperties`, `ServerHttpSecurity`, or any dependency that ties it to Spring Boot. Things that need Spring (PBAC types, JWT config, security filters) go in the future `pbac-common` library instead — those are separate concerns.

**DEPENDENCY SETUP:** To use `common` in a service, add to its `build.gradle.kts`:
```kotlin
implementation(project(":common"))
```
Current consumers: `auth-service`, `stream-service`, `chat-service`, `notification-service`.

### `pbac-common` — Authorization library (planned Phase 6.2)

Do NOT duplicate these — they'll be extracted from current duplicates into `pbac-common`:

| Candidates (marked `PBAC-COMMON-CANDIDATE`) | Currently duplicated in |
|----------------------------------------------|------------------------|
| `AuthAction`, `AuthResourceDomain`, `AuthResourceKind` | auth-service, stream-service |
| `EntitlementMatcher`, `RequiredAuthority` | stream-service |
| `JwtProperties` | stream, chat, notification (3 copies) |
| `AuthenticationEntryPoint` | gateway, stream, chat, notification (4 copies) |
| `SecurityConfig` jwtDecoder bean | stream, chat, notification (3 copies) |
| `AuthorizationResource`, `EntitlementStatements` | auth-service |

When adding PBAC logic to a new service, **copy from stream-service's `security/` package** as a temporary measure until `pbac-common` extraction. Never create a third divergent copy.

## Project Structure

```
agents/          — 17 specialized agents
skills/          — 24 workflow skills
commands/        — 10 slash commands
contexts/        — 6 behavior modes
config/          — Project stack mappings
rules/           — Per-language guidelines (angular, java, typescript, web, common)
docs/            — Architecture and detail designs
main/            — Docker compose, env configs, backend/frontend source
agent-harness-template/ — Full industrial pipeline (orch, multi-model) for complex projects
```

## Heavy Pipeline (When You Need It)

For complex microservice projects where you act as architect rather than coder, the full ECC orchestration pipeline is preserved in `../agent-harness-template/`. See its README for when and how to activate it.

## Success Metrics

- All tests pass
- No security vulnerabilities
- Code follows project patterns
- User requirements met
- Flexible process — not forced ceremony
