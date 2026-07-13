---
name: r2dbc-postgres-text-array-mapping
description: "R2DBC PostgreSQL maps TEXT[] columns natively to Java String[] only — NOT Set<String> or List<String>. Wrong type → silent 500 → frontend JSON.parse failure."
user-invocable: false
origin: auto-extracted
---

# R2DBC PostgreSQL: TEXT[] Columns Must Use Java String[], Not Set<String> or List<String>

**Extracted:** 2026-07-14
**Context:** Spring WebFlux + R2DBC + PostgreSQL. Entity field mapped to a `TEXT[]` column via `@Column`.

## Problem

The R2DBC PostgreSQL driver (via `PostgresDialect`) natively maps `String[]` ↔ `TEXT[]`. It does **not** support `Set<String>` or `List<String>` for array columns. If you use `Set<String>` as the entity field type, the driver throws a `MappingException` at persist time with no clear error message. The controller returns a 500 HTML error page, and the Angular `HttpClient` tries to parse the HTML as JSON → `SyntaxError: JSON.parse: unexpected keyword at line 1 column 1 of the JSON data`.

The debugging chain is misleading:

```
1. Frontend: "JSON.parse: unexpected keyword" ← points at the HttpClient call
2. Network tab: 500 response with HTML body ← backend crashed
3. Backend logs: MappingException ← R2DBC can't bind Set<String> to TEXT[]
4. Root cause: wrong Java collection type for PostgreSQL array column
```

## Solution

### Entity (R2DBC layer) — use `String[]`

```java
// CORRECT: String[] is the only collection type natively supported for TEXT[]
@Column("mentions")
private String[] mentions = new String[0];

// WRONG: R2DBC PostgreSQL driver does NOT support these for TEXT[]
// private Set<String> mentions = Collections.emptySet();    // → MappingException
// private List<String> mentions = Collections.emptyList();  // → MappingException
```

### API DTO — use `List<String>`

```java
// DTO: List<String> serializes cleanly to JSON array ["alice","bob"] via Jackson
public record MessageResponse(
    // ...other fields...,
    List<String> mentions
) {
    public static MessageResponse from(ChatMessage msg, String roomExternalKey) {
        return new MessageResponse(
            // ...other fields...,
            msg.getMentions() != null
                ? Arrays.asList(msg.getMentions())
                : Collections.emptyList()
        );
    }
}
```

### Bridge at the service boundary

```java
// In the service — convert List (from parsing) to String[] (for entity)
List<String> mentionList = parseMentions(body);
String[] mentions = mentionList.toArray(new String[0]);
ChatMessage msg = ChatMessage.create(room.getId(), authorSubject, authorUsername, body, now, mentions);
```

### Flyway migration

```sql
ALTER TABLE chat.chat_message ADD COLUMN IF NOT EXISTS mentions TEXT[] DEFAULT '{}';
CREATE INDEX IF NOT EXISTS idx_chat_message_mentions ON chat.chat_message USING GIN (mentions);
```

## Why This Happens

R2DBC PostgreSQL uses `PostgresDialect` which registers type converters for common Java ↔ PostgreSQL type pairs. The driver includes built-in converters for:

- `String[]` ↔ `TEXT[]` (via array codecs)
- `Integer[]` ↔ `INT[]`
- `Long[]` ↔ `BIGINT[]`

But it does **not** ship converters for `Set<T>` or `List<T>` to PostgreSQL array types. To use those, you would need a custom `R2dbcCustomConversions` converter — but `String[]` is simpler and sufficient.

## When to Use

- Any Spring WebFlux + R2DBC + PostgreSQL project using array columns (`TEXT[]`, `INT[]`, `BIGINT[]`, etc.)
- **Symptom:** Frontend `JSON.parse` error on API calls that should return JSON
- **Symptom:** Backend returns 500 HTML error page instead of JSON error response
- **Symptom:** `MappingException` in backend logs when persisting an entity with a collection field
- **Not needed for:** JPA/Hibernate projects (Hibernate handles collection mapping differently)
- **Not needed for:** Non-array PostgreSQL columns (scalar types map fine)
