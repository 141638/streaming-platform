---
name: postgres-patterns
description: PostgreSQL database patterns for query optimization, schema design, indexing, and security. Based on Supabase best practices.
origin: ECC
---

# PostgreSQL Patterns

Quick reference for PostgreSQL best practices. For detailed guidance, use the `database-reviewer` agent.

## When to Activate

- Writing SQL queries or migrations
- Designing database schemas
- Troubleshooting slow queries
- Implementing Row Level Security
- Setting up connection pooling

## Quick Reference

### Index Cheat Sheet

| Query Pattern | Index Type | Example |
|--------------|------------|---------|
| `WHERE col = value` | B-tree (default) | `CREATE INDEX idx ON t (col)` |
| `WHERE col > value` | B-tree | `CREATE INDEX idx ON t (col)` |
| `WHERE a = x AND b > y` | Composite | `CREATE INDEX idx ON t (a, b)` |
| `WHERE jsonb @> '{}'` | GIN | `CREATE INDEX idx ON t USING gin (col)` |
| `WHERE tsv @@ query` | GIN | `CREATE INDEX idx ON t USING gin (col)` |
| Time-series ranges | BRIN | `CREATE INDEX idx ON t USING brin (col)` |

### Data Type Quick Reference

| Use Case | Correct Type | Avoid |
|----------|-------------|-------|
| IDs | `bigint` | `int`, random UUID |
| Strings | `text` | `varchar(255)` |
| Timestamps | `timestamptz` | `timestamp` |
| Money | `numeric(10,2)` | `float` |
| Flags | `boolean` | `varchar`, `int` |

### Type Selection — Two-Tier Preference

Prefer simple types. Complex types cost framework friction, migration complexity, and query opacity.

**Tier 1 — Default choice (primitive, universally supported):**

| Type | PostgreSQL | Java (R2DBC native) |
|------|-----------|---------------------|
| Variable text | `VARCHAR(n)`, `TEXT` | `String` |
| Boolean flag | `BOOLEAN` | `Boolean` |
| Integer / long | `INTEGER`, `BIGINT` | `Integer`, `Long` |
| UUID | `UUID` | `java.util.UUID` |
| Timestamp with TZ | `TIMESTAMPTZ` | `OffsetDateTime`, `Instant` |
| Fixed-precision decimal | `NUMERIC(p,s)` | `BigDecimal` |

**Tier 2 — Use only when tier 1 is genuinely insufficient:**

| Type | PostgreSQL | Java (needs converter) | When justified |
|------|-----------|----------------------|----------------|
| JSON object/array | `JSONB` | Domain type via `Converter<Json, T>` | Semi-structured data: variable keys, nested objects, optional fields that change shape |
| Text array | `TEXT[]` | Domain type via `Converter<String[], T>` | GIN-indexed containment queries (`@>`, `&&`) |
| Binary | `BYTEA` | `byte[]` or `ByteBuffer` | Binary data co-located with row (small files only; large blobs → object storage) |
| Custom ENUM | `CREATE TYPE ... AS ENUM` | `String` + `CHECK` constraint preferred | Rare. Usually a lookup table is better (extensible without DDL) |

**Decision rule:** Start at tier 1. Escalate to tier 2 ONLY when you can name the specific query or constraint that tier 1 cannot satisfy. "It would be cleaner as JSON" is not sufficient justification.

### Verifying Tier 2 Framework Compatibility (Spring Data R2DBC)

This project uses **Spring Data R2DBC** with the **PostgreSQL reactive dialect** — NOT Hibernate/JPA. R2DBC does NOT automatically map JSONB to POJOs.

**For every tier 2 column, verify:**

1. **Is there a Spring Data R2DBC `Converter` registered?** R2DBC requires explicit `@ReadingConverter` and `@WritingConverter` pairs registered in `AbstractR2dbcConfiguration.getCustomConverters()`.
2. **Does the converter use the correct wire type?** For JSONB: the writing converter MUST return `io.r2dbc.postgresql.codec.Json` (via `Json.of(jsonString)`), NOT a plain `String`. A `String` return type causes `"column is of type jsonb but expression is of type character varying"`.
3. **Is the converter in the right package?** All converters must live under `config/converter/` for discoverability:
   ```
   config/
   ├── R2dbcConfig.java
   └── converter/
       ├── SocialLinksReadingConverter.java
       ├── SocialLinksWritingConverter.java
       └── ...
   ```

**Existing converter pattern:** [`docs/R2DBC-JSONB-CONVERTER-PATTERN.md`](../../docs/R2DBC-JSONB-CONVERTER-PATTERN.md) — full template, common pitfalls, and existing implementations.

### Common Patterns

**Composite Index Order:**
```sql
-- Equality columns first, then range columns
CREATE INDEX idx ON orders (status, created_at);
-- Works for: WHERE status = 'pending' AND created_at > '2024-01-01'
```

**Covering Index:**
```sql
CREATE INDEX idx ON users (email) INCLUDE (name, created_at);
-- Avoids table lookup for SELECT email, name, created_at
```

**Partial Index:**
```sql
CREATE INDEX idx ON users (email) WHERE deleted_at IS NULL;
-- Smaller index, only includes active users
```

**RLS Policy (Optimized):**
```sql
CREATE POLICY policy ON orders
  USING ((SELECT auth.uid()) = user_id);  -- Wrap in SELECT!
```

**UPSERT:**
```sql
INSERT INTO settings (user_id, key, value)
VALUES (123, 'theme', 'dark')
ON CONFLICT (user_id, key)
DO UPDATE SET value = EXCLUDED.value;
```

**Cursor Pagination:**
```sql
SELECT * FROM products WHERE id > $last_id ORDER BY id LIMIT 20;
-- O(1) vs OFFSET which is O(n)
```

**Queue Processing:**
```sql
UPDATE jobs SET status = 'processing'
WHERE id = (
  SELECT id FROM jobs WHERE status = 'pending'
  ORDER BY created_at LIMIT 1
  FOR UPDATE SKIP LOCKED
) RETURNING *;
```

### Anti-Pattern Detection

```sql
-- Find unindexed foreign keys
SELECT conrelid::regclass, a.attname
FROM pg_constraint c
JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
WHERE c.contype = 'f'
  AND NOT EXISTS (
    SELECT 1 FROM pg_index i
    WHERE i.indrelid = c.conrelid AND a.attnum = ANY(i.indkey)
  );

-- Find slow queries
SELECT query, mean_exec_time, calls
FROM pg_stat_statements
WHERE mean_exec_time > 100
ORDER BY mean_exec_time DESC;

-- Check table bloat
SELECT relname, n_dead_tup, last_vacuum
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY n_dead_tup DESC;
```

### Configuration Template

```sql
-- Connection limits (adjust for RAM)
ALTER SYSTEM SET max_connections = 100;
ALTER SYSTEM SET work_mem = '8MB';

-- Timeouts
ALTER SYSTEM SET idle_in_transaction_session_timeout = '30s';
ALTER SYSTEM SET statement_timeout = '30s';

-- Monitoring
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Security defaults
REVOKE ALL ON SCHEMA public FROM public;

SELECT pg_reload_conf();
```

## Related

- Agent: `database-reviewer` - Full database review workflow
- Skill: `clickhouse-io` - ClickHouse analytics patterns
- Skill: `backend-patterns` - API and backend patterns

---

*Based on Supabase Agent Skills (credit: Supabase team) (MIT License)*
