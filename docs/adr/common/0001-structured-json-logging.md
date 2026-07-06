# ADR-0001: Structured JSON Logging with Logstash Encoder

**Date**: 2026-07-06
**Status**: accepted
**Deciders**: streaming-platform team

## Context

The streaming platform consists of 6 Spring Boot microservices (gateway, discovery, auth, stream, chat, notification) communicating via HTTP and Kafka. Currently, all services rely on Spring Boot's default logback auto-configuration — unstructured, human-readable text to stdout. As inter-service communication grows (especially async flows through Kafka), local debugging becomes insufficient. The Phase 6 observability plan calls for structured JSON logging, Micrometer tracing, and eventually a centralized log backend (ELK or Loki+Grafana).

This ADR covers the first step: standardizing log format across all services.

**Constraints:**
- Spring Boot 3.3.6 with logback-classic (bundled by default)
- No existing log aggregation infrastructure
- No custom logback/log4j configuration in any service
- Trace context (traceId/spanId) propagation is planned but not yet implemented

## Decision

**Adopt `logstash-logback-encoder` with a shared logback-spring.xml template, duplicated per service.**

Each service outputs structured JSON to stdout using a consistent schema:

```json
{
  "timestamp": "2026-07-06T14:30:00.123Z",
  "level": "INFO",
  "logger": "com.streaming.chat.service.ChatService",
  "message": "Message sent to Kafka topic stream-chat",
  "thread": "reactor-http-nio-3",
  "service": "chat-service",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "stack_trace": "..."
}
```

Key design choices:
- **Configuration duplicated, not shared** — a 30-line logback-spring.xml copied per service is simpler than a shared Gradle module. Logback uses the first config found on the classpath; a shared JAR would be silently bypassed if any service needs customization. See [alternatives](#alternative-1-shared-logging-gradle-module).
- **Service name via `<springProperty>`** — reads `spring.application.name` at startup; no per-service edits needed.
- **`traceId` and `spanId` via MDC** — fields are included now as empty strings, populated automatically when Micrometer Tracing is added later.
- **Console appender (stdout)** — compatible with Docker log drivers, `docker compose logs`, and any future log shipper (Filebeat, Otel Collector, Fluentd).

## Alternatives Considered

### Alternative 1: Shared Logging Gradle Module
- **Pros**: Single source of truth, no duplication
- **Cons**: Logback discovers the first `logback-spring.xml` on the classpath — overriding requires replacing the file entirely (no merge). A shared module that provides a `LogbackConfigurer` class adds complexity (new subproject, composite build wiring) for a 30-line config file.
- **Why not**: The "shared config" benefit is illusory — any per-service customization bypasses the shared config, leading to fragmentation. Duplication of 30 lines across 6 services is acceptable operational overhead. If the config grows complex, a shared `LogbackConfigurer` Java class can be extracted later; the per-service XML files become 5-line stubs that delegate to it.

### Alternative 2: Spring Boot Structured Logging (3.4+)
- **Pros**: Built-in, no extra dependency — `logging.structured.format.console=ecs` or `json`
- **Cons**: Requires Spring Boot 3.4+. Project is on 3.3.6. Upgrading solely for this feature introduces risk.
- **Why not**: The logstash-logback-encoder approach is more mature, more configurable, and works on 3.3.x. The JSON output format is more human-readable than ECS. When the project upgrades to 3.4+, switching is a config change, not a code change.

### Alternative 3: OpenTelemetry Logging Appender
- **Pros**: Single agent for logs + traces + metrics, OTLP-native
- **Cons**: Requires OpenTelemetry Collector infrastructure (another container), Otel Java agent or SDK in every service, more complex setup
- **Why not**: Premature for Phase 6 step 1. The logstash encoder approach delivers structured JSON today with zero infrastructure. Adding Otel later slots in cleanly — the JSON format stays the same, the Otel Collector ingests it from stdout.

## References

- [Logging Architecture](../../LOGGING-ARCHITECTURE.md)
- [Trace Propagation Architecture](../../TRACE-PROPAGATION.md)
- [Implementation Plan](../../IMPLEMENTATION-PLAN.md#phase-6--production-hardening)
- [logstash-logback-encoder](https://github.com/logfellow/logstash-logback-encoder)
- [Micrometer Tracing](https://micrometer.io/docs/tracing)

## Consequences

What becomes easier or more difficult to do because of this change?

### Positive
- Every log line is machine-parseable: `docker compose logs chat-service | jq '.message'`
- `traceId`/`spanId` fields are ready — adding Micrometer Tracing populates them automatically
- Consistent field names across all 6 services enable future log aggregation (ELK, Loki, etc.)
- No new infrastructure required — works with existing `docker compose logs`

### Negative
- Duplication: 6 copies of the same 30-line XML file (mitigated by `springProperty` — no per-service edits needed, truly identical copies)
- Extra dependency (`logstash-logback-encoder:8.0`) in each service
- Text log output is replaced by JSON — developers must use `jq` or a log viewer for readability (mitigated: Spring Boot devtools profile can switch back to text for local dev)

### Risks
- **`customFields` variable substitution failure**: If logback doesn't resolve `${SERVICE_NAME}` in `customFields`, the `service` field will be `null`. Mitigation: fall back to hardcoded service name in each copy.
- **LogstashEncoder performance overhead**: JSON encoding is slightly more expensive than text. Mitigation: benchmarked at <5% overhead in typical Spring Boot workloads; acceptable trade-off.
