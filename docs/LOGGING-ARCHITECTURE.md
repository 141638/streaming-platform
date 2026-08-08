# Logging Architecture

> **Status**: Phase 1 implemented — structured JSON logging. Phases 2-3 planned.
> **ADR**: [0001-structured-json-logging](adr/common/0001-structured-json-logging.md)
> **Last updated**: 2026-07-06

## Overview

The streaming platform's observability strategy is built in three phases:

```
Phase 1 (DONE)           Phase 2 (PLANNED)            Phase 3 (PLANNED)
─────────────────       ─────────────────────       ─────────────────────────
Structured JSON    →    Distributed Tracing     →    Centralized Log Backend
(logback XML)            (Micrometer + Brave)         (Loki/Grafana or ELK)
```

## Phase 1 — Structured JSON Logging (Implemented)

### Log Format

Every service emits a single JSON object per log line to stdout:

```json
{
  "timestamp": "2026-07-06T14:30:00.123Z",
  "level": "INFO",
  "logger": "com.streaming.chat.service.ChatService",
  "message": "Message sent to Kafka topic stream-chat",
  "thread": "reactor-http-nio-3",
  "service": "chat-service",
  "traceId": "",
  "spanId": "",
  "stack_trace": null
}
```

### Field Reference

| Field | Source | Notes |
|---|---|---|
| `timestamp` | Log event time | ISO 8601, UTC |
| `level` | Log level | TRACE, DEBUG, INFO, WARN, ERROR |
| `logger` | Logger name | Typically the fully-qualified class name |
| `message` | Log message | The formatted message string |
| `thread` | Thread name | Useful for reactive/async debugging |
| `service` | `spring.application.name` | Injected via `<springProperty>` in logback-spring.xml |
| `traceId` | MDC `traceId` | Empty until Phase 2 (Micrometer Tracing) |
| `spanId` | MDC `spanId` | Empty until Phase 2 (Micrometer Tracing) |
| `stack_trace` | Exception stack trace | `null` when no exception |

### Implementation

- **Library**: `net.logstash.logback:logstash-logback-encoder:8.0`
- **Config**: `logback-spring.xml` in each service's `src/main/resources/`
- **Template**: `main/source/backend/templates/logback-spring.xml` (canonical copy)
- **Deployment**: Identical file copied to all 6 services. No per-service edits needed — `springProperty` reads `spring.application.name`.

### Services

| Service | `spring.application.name` | logback-spring.xml |
|---|---|---|
| gateway-service | `gateway-service` | ✅ deployed |
| discovery-service | `discovery-service` | ✅ deployed |
| auth-service | `auth-service` | ✅ deployed |
| stream-service | `stream-service` | ✅ deployed |
| chat-service | `chat-service` | ✅ deployed |
| notification-service | `notification-service` | ✅ deployed |
| insight-service | `insight-service` | ❌ pending — needs `logback-spring.xml` copied from template |

### Local Usage

```bash
# View all logs (JSON)
docker compose logs chat-service

# Filter by level
docker compose logs chat-service | jq 'select(.level=="ERROR")'

# Filter by trace (after Phase 2)
docker compose logs | jq 'select(.traceId=="abc123")'

# Pretty-print recent logs
docker compose logs --tail=50 chat-service | jq '.'
```

## Phase 2 — Distributed Tracing (Planned)

> Full implementation details: [Trace Propagation Architecture](TRACE-PROPAGATION.md)

### Goal

Inject `traceId` and `spanId` into every log line and propagate them across service boundaries (HTTP + Kafka).

### Approach

- **Micrometer Tracing** (Spring Boot 3.x auto-configuration)
- **Brave** as the tracer implementation (Spring Cloud default)
- **Trace context propagation**:
  - HTTP: `X-B3-TraceId` / `traceparent` headers via Spring Cloud Gateway → downstream services
  - Kafka: trace headers in message metadata via `spring.kafka.*` configuration

### Dependencies (per service)

```kotlin
implementation("io.micrometer:micrometer-tracing-bridge-brave")
implementation("io.micrometer:micrometer-tracing")
```

### Expected Output

Once Phase 2 is active, `traceId` and `spanId` fields populate automatically — no logback config changes needed. The existing MDC key names (`traceId`, `spanId`) match Micrometer Tracing's defaults.

## Phase 3 — Centralized Log Backend (Planned)

### Goal

Aggregate logs from all service instances into a searchable, indexed store with a UI.

### Options

| Option | Components | Best for |
|---|---|---|
| **A: Grafana LGTM** | Loki (logs) + Tempo (traces) + Grafana (UI) + Otel Collector (agent) | Lower resource footprint, single UI for logs+traces+metrics, open source |
| **B: ELK Stack** | Elasticsearch + Logstash + Kibana + Filebeat (agent) | Enterprise standard, powerful full-text search, large ecosystem |
| **C: ELK + Fleet** | Option B + Fleet Server + Elastic Agents | 20+ instances, centralized agent management |

### Shipping Method

Regardless of backend, log shipping uses Docker's `json-file` log driver (already configured with rotation in compose files) + a lightweight agent:

```
┌──────────────┐     stdout (JSON)     ┌───────────────┐     ┌──────────────┐
│  Services     │─────────────────────▶│  Docker JSON   │────▶│  Agent       │
│  (container)  │                      │  log driver    │     │  (Filebeat/  │
│               │                      │  (10m/3 files) │     │   Otel Col)  │
└──────────────┘                      └───────────────┘     └──────┬───────┘
                                                                    │
                                                             ┌──────▼───────┐
                                                             │  Log Backend │
                                                             │  (ES/Loki)   │
                                                             └──────────────┘
```

### Decision Criteria (for later)

- **Loki vs Elasticsearch**: Loki indexes labels only (cheap storage, S3-compatible), Elasticsearch full-text indexes everything (powerful search, heavier). For 6 services with moderate log volume, Loki is likely sufficient and cheaper to operate.
- **Otel Collector vs Filebeat**: Otel Collector is the industry direction (single agent for logs+traces+metrics), but Filebeat is simpler for logs-only. Decision deferred until Phase 2 completion — if Phase 2 uses Otel for traces, Otel Collector becomes the natural choice for logs too.

## Future Considerations

### Spring Boot 3.4+ Structured Logging

Spring Boot 3.4 introduces built-in structured logging (ECS, JSON, GELF formats). When the project upgrades:
- Remove `logstash-logback-encoder` dependency
- Remove `logback-spring.xml`
- Set `logging.structured.format.console=json` in `application.yml`
- Output format is ECS by default (slightly different field names than current)

This is a drop-in swap — no log pipeline changes needed.

### Kubernetes / Production Deployment

When moving beyond Docker Compose:
- The `json-file` log driver is replaced by container runtime logging
- A DaemonSet agent (Fluentd, Otel Collector, or Filebeat) collects from node-level log files
- Service discovery labels (pod name, namespace) replace the `service` field
- Structured JSON format remains the same — only the collection layer changes

## References

- [ADR-0001: Structured JSON Logging](adr/common/0001-structured-json-logging.md)
- [Implementation Plan](../IMPLEMENTATION-PLAN.md#phase-6--production-hardening) — Phase 6.6 Observability
- [logstash-logback-encoder documentation](https://github.com/logfellow/logstash-logback-encoder)
- [Micrometer Tracing](https://micrometer.io/docs/tracing)
- [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/)
