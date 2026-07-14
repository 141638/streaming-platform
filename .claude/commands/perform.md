---
description: Quick, flexible code generation — no pipeline, no tests, no gates. For prototyping, suggesting, generating.
argument-hint: "<what to generate or suggest>"
---

# /perform — Quick & Flexible

Lightweight mode for when you don't need the full engineering pipeline.
Generate, suggest, prototype — no mandatory tests, no gates, no formal review.

**Before you begin**, record telemetry:
`node .claude/scripts/telemetry-track.mjs --command perform`

## When to Use

- "Generate a Dockerfile for this service"
- "Suggest 3 ways to cache this query"
- "Write a quick script to seed test data"
- "Create a bash script to check service health"
- "Prototype a WebSocket handler — I'll refine it later"
- "Show me how to configure CORS for multiple origins"

## What Happens

1. **Understand** — What do you need?
2. **Generate** — Produce the code, suggestion, or prototype
3. **Explain** — Brief explanation of what was done and why
4. **Done** — No tests, no review, no commit (unless you ask)

## What It Does NOT Do

- Does NOT enforce TDD
- Does NOT run formal code review
- Does NOT require a plan
- Does NOT gate on user confirmation
- Does NOT commit anything

## What It DOES

- Reads existing code to match conventions
- Follows project patterns naturally
- Produces working, well-structured output
- Explains trade-offs when multiple approaches exist

## Examples

```
/perform Generate a Python script to parse NGINX access logs and output top 10 IPs
/perform Show me 3 approaches to implement rate limiting in Spring Cloud Gateway
/perform Write a docker-compose override for local dev with hot reload
/perform Create a SQL migration to add a user_preferences table
```

Think of this as asking a senior colleague to quickly whip something up — quality output, no bureaucracy.
