---
name: java-backend-workflow
description: Implementation workflow for Java Spring Boot backend features — three-phase process (orient, implement, verify), build commands, common anti-patterns, and quality checklist. Load as the final skill before starting Java backend implementation.
origin: project
---

# Java Backend Implementation Workflow

Three-phase process for implementing Java Spring Boot backend features in this project. Load this skill after `java-backend-architecture` and `r2dbc-patterns` (if doing DB work).

## Phase 1: Orient

Before writing any code:

1. **Identify the target service** — which service directory are you working in?
2. **Read the service ADR-0000** — `docs/adr/<service>/0000-architecture-foundation.md`. This is mandatory per project rules. It documents the architectural style, package structure, entity patterns, and third-party integrations for that specific service.
3. **Load skills** — `java-coding-standards`, `springboot-patterns`, `java-backend-architecture`, and `r2dbc-patterns` (if DB work). Add `springboot-security` if auth is involved.
4. **Read project rules** — at minimum: `rules/java/coding-style.md`, `rules/java/patterns.md`, `rules/common/database-design.md` (if DB work).
5. **Survey existing patterns** — read similar controllers/services/repositories in the service to mirror naming, structure, and error-handling conventions.

## Phase 2: Implement

Follow the layered architecture from `java-backend-architecture`:

1. **Controller/Handler** — thin. Validate input (`@Valid`), delegate to service, return response DTO via `ApiResponse<T>` envelope. No business logic.
2. **Service** — business logic and orchestration. Constructor injection. `@Transactional` on mutating methods. Map between entities and DTOs.
3. **Repository** — R2DBC interface. Returns `Mono<T>`/`Flux<T>`. Use `@Query` for custom queries with `:` parameters.
4. **Domain** — entities, value objects (records), domain exceptions (`extends RuntimeException`).
5. **DTO** — records for request/response boundaries. Static factory `from()` methods.

Conventions to apply (not guess):
- Constructor injection only — never `@Autowired` on fields
- Immutable domain objects — records + final fields, no setters
- `Optional` for finder returns, `orElseThrow()` — never `.get()`
- Unchecked domain exceptions, centralized `@RestControllerAdvice`
- `ApiResponse<T>` envelope for all endpoints
- Structured key=value logging: `log.info("key={}", value)`

Edge cases to handle:
- Null/missing input → `@Valid` + `@NotNull`/`@NotBlank` on DTOs
- Not found → `orElseThrow(() -> new XxxNotFoundException(id))`
- Validation failure → `MethodArgumentNotValidException` → 400
- Error paths → catch, log, rethrow or return error response

## Phase 3: Verify (MANDATORY)

Run verification after every implementation session:

### Maven (most services)

```bash
./mvnw compile -q          # Step 1: Compile — fix all errors
./mvnw verify -q           # Step 2: Full — compile + test + static analysis
./mvnw test -q             # Step 3: Tests only, for quick feedback
```

### Gradle (if applicable)

```bash
./gradlew compileJava
./gradlew check
./gradlew test
```

### Framework detection

```bash
grep -E "spring-boot|quarkus" pom.xml build.gradle 2>/dev/null
```

If `./mvnw verify` fails, analyze errors and fix before proceeding. Do not skip verification.

## Anti-Patterns

These are project-specific gotchas. See `java-coding-standards` and `springboot-patterns` for general Java/Spring anti-patterns.

### R2DBC-specific

- **JPA annotations on R2DBC entities** → use `@Table` (Spring Data R2DBC) with `@Id` from `org.springframework.data.annotation`, never `@Entity`, `@OneToMany`, or `@GeneratedValue`
- **`JSONB` column + `String` entity field without converter** → causes `character varying` error at persist time. Always create `@ReadingConverter` + `@WritingConverter` pair registered in `R2dbcConfig`
- **`Mono<Optional<T>>` return types** → R2DBC repositories already return `Mono<T>` which can complete empty. Don't nest Optional
- **Missing converter registration** → converter class exists but not added to `R2dbcConfig.getCustomConverters()`. Always register

### Reactive-specific (WebFlux)

- **Blocking calls in reactive pipelines** → never call blocking JDBC, HTTP, or file I/O directly inside `Mono`/`Flux` chains. Use `Mono.fromCallable()` + `Schedulers.boundedElastic()` to offload
- **Servlet `SecurityFilterChain` in WebFlux services** → stream/chat/notification must use `SecurityWebFilterChain` + `ServerHttpSecurity`. Only auth-service (MVC) uses the traditional `SecurityFilterChain` shown in `springboot-security`
- **`@SpringBootTest` for unit tests in WebFlux services** → use `@WebFluxTest` for controller slices or plain JUnit 5 + Mockito for service tests. Reserve `@SpringBootTest` for full integration tests

## Service-Specific Testing

The external skill `java-coding-standards` shows `@WebMvcTest` + `@DataJpaTest` + `@MockBean`. This project has both MVC and WebFlux services — use the right annotations per service:

| Service | Controller test | Repository test | Unit test |
|---------|----------------|----------------|-----------|
| **auth-service** (MVC) | `@WebMvcTest` + `@MockBean` | `@DataJpaTest` | JUnit 5 + Mockito |
| **stream-service** (WebFlux) | `@WebFluxTest` + `@MockBean` | Plain JUnit 5 + Mockito (no DB slice) | JUnit 5 + Mockito |
| **chat-service** (WebFlux) | `@WebFluxTest` + `@MockBean` | Plain JUnit 5 + Mockito | JUnit 5 + Mockito |
| **notification-service** (consumer) | N/A (no controllers) | N/A | JUnit 5 + Mockito |
| **gateway-service** (pipeline) | `@SpringBootTest` (integration only) | N/A | JUnit 5 |

**Key rule**: R2DBC repositories don't have `@DataJpaTest` equivalent. Test WebFlux repositories with plain Mockito mocks in unit tests, or use Testcontainers for integration tests.

## Quality Checklist

Before marking work complete:

- [ ] Service ADR-0000 read before modifying service code
- [ ] Skills loaded: `java-coding-standards`, `springboot-patterns`, `java-backend-architecture`
- [ ] `r2dbc-patterns` loaded if DB work involved
- [ ] Project rules read fresh (not guessed):
  - [ ] `java/coding-style.md`: records, constructor injection, Optional, streams, error handling
  - [ ] `java/patterns.md`: layered architecture, DTO mapping, API envelope
  - [ ] `common/database-design.md`: Tier 1 types preferred, converters registered (if DB work)
- [ ] `./mvnw verify` (or `./gradlew check`) passes with zero errors
- [ ] Controllers thin — business logic in services
- [ ] `@Transactional` on service layer, not controller or repository
- [ ] Entities never exposed in API responses — DTOs at boundaries
- [ ] `@Valid` on all request bodies
- [ ] Error paths handled — no swallowed exceptions
- [ ] No hardcoded secrets or magic numbers
- [ ] Structured logging (`log.info("key={}", value)`)
- [ ] Immutability preserved — records + final fields, no setters
- [ ] Existing tests still pass

## References

- `skill: java-backend-architecture` — project architecture patterns
- `skill: r2dbc-patterns` — entity, converter, and migration patterns
- `skill: springboot-verification` — full verification pipeline for pre-PR checks
- `rules/java/*.md` — project Java conventions
- `docs/SERVICE-ARCHITECTURE.md` — per-service architectural rationale
