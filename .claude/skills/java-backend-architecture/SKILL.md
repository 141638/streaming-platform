---
name: java-backend-architecture
description: Project-specific Java backend architecture — layered-reactive style, hexagonal ports, R2DBC data access, service structure, package conventions, and ADR-driven development for the streaming-platform Spring Boot WebFlux services.
origin: project
---

# Java Backend Architecture

This project's backend architecture: Spring Boot WebFlux with layered-reactive services, hexagonal ports where multiple implementations exist, R2DBC for PostgreSQL, Redis for caching, and Kafka for events.

Load this skill alongside `skill: java-coding-standards` and `skill: springboot-patterns`. Those skills teach general Java and Spring Boot; this skill teaches how THIS project uses them.

## When to Activate

- Modifying any backend service (`stream-service/`, `chat-service/`, `auth-service/`, etc.)
- Creating new service endpoints, domain services, or repository implementations
- Deciding architectural patterns (layered vs hexagonal, sync vs reactive)
- Setting up dependency injection, configuration, or integration wiring
- Choosing between R2DBC patterns (this project) and JPA patterns (skills may show JPA — ignore them)

## Service Architecture (from SERVICE-ARCHITECTURE.md)

Every service has a designated architectural style. Read the service's ADR-0000 for full details.

| Service | Primary style | Key characteristics |
|---------|--------------|---------------------|
| **gateway-service** | Configuration / pipeline | Declarative routes, filters, CORS — thin, no domain model |
| **discovery-service** | Infrastructure adapter | Eureka server, framework glue |
| **auth-service** | Layered MVC + tactical DDD | Web → application services → domain. Hexagonal ports if multiple IdPs |
| **stream-service** | Layered reactive + hexagonal ports | R2DBC persistence, Kafka outbound events, HTTP webhooks. Ports = swappable adapters |
| **chat-service** | Layered reactive + cache-aside | Redis (hot window) + Postgres (durable). Orchestration layer decides store |
| **notification-service** | Layered consumer + adapters | Kafka listener → domain/service → outbox + external gateways |

**Key rule**: Use hexagonal ports when a dependency has more than one implementation (e.g., mock SRS vs real webhook, test double vs real Kafka). Otherwise, direct injection in the service layer is fine.

## Package Structure

```
src/main/java/com/streaming/<service>/
  config/              # @Configuration, @Bean, R2dbcConfig, converter/
    converter/         # ALL R2DBC @ReadingConverter + @WritingConverter grouped here
  controller/          # @RestController (MVC) or RouterFunction (WebFlux)
  service/             # Business logic, orchestration, @Transactional
  repository/          # R2DBC Repository interfaces (NOT JPA)
  domain/              # Entities, value objects, domain exceptions
  dto/                 # Request/response records
  util/                # Shared utilities
  port/                # (when hexagonal) inbound + outbound port interfaces
  adapter/             # (when hexagonal) inbound + outbound adapter implementations
src/main/resources/
  application.yml      # NOT application.properties unless no choice
  db/migration/        # Flyway migrations
src/test/java/...      # mirrors main
```

## Layered Architecture Rules

1. **Controller/Handler** — thin. Validates input (`@Valid`), delegates to service, maps to response DTO. No business logic.
2. **Service** — business logic and orchestration. `@Transactional` on mutating methods, `@Transactional(readOnly = true)` on reads. Constructor injection only.
3. **Repository** — data access interface. R2DBC `@Query` or Spring Data repository. Returns `Mono<T>`/`Flux<T>`. Never expose entities to controllers.
4. **Domain** — entities as plain Java classes (NOT `@Entity`), value objects as records, domain exceptions extending `RuntimeException`.
5. **DTO** — Java records at boundaries. `Create*Request`, `*Response`, `Update*Request`. Map in the service or via static factory method.

## R2DBC vs JPA (CRITICAL)

The external skills (`springboot-patterns`, `java-coding-standards`) show JPA/Hibernate examples. **This project uses R2DBC, not JPA.** When you see JPA code in an external skill, translate to the R2DBC equivalent. For the full R2DBC reference (entities, repositories, JSONB converters, type tiers, Flyway), load `skill: r2dbc-patterns`.

## Project-Specific Conventions

These conventions extend what `skill: java-coding-standards` and `skill: springboot-patterns` cover:

- **API envelope**: All endpoints wrap responses in `ApiResponse<T>` from `rules/java/patterns.md`.
- **Service-specific DI**: Constructor injection only (per `java-coding-standards`). `@ConfigurationProperties` on records for type-safe config. Use `InjectionToken` only for non-class dependencies.
- **Exception handling**: Domain exceptions extend `RuntimeException`. Centralized via `@RestControllerAdvice` + `@ExceptionHandler`. Never expose stack traces in API responses — log details server-side, return generic messages to clients.
- **Reactive security**: For WebFlux services (stream, chat, notification), use `SecurityWebFilterChain` + `ServerHttpSecurity` (not servlet-based `SecurityFilterChain`). The auth-service (MVC) uses the traditional `SecurityFilterChain` + `OncePerRequestFilter` shown in `springboot-security`.

## References

- `docs/SERVICE-ARCHITECTURE.md` — full architectural rationale per service
- `docs/adr/<service>/0000-architecture-foundation.md` — service-specific architecture ADR
- `rules/java/patterns.md` — project Java patterns
- `rules/common/database-design.md` — R2DBC type tiers and converter rules
- `skill: hexagonal-architecture` — port/adapter patterns when needed
- `skill: r2dbc-patterns` — R2DBC entity, converter, and migration patterns
- `skill: java-backend-workflow` — implementation process and verification
