---
name: r2dbc-patterns
description: R2DBC patterns for this Spring Boot WebFlux project — entity design, type tiers, JSONB converters, reactive repositories, Flyway migrations, and common pitfalls. Load alongside java-backend-architecture when doing database work.
origin: project
---

# R2DBC Patterns

This project uses Spring Data R2DBC with PostgreSQL — NOT JPA/Hibernate. External skills (`springboot-patterns`, `java-coding-standards`) may show JPA examples; this skill provides the R2DBC equivalents and project-specific converter/migration patterns.

Load this skill whenever creating or modifying entities, repositories, converters, or Flyway migrations.

## Type Tier Decision Flow

The project rule `rules/common/database-design.md` defines two tiers. Follow this flow for every column:

```
1. Does Tier 1 (TEXT, BOOLEAN, BIGINT, UUID, TIMESTAMPTZ, NUMERIC) work?
   → YES: Use Tier 1. Done.
   → NO:  Proceed to step 2.

2. Does the column need PostgreSQL-specific queries (JSON operators, array containment, GIN indexes)?
   → YES: Use Tier 2 type + create R2DBC converter pair. Verify framework compatibility.
   → NO:  Reconsider — Tier 1 is probably fine.
```

## R2DBC Type Compatibility

| PG Type | R2DBC Java type | Converter needed? |
|---------|----------------|-------------------|
| `TEXT` / `VARCHAR` | `String` | No |
| `BOOLEAN` | `boolean`, `Boolean` | No |
| `BIGINT` / `INTEGER` | `long`, `Long`, `int`, `Integer` | No |
| `UUID` | `java.util.UUID` | No |
| `TIMESTAMPTZ` | `java.time.Instant`, `OffsetDateTime` | No |
| `JSONB` | `io.r2dbc.postgresql.codec.Json` | **Yes** — converter pair |
| `TEXT[]` | `String[]` | No (but NOT `List<String>`) |
| `INT[]` | `Integer[]`, `int[]` | No (but NOT `List<Integer>`) |

## JSONB Converter Pattern (MANDATORY)

Every `JSONB` column requires a `@ReadingConverter` + `@WritingConverter` pair. The `@WritingConverter` MUST return `io.r2dbc.postgresql.codec.Json` — **never `String`**. Returning `String` sends the `character varying` wire type and causes:

```
ERROR: column "<name>" is of type jsonb but expression is of type character varying
```

### ReadingConverter

```java
@ReadingConverter
public class YourTypeReadingConverter implements Converter<Json, YourType> {
    // Must accept io.r2dbc.postgresql.codec.Json (NOT String)
    @Override
    public YourType convert(Json source) {
        // Parse and return your domain type
    }
}
```

### WritingConverter

```java
@WritingConverter
public class YourTypeWritingConverter implements Converter<YourType, Json> {
    // Must return io.r2dbc.postgresql.codec.Json (NOT String)
    @Override
    public Json convert(YourType source) {
        return Json.of(serializeToString(source));
    }
}
```

### Converter Organization

All converters live in `config/converter/` — never scattered:

```
src/main/java/com/streaming/<service>/config/
  R2dbcConfig.java              ← registers all converters
  converter/
    SocialLinksReadingConverter.java
    SocialLinksWritingConverter.java
    ...
```

### Registration in R2dbcConfig

```java
@Configuration
public class R2dbcConfig extends AbstractR2dbcConfiguration {
    @Override
    protected List<Object> getCustomConverters() {
        return List.of(
            new SocialLinksReadingConverter(),
            new SocialLinksWritingConverter()
        );
    }
}
```

### Entity field alternatives

If the JSON content is simple and doesn't need PostgreSQL JSON queries:

| Approach | DB column | Entity field | When |
|----------|-----------|-------------|------|
| Use TEXT | `metadata TEXT` | `String metadata` | No PG JSON queries needed; simplest |
| Use converters | `metadata JSONB` | `JsonNode metadata` (or domain type) | Need PG JSON operators (`->`, `->>`, `@>`) |

**Never use `JSONB` column + `String` entity field without converters.** This is the #1 cause of R2DBC type errors.

## Entity Patterns

R2DBC entities are plain Java classes — NOT `@Entity` (Jakarta/JPA):

```java
@Table("markets")  // Spring Data R2DBC annotation
public class Market {
    @Id
    private final Long id;
    private final String name;
    private final MarketStatus status;  // enum — needs converter if stored as custom ENUM

    // Constructor, getters — no setters
}
```

Key differences from JPA:
- `@Table` from `org.springframework.data.relational.core.mapping.Table` (not `jakarta.persistence.Table`)
- `@Id` from `org.springframework.data.annotation.Id` (not `jakarta.persistence.Id`)
- No `@GeneratedValue` — assign IDs manually or use DB defaults
- No `@OneToMany`, `@ManyToOne` — R2DBC does not support entity relationships
- No lazy loading, no dirty checking — explicit save/update

## Reactive Repository Pattern

```java
public interface MarketRepository extends ReactiveCrudRepository<Market, Long> {
    Mono<Market> findBySlug(String slug);
    Flux<Market> findByStatus(MarketStatus status);
    
    @Query("SELECT * FROM markets WHERE status = :status ORDER BY created_at DESC LIMIT :limit")
    Flux<Market> findTopByStatus(MarketStatus status, int limit);
}
```

