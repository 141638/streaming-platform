# Service structure: architectural styles & data strategy

This note answers two implementation questions for the control plane:

1. Which **architectural style** fits each service.
2. How **PostgreSQL schemas** relate to **Redis** for chat (hot cache vs. durable store).

It complements `docs/ARCHITECTURE.md`.

---

## 1. Recommended style per service

These are **pragmatic defaults** for Spring Boot — not dogma. You can blend styles (e.g. layered + hexagonal ports) inside one codebase.

| Service | Suggested primary style | Why |
|--------|-------------------------|-----|
| **gateway-service** | **Configuration / pipeline** | Mostly declarative routes, filters, CORS — almost no domain model. Keep it thin. |
| **discovery-service** | **Infrastructure adapter** | Eureka server is framework glue; no business domain. |
| **auth-service** | **Layered MVC** + **tactical DDD** where complexity grows | Classic **Web → application services → domain** works well with Spring Security. Introduce **aggregates** (e.g. `UserAccount`) and repositories when you add real persistence. **Hexagonal** is optional: define ports (`TokenIssuer`, `CredentialVerifier`) if you expect multiple IdPs or OIDC. |
| **stream-service** | **Layered reactive** + **ports & adapters (hexagonal)** | Core use cases: create/validate session, emit events. **Adapters**: R2DBC (persistence), Kafka (outbound events), HTTP (SRS webhooks). Keeps SRS/Kafka/test doubles swappable. |
| **chat-service** | **Layered reactive** + **application services with cache-aside** | High read/write rate: orchestration layer decides **Redis vs Postgres** (see §2). Optional **DDD** for `ChatRoom` / `Message` if rules get richer. |
| **notification-service** | **Layered consumer** + **adapters** | Kafka listener → domain/service → optional JDBC/R2DBC outbox + external gateways (email/push). |

**Notes.**

- **“Pure DDD”** everywhere is often overkill for small teams; use **DDD tactical patterns** (aggregates, boundaries) where churn and invariants concentrate (stream session lifecycle, billing later).
- **Onion/hexagonal** overlap — both push **domain in the middle, IO at the edge**. Use explicit **ports** when you have more than one implementation (e.g. mock SRS vs real webhook).
- **MVC** applies to **auth** ( servlet ); reactive services use the **same layering idea** with WebFlux instead of “MVC” in the Spring servlet sense.

---

## 2. Postgres schemas vs Redis for chat

**Intent (your design):**

- **PostgreSQL schema `chat`**: system of record for **rooms** and **messages** (audit, replay, moderation, compliance, slow queries).
- **Redis**: **latency buffer** for bursts: recent messages per room, optional pub/sub fan-out to WebSocket workers, rate limits, ephemeral presence.

**Recommended pattern: cache-aside + write-through (variant).**

1. **Send message (write path):**  
   Persist to Postgres first (transactional). Then update Redis (recent list, TTL window, pub/sub). If Redis fails after commit, compensate or retry async (outbox or background job) — never silently drop the durable write.

2. **Read recent messages (read path):**  
   Try Redis first for “hot window” (last *N* messages). On miss, load from Postgres, backfill cache.

3. **Listing historical pages:**  
   Query Postgres (indexed by `room_id`, `created_at`), optionally skip Redis.

This matches **high request volume + fast response** without making Redis the only source of truth.

**Operational caution:** Redis and Postgres can get **eventually consistent** if you’re not strict about ordering; for strict ordering per room, either serialize writes through one path or use a **single stream key** (Redis Streams / Kafka) then materialize to PG — evolve when you need stronger guarantees.

---

## 3. Flyway layout (SQL migrations)

Each owning service ships versioned scripts under `src/main/resources/db/migration/` (default Flyway location):

- `V1__bootstrap_*_schema.sql` — `CREATE SCHEMA` + baseline tables for **`auth`**, **`stream`**, **`chat`**, or **`notification`**.
- **gateway** and **discovery** have no Flyway.

**Runtime:** **No** `spring.datasource` / `spring-boot-starter-jdbc` on reactive services. **Flyway** uses its own blocking JDBC connection configured through **`spring.flyway.url`** / **`user`** / **`password`** (plus **`spring.flyway.schemas`** so each service’s schema holds its own **`flyway_schema_history`**). Reactive code continues to use **R2DBC** with `search_path` on the `r2dbc:postgresql` URL. The plain **PostgreSQL JDBC driver** is still a dependency so Flyway can open that connection; it is **not** wired as the application’s primary `DataSource` bean.

**Schemas:** `auth`, `stream`, `chat`, `notification` on the same database (`POSTGRES_DB=streaming` in local Compose). Cross-schema **foreign keys are avoided** across services; within `chat`, room↔message FKs are allowed.

---

## 4. When to split databases

Stay on **one Postgres cluster + multiple schemas** until you need independent **backup/restore**, **noisy-neighbor isolation**, or **compliance partitioning**. Migration scripts remain per service (`db/migration`), so you can lift a schema to its own instance by changing Flyway/R2DBC URLs and roles.
