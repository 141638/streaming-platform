---
description: Java Spring Boot backend implementation — build APIs, services, repositories, domain models, and database entities.
argument-hint: "<task description> — e.g., 'add a subscription service with R2DBC repository', 'create POST /api/checkout endpoint'"
---

# /java-dev — Java Backend Implementation

**TOOL ROUTING:** When this command is invoked, you MUST:
1. Call `Skill({skill: "java-backend-workflow"})` to load the project implementation workflow.
2. Delegate implementation work to `Agent({subagent_type: "java-backend-developer", description: "<short task description>"})`. The agent uses a **conditional skill-loading matrix** — it only loads skills relevant to the task type, not all 6. It reads the service ADR, loads matched skills from the matrix, reads relevant project rules, and runs build verification.

**Before routing**, record telemetry:
`node .claude/scripts/telemetry-track.mjs --command java-dev`

## When to Use

- Building REST API endpoints (controllers, handlers, routers)
- Implementing service-layer business logic and orchestration
- Creating R2DBC repositories, entities, and Flyway migrations
- Adding Redis caching or Kafka producers/consumers
- Writing domain models, DTOs, and value objects
- Configuring Spring Security, validation, or exception handling
- Any Java Spring Boot backend implementation task

## What It Does

The **java-backend-developer** agent:

1. **Orient** — Reads the target service's ADR-0000, loads Java/Spring Boot skills, reads project rules
2. **Survey** — Reads existing patterns in the service to mirror conventions
3. **Implement** — Writes idiomatic Java following the project's layered-reactive architecture
4. **Verify** — Runs `./mvnw verify` (or `./gradlew check`) to ensure zero errors
5. **Report** — Summarizes files created/modified and build result

## Examples

```
/java-dev Add a SubscriptionService with create/cancel/renew in stream-service
/java-dev Create POST /api/checkout endpoint with @Valid request body
/java-dev Write Flyway V4__add_subscriptions_table.sql migration
/java-dev Add Redis caching for chat room message history
/java-dev Implement Kafka producer for stream session events
/java-dev Add R2DBC converters for the features JSONB column
```

## Related

- `/implement --be` — flexible implementation with auto-detected mode (delegates to java-backend-developer for backend tasks)
- `/review` — code review for Java changes (uses java-reviewer agent)
- `/blueprint` — plan before implementing complex backend features
- `database-reviewer` agent — for database schema review and query optimization
