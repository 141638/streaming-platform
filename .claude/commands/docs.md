---
description: Look up current library/API documentation via Context7 MCP. Use for framework APIs, setup questions, code examples.
argument-hint: "<library or framework> <question>"
---

# /docs — Documentation Lookup

Fetch current, accurate documentation for libraries and frameworks instead of relying on training data.

## When to Use

- "How do I configure Spring Security OAuth2 resource server?"
- "What's the API for R2DBC DatabaseClient?"
- "Show me Angular 19 signal-based state management"
- "How does Kafka consumer group rebalancing work?"

## How It Works

1. Resolves library name to a Context7 library ID
2. Fetches current documentation snippets for your question
3. Returns accurate, version-aware answers with code examples

## Examples

```
/docs Spring Security OAuth2 resource server configuration
/docs Angular signal store patterns
/docs PostgreSQL json_agg with joined tables
/docs Redis Streams consumer groups
```

Uses the Context7 MCP server when available. Falls back to web search if not configured.