- Return types: `Mono<T>` (0-1), `Flux<T>` (0-N)
- `Mono<Optional<T>>` is wrong — use `Mono<T>` which can be empty
- For custom queries, use `@Query` with `:` named parameters

## WebFlux Reactive Patterns

The external skill `java-coding-standards` covers Quarkus reactive (`Uni`/`Multi`). This project uses **Spring WebFlux** (`Mono`/`Flux`), which is functionally equivalent but with different APIs.

### Mono vs Uni

```java
// Quarkus (from java-coding-standards) — DO NOT USE in this project
public Uni<Market> findBySlug(String slug) { ... }

// Spring WebFlux — USE THIS in this project
public Mono<Market> findBySlug(String slug) { ... }
```

### Pipeline composition

```java
public Mono<OrderConfirmation> placeOrder(OrderRequest req) {
  return validateOrder(req)        // Mono<ValidatedOrder>
      .flatMap(this::persistOrder)  // Mono<Order>
      .flatMap(this::notifyFulfillment); // Mono<OrderConfirmation>
}
```

- `map()` — transform synchronously: `Mono<T>` → `Mono<R>`
- `flatMap()` — transform asynchronously: `Mono<T>` → `Mono<R>` (returns a reactive type)
- `filter()` — conditionally pass: `Mono<T>` → `Mono<T>` (empty if predicate fails)

### Error handling

```java
public Mono<Market> findBySlug(String slug) {
  return marketRepository.findBySlug(slug)
      .switchIfEmpty(Mono.error(new MarketNotFoundException(slug)));
}
```

- `onErrorResume(Ex.class, e -> fallback)` — recover with a fallback value
- `onErrorMap(Ex.class, e -> new AppEx(e))` — wrap/translate exceptions
- `switchIfEmpty(Mono.error(...))` — convert empty to error

### Avoiding blocking

```java
// FAIL: Blocking JDBC call inside reactive pipeline
public Mono<Report> generate(Long id) {
  return repository.findById(id)
      .map(entity -> {
        Report r = jdbcClient.query(...); // BLOCKING — freezes event loop
        return r;
      });
}

// PASS: Offload blocking work to boundedElastic
public Mono<Report> generate(Long id) {
  return repository.findById(id)
      .flatMap(entity -> Mono.fromCallable(() -> jdbcClient.query(...))
          .subscribeOn(Schedulers.boundedElastic()));
}
```

### Subscribing

Never call `.subscribe()` or `.block()` in service/controller code — return the `Mono`/`Flux` and let WebFlux handle the subscription. The only time `.block()` is acceptable is in tests or integration glue code that bridges to a blocking API.

```java
// PASS: Return the reactive type — framework subscribes
public Mono<Market> getMarket(Long id) {
  return marketRepository.findById(id);
}

// FAIL: Blocking in application code
public Market getMarket(Long id) {
  return marketRepository.findById(id).block(); // blocks thread
}
```

### Service layer with reactive composition

```java
@Service
public class MarketService {
  private final MarketRepository marketRepository;
  private final MarketEventPublisher eventPublisher;

  public MarketService(MarketRepository marketRepository, MarketEventPublisher eventPublisher) {
    this.marketRepository = marketRepository;
    this.eventPublisher = eventPublisher;
  }

  @Transactional
  public Mono<MarketResponse> create(CreateMarketRequest request) {
    Market entity = Market.from(request);
    return marketRepository.save(entity)
        .flatMap(saved -> eventPublisher.publish(new MarketCreated(saved)))
        .thenReturn(entity)
        .map(MarketResponse::from);
  }
}
```

## Flyway Migration Conventions

Migrations live in `src/main/resources/db/migration/`:

```
V1__create_markets_table.sql
V2__add_status_column.sql
V3__create_indexes.sql
```

Rules:
- Versioned migrations: `V<number>__<description>.sql` (double underscore)
- Never edit a migration that has run in production — create a new one
- Schema (DDL) and data (DML) in separate migrations
- New columns must be nullable or have defaults — never `NOT NULL` without `DEFAULT`
- Create indexes with `CONCURRENTLY` on large tables (outside migration framework transactions)
- Every Tier 2 column must have a verified converter pair before the migration is committed

## Common Pitfalls

1. **JSONB + String field** → silent `character varying` error. Fix: add converter pair.
2. **Entity relationships** → R2DBC has no `@OneToMany`. Join manually in the service layer or use a custom query.
3. **Missing converter registration** → converters exist but not in `R2dbcConfig.getCustomConverters()`. Always register.
4. **JPA annotations on entities** → `@Entity`, `@GeneratedValue`, `@OneToMany` are ignored by R2DBC. Use R2DBC annotations.
5. **Blocking on reactive thread** → `Mono.fromCallable(() -> jdbcCall())` wraps blocking calls but R2DBC is non-blocking natively.
6. **`Mono<Optional<T>>`** → R2DBC repositories already return `Mono<T>` which can complete empty. Don't nest Optional.

## References

- `rules/common/database-design.md` — full type tier rules and converter verification checklist
- `docs/R2DBC-JSONB-CONVERTER-PATTERN.md` — complete pattern with code templates and pitfalls
- `skill: database-migrations` — general migration safety and zero-downtime patterns
