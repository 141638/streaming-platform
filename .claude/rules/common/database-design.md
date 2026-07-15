# Database Schema Design

> This file extends [common/patterns.md](../common/patterns.md) with database-specific design rules.

## Two-Tier Type Preference (STEEL RULE)

Every column type choice starts at Tier 1. Only escalate to Tier 2 when Tier 1 cannot satisfy the query or constraint requirements — and when you do, you MUST verify framework compatibility first.

### Tier 1 — Always prefer these

`TEXT` / `VARCHAR`, `BOOLEAN`, `BIGINT` / `INTEGER`, `UUID`, `TIMESTAMPTZ`, `NUMERIC`

These are universally supported by every framework, driver, and ORM. They never cause wire-type mismatches, converter gaps, or serialization surprises.

### Tier 2 — Require explicit justification and converter verification

`JSONB`, `TEXT[]` / `INT[]` (Postgres arrays), `BYTEA`, custom ENUM types, `TSVECTOR`, composite types

**Decision rule:** Start with Tier 1. Only escalate when:
- The data is genuinely semi-structured (variable keys, nested objects) → `JSONB`
- You need GIN-indexed array containment queries (`@>`, `&&`) → `TEXT[]`
- Binary data that must be stored with the row (not in object storage) → `BYTEA`

## Framework Compatibility Verification (MANDATORY for Tier 2)

Before committing a DDL migration that uses a Tier 2 type, answer these four questions:

1. **Does the framework/driver support this type natively?**
   - This project uses **Spring Data R2DBC** with the **PostgreSQL reactive dialect** — NOT Hibernate/JPA.
   - R2DBC has **no automatic JSONB ↔ POJO mapping** and limited array type support.

2. **If not natively supported, has a custom converter been created?**

3. **Is the converter registered in `R2dbcConfig`** (or equivalent `AbstractR2dbcConfiguration` subclass)?

4. **For JSONB specifically: does the converter use the correct wire type?**
   - `io.r2dbc.postgresql.codec.Json` → correct `jsonb` wire type
   - `String` → **WRONG** — sends `character varying`, causes:
     ```
     ERROR: column "<name>" is of type jsonb but expression is of type character varying
     ```

### Converter organization

Custom type converters MUST live in a dedicated subfolder:

```
src/main/java/com/streaming/<service>/config/
├── R2dbcConfig.java              ← registers all converters
├── converter/                     ← ALL custom converters grouped here
│   ├── SocialLinksReadingConverter.java
│   ├── SocialLinksWritingConverter.java
│   └── ...
```

Anti-pattern: converters scattered across `config/`, `persistence/`, or `service/` packages.

### Entity field alternatives to converters

If the JSON content is simple and doesn't need PostgreSQL-level JSON queries, you have two options that avoid converter complexity entirely:

| Approach | DB column | Entity field | When |
|----------|-----------|-------------|------|
| **Use TEXT** | `metadata TEXT` | `String metadata` | No PG JSON queries needed; simplest |
| **Use converters** | `metadata JSONB` | `JsonNode metadata` (or domain type) | Need PG JSON operators (`->`, `->>`, `@>`) |

**Do NOT use `JSONB` column + `String` entity field without a converter.** This is the #1 cause of the `jsonb vs character varying` error at persist time.

## R2DBC Type Compatibility Reference

| PG Type | Supported Java types (native) | Requires custom converter? |
|---------|------------------------------|---------------------------|
| `TEXT` / `VARCHAR` | `String` | No |
| `BOOLEAN` | `boolean`, `Boolean` | No |
| `BIGINT` / `INTEGER` | `long`, `Long`, `int`, `Integer` | No |
| `UUID` | `java.util.UUID` | No |
| `TIMESTAMPTZ` | `java.time.OffsetDateTime`, `Instant` | No |
| `JSONB` | `io.r2dbc.postgresql.codec.Json` | **Yes** — converter pair required |
| `TEXT[]` | `String[]` | No (but NOT `Set<String>` or `List<String>`) |
| `INT[]` | `Integer[]`, `int[]` | No (but NOT `List<Integer>`) |
| Custom ENUM | `String` (via `@ReadingConverter`/`@WritingConverter`) | **Yes** — enum converter pair required |

## Schema Design Checklist

Before merging any DDL migration:

- [ ] All columns use Tier 1 types unless Tier 2 is explicitly justified in the migration comment
- [ ] Every Tier 2 column has a corresponding R2DBC converter pair verified
- [ ] Converters are grouped in `config/converter/` subfolder
- [ ] `@WritingConverter` returns `io.r2dbc.postgresql.codec.Json` (not `String`) for JSONB columns
- [ ] `@ReadingConverter` accepts `io.r2dbc.postgresql.codec.Json` (not `String`) for JSONB columns
- [ ] Converters are registered in `R2dbcConfig.getCustomConverters()` or `R2dbcCustomConversions` bean
- [ ] Entity field type matches what the converter produces (not `String` for a JSONB column unless TEXT is used)
- [ ] Foreign keys have indexes
- [ ] `timestamp` columns use `TIMESTAMPTZ` (not `TIMESTAMP` without timezone)
- [ ] IDs use `UUID` or `BIGINT` (not `INT`)

## References

- [R2DBC JSONB Converter Pattern](../../docs/R2DBC-JSONB-CONVERTER-PATTERN.md) — complete pattern with code templates and pitfalls
- [database-reviewer agent](../../.claude/agents/database-reviewer.md) — automated schema review checklist
- [database-migrations skill](../../.claude/skills/database-migrations/SKILL.md) — migration safety and workflow
