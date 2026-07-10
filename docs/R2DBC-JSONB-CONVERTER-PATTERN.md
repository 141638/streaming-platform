# R2DBC JSONB Column Mapping Pattern

**Date:** 2026-07-10
**Pattern type:** Backend — PostgreSQL / Spring Data R2DBC
**Established in:** stream-service, `SocialLinksReadingConverter` + `SocialLinksWritingConverter`
**Applicable to:** Any Spring Boot service using R2DBC with PostgreSQL `jsonb` columns

---

## Problem

Spring Data R2DBC does not natively map PostgreSQL `jsonb` columns to Java domain types
(e.g., `List<SocialLink>`, `Map<String, Object>`, or custom records). The column value must
be manually serialized/deserialized, and the **wire type** sent to PostgreSQL matters: a
`String` value is transmitted as `character varying`, which causes:

```
ERROR: column "social_links" is of type jsonb but expression is of type character varying
Hint: You will need to rewrite or cast the expression.
```

The PostgreSQL R2DBC driver inspects the **Java type** of the parameter value to determine
which PG wire type to use. A `String` → `character varying`. Only
`io.r2dbc.postgresql.codec.Json` → `jsonb`.

---

## Solution

Use a pair of Spring Data R2DBC custom converters registered in `R2dbcConfig`:

| Converter | Type signature | Direction |
|-----------|---------------|-----------|
| `@ReadingConverter` | `Converter<Json, DomainType>` | PG `jsonb` → Java domain type |
| `@WritingConverter` | `Converter<DomainType, Json>` | Java domain type → PG `jsonb` |

The key contract:
- **Writing:** Serialize to JSON string, wrap with `Json.of(jsonString)`. Never return a
  plain `String` — the driver will use the wrong wire type.
- **Reading:** Accept `Json` (not `String`). Call `source.asString()` to get the raw JSON,
  then parse with Jackson or your preferred library.
- **Error handling:** On parse failure, return a safe fallback (empty list, empty map, or
  null depending on domain semantics). Never throw — a malformed JSONB value in the database
  should not crash the read path.

---

## Template

### Reading Converter

```java
package com.streaming.<service>.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.jetbrains.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

@ReadingConverter
public class <Domain>ReadingConverter implements Converter<Json, <DomainType>> {

    private static final TypeReference<<DomainType>> TYPE = new TypeReference<>() {};
    private final ObjectMapper objectMapper;

    public <Domain>ReadingConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public <DomainType> convert(@NonNull Json source) {
        try {
            return objectMapper.readValue(source.asString(), TYPE);
        } catch (Exception e) {
            // Return a safe fallback — never throw from a converter
            return <safeFallback>;
        }
    }
}
```

### Writing Converter

```java
package com.streaming.<service>.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

@WritingConverter
public class <Domain>WritingConverter implements Converter<<DomainType>, Json> {

    private final ObjectMapper objectMapper;

    public <Domain>WritingConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Json convert(@NotNull <DomainType> source) {
        try {
            return Json.of(objectMapper.writeValueAsString(source));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize <DomainType> to JSON", e);
        }
    }
}
```

### Registration in R2dbcConfig

```java
@Configuration
public class R2dbcConfig extends AbstractR2dbcConfiguration {

    private final ObjectMapper objectMapper;

    public R2dbcConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected List<Object> getCustomConverters() {
        return List.of(
            // ...existing converters...
            new <Domain>ReadingConverter(objectMapper),
            new <Domain>WritingConverter(objectMapper)
        );
    }
}
```

### Entity Field

```java
// The entity stores the domain type directly — no manual serialization in the service layer
@Column("social_links")
private List<SocialLink> socialLinks;  // mapped by converters, not by R2DBC natively
```

---

## Common Pitfalls

| Pitfall | Symptom | Fix |
|---------|---------|-----|
| Writing converter returns `String` | `ERROR: column is of type jsonb but expression is of type character varying` | Return `Json.of(jsonString)` instead of the raw String |
| Reading converter accepts `String` | Converter never matches — R2DBC driver provides `Json` for jsonb columns | Change parameter type to `Json` and call `source.asString()` |
| Converter throws on parse failure | Read path crashes on malformed JSONB in DB | Catch exception, return safe fallback (empty list/map) |
| Jackson `TypeReference` caching issue | Subtle serialization differences across calls | Make `TypeReference` a `static final` field |
| Not registering in `getCustomConverters()` | Converters silently not used; column value is null or default | Add both converters to the list returned by `getCustomConverters()` |

---

## When to Use This Pattern

- ✅ PostgreSQL `jsonb` column that needs to be a typed Java object (not a raw `String`)
- ✅ Small-to-medium JSON documents (not multi-MB blobs — JSONB has a ~1GB上限 but practical limit is much lower)
- ✅ JSON structure that is stable enough for a Jackson `TypeReference`

**Alternatives to consider:**
- If the JSON is always a flat map → `Converter<Json, Map<String, Object>>` with `objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {})`
- If the JSON structure varies polymorphically → consider a dedicated JSON column + `@JsonTypeInfo` or store as `String` and parse in the service layer
- If the JSON is large and rarely read → store as `TEXT` (not `jsonb`) and parse manually — avoids the converter overhead

---

## Existing Implementations

| Service | Converters | Domain type |
|---------|-----------|-------------|
| stream-service | `SocialLinksReadingConverter`, `SocialLinksWritingConverter` | `List<SocialLink>` (record: `String platform, String url`) |

---

## References

- [stream/ADR-0007](adr/stream/0007-public-channel-read-and-channel-service-seam.md) §4 — BroadcasterProfile design (first use of this pattern)
- [channel-page-retrospective.md](plans/channel-page-retrospective.md) §4.1 — How this pattern was discovered
- [Spring Data R2DBC Custom Converters](https://docs.spring.io/spring-data/r2dbc/docs/current/reference/html/#r2dbc.custom-converters)
- [PostgreSQL R2DBC Driver — Json type](https://github.com/pgjdbc/r2dbc-postgresql)
