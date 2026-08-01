# C3 — Kafka Topic Provisioning — Implementation Retrospective

**Date:** 2026-08-01
**Status:** Complete

## 1. What was implemented (vs the original plan)

| Planned item | Commit(s) | Notes |
|-------------|-----------|-------|
| Disable `KAFKA_AUTO_CREATE_TOPICS_ENABLE` in all 5 broker configs | _(uncommitted)_ | `compose.yaml`, `single-broker/docker-compose.yaml`, `broker1/2/3.yml` |
| Disable `spring.kafka.admin.auto-create` in stream-service | _(uncommitted)_ | `stream-service/application.yml` |
| Provision topics declaratively | _(uncommitted)_ | `KafkaTopicConfig.java` in `common` module |
| Create Kafka infrastructure reference doc | _(uncommitted)_ | `docs/architecture/kafka-infrastructure.md` |

### Plan deviation

The original C3 plan specified `KAFKA_CREATE_TOPICS` env var for topic provisioning. During
implementation discussion, this was changed to `@Bean NewTopic` in the `common` module because:

- New topics don't require broker restart (KafkaAdmin creates at app startup via TCP)
- Topic definitions live near the event schema (`StreamEvent.java`), not in docker-compose
- Migrates cleanly to Terraform/infra-as-code later
- Works when Kafka is remote (AdminClient connects over the network)

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking doc | Reason |
|------|------------|-------------|--------|
| DLQ consumer | Observability phase | `kafka-infrastructure.md` §11 G1 | Out of scope for C3 |
| OutboxPoller DLQ integration | Task A5 | `kafka-infrastructure.md` §11 G2 | Out of scope for C3 |
| Multi-broker RF=2 configuration | Follow-up | C3 plan Design Decisions table | Dev-only; RF=1 works everywhere |

## 3. What was deferred but NOT yet documented

None — all gaps were already captured in `kafka-infrastructure.md` §11.

## 4. Architectural decisions made during implementation

1. **Shared-library topic provisioning over `KAFKA_CREATE_TOPICS` env var**
   - Why: No broker restart for new topics, topic config colocated with event schema, migrates to Terraform later
   - Documented in: C3 plan (updated) + `kafka-infrastructure.md` §2
   - Format: Plan documentation (not ADR-worthy — it's an implementation choice within an existing pattern)

2. **`common` module as the home for Kafka topic definitions**
   - Why: `common` already holds `StreamEvent.java` — the canonical event contract. Topic definitions are the infrastructure counterpart.
   - Documented in: `KafkaTopicConfig.java` class-level javadoc + `kafka-infrastructure.md`
   - Format: Code-level documentation (not a separate pattern doc)

## 5. Documents to update

| Document | Current status | What's stale | Action |
|----------|---------------|-------------|--------|
| `REDIS-KAFKA-PRODUCTION-GAP.md` | K7 listed as open (MEDIUM, T2) | K7 now closed | Mark ✅ Done with date |
| `IMPLEMENTATION-PLAN.md` | Phase E lists "disable auto-create-topics" as incomplete | C3 completed | Mark ✅ done |
| `docs/plans/C3-kafka-topic-provisioning.md` | Status: In progress | Implementation complete | Mark Status: Complete |

## 6. Architecture & reference docs updated

| Document | Trigger | What changed | Summary |
|----------|---------|-------------|---------|
| `docs/architecture/kafka-infrastructure.md` | **New doc created** | Created entire doc: 12 sections covering topology, topics, events, producers, consumers, DLQ, outbox, saga, serialization, config, gaps, link map | New single source of truth for Kafka topology |
| `.claude/skills/session-retro/SKILL.md` | New reference doc added | Added §P to update matrix (14 triggers) + 6 staleness signal rows | Retro skill now tracks Kafka doc staleness |
| `docs/plans/C3-kafka-topic-provisioning.md` | Plan deviation (env var → shared library) | Rewrote Solution, Design Decisions, Files to Change, Validation sections | Reflects actual implementation approach |

## 7. Updated execution order

| Planned | Actual | Status |
|---------|--------|--------|
| C3: KAFKA_CREATE_TOPICS approach | C3: @Bean NewTopic in common | ✅ Complete |

## 8. Key risks carried forward

1. **Existing auto-created topics won't change until broker restart** — the `KAFKA_AUTO_CREATE_TOPICS_ENABLE: false` takes effect on next broker restart. Until then, auto-create is still on for running brokers.
2. **KafkaAdmin depends on stream-service boot** — if stream-service is not running, topics won't be created on fresh Kafka instances. Mitigation: stream-service is always part of the stack.
3. **common module now depends on spring-kafka** — adds a transitive dependency to all services that depend on common, even if they don't use Kafka. Current impact: none (all services except auth already depend on spring-kafka). Mitigation: this is a `compileOnly` candidate if it becomes an issue.
