# Trace Propagation Architecture

> **Status**: Reference — Phase 2 (Micrometer Tracing) is planned but not yet implemented.
> **Depends on**: [Logging Architecture](LOGGING-ARCHITECTURE.md) Phase 1 (done)
> **Last updated**: 2026-07-06

## Overview

A **trace** is a single journal entry that follows a user request from the browser through every backend service and back, across both HTTP and Kafka boundaries. Each trace has a unique, randomly-generated `traceId` that appears in every log line produced by any service handling that request.

This document describes the end-to-end flow: how traceId is generated, propagated, and consumed — and what needs to be implemented at each layer.

## Key Concepts

| Term | Definition | Example |
|---|---|---|
| **Trace** | The entire journey of one request across all services | `GET /api/chat/rooms/42/messages` |
| **Trace ID** | 16-byte random hex identifier, same for every service in the trace | `4bf92f3577b34da6a3ce929d0e0e4736` |
| **Span** | One operation within a trace (one HTTP call, one Kafka produce) | `POST /api/chat/messages` inside chat-service |
| **Span ID** | 8-byte hex, unique per span, changes at every hop | `00f067aa0ba902b7` |
| **W3C traceparent** | Standard HTTP header carrying trace ID + span ID | `traceparent: 00-<traceId>-<spanId>-01` |
| **B3** | Legacy header format (Zipkin), still used by Spring Cloud for Kafka | `b3: <traceId>-<spanId>-1` |
| **MDC** | Logback's Mapped Diagnostic Context — stores traceId/spanId per thread | `MDC.put("traceId", ...)` |

## End-to-End Flow

```
┌──────────────────────────────────────────────────────────────────┐
│                    ONE TRACE (traceId=abc123)                     │
│                                                                  │
│  Browser ──HTTP──▶ Gateway ──HTTP──▶ ChatService ──Kafka──▶ Notif │
│  (generates   │  (extracts)  │  (extracts)  │  (injects) │ (extr)│
│   traceparent)│              │              │            │       │
│               ▼              ▼              ▼            ▼       │
│           MDC set       MDC set       MDC set     MDC set        │
│           traceId       traceId       traceId     traceId        │
│           =abc123       =abc123       =abc123     =abc123        │
│                                                                  │
│  Every log line across all services → {"traceId":"abc123",...}   │
└──────────────────────────────────────────────────────────────────┘
```

---

## Step 1 — Trace Origin: Browser or Gateway

The trace must start somewhere. There are two approaches:

### Option A: Browser-Generated (Recommended)

The Angular SPA generates a W3C `traceparent` header and attaches it to every outgoing HTTP request via an interceptor. This means the trace starts at the user action — the frontend error report and the backend logs share the same traceId.

```typescript
// Angular HTTP interceptor
import { v4 as uuidv4 } from 'uuid';

intercept(req: HttpRequest<unknown>, next: HttpHandlerFn): Observable<HttpEvent<unknown>> {
  const traceId = uuidv4().replace(/-/g, '');                       // 32 hex chars
  const spanId  = uuidv4().replace(/-/g, '').substring(0, 16);      // 16 hex chars

  return next(req.clone({
    setHeaders: { traceparent: `00-${traceId}-${spanId}-01` }
  }));
}
```

**Pros**: Full end-to-end visibility — frontend console errors and backend logs are joinable.
**Cons**: Requires `uuid` dependency in the frontend; traceId is client-generated (trustable for debugging, not for auth).

### Option B: Gateway-Generated (Simpler)

If no `traceparent` header is present when a request arrives at the Gateway, Spring Cloud Gateway + Micrometer Tracing auto-generates one. This is the default behavior — zero code required.

**Pros**: Zero frontend changes. Trace starts at the first backend service.
**Cons**: Frontend errors and backend logs are not correlated.

**Recommendation**: Start with Option B (zero effort). Add Option A later when frontend observability matters.

---

## Step 2 — HTTP Propagation: Gateway ↔ Services

Spring Boot + Micrometer Tracing (Brave) handles HTTP trace propagation via auto-configuration. The mechanism:

```
Incoming Request
│
├─ TracingFilter (auto-registered by Brave)
│   ├─ Extracts traceparent / b3 from request headers
│   ├─ Creates a new Span (child of the extracted parent)
│   ├─ Sets MDC: traceId=<extracted>, spanId=<new child>
│   └─ Stores TraceContext in thread-local
│
├─ Your Controller / Service code runs
│   └─ All log statements include traceId (via MDC → logback)
│
├─ Outgoing HTTP call (WebClient / RestTemplate)
│   ├─ Brave interceptor reads TraceContext from thread-local
│   └─ Injects traceparent header into outgoing request
│       (same traceId, new spanId for this hop)
```

**Zero code in your services** — having `micrometer-tracing-bridge-brave` on the classpath enables all of this.

```kotlin
// build.gradle.kts (per service — Phase 2)
implementation("io.micrometer:micrometer-tracing-bridge-brave")
```

---

## Step 3 — Kafka Propagation: Service → Message Broker

HTTP headers don't exist in Kafka. The trace context travels in **Kafka message headers** instead.

### Producer Side (chat-service, stream-service)

Spring Kafka auto-registers a producer interceptor when Micrometer Tracing is on the classpath:

```
Your code: kafkaTemplate.send("topic", message)
│
├─ KafkaTracingProducerInterceptor (auto-registered)
│   ├─ Reads TraceContext from thread-local (set by TracingFilter)
│   ├─ Injects into Kafka message headers:
│   │   b3: <traceId>-<spanId>-1
│   │
│   └─ Sends: Kafka message with trace headers
```

Your `application.yml` does NOT need extra config — the interceptor is registered automatically:

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      # No extra tracing config needed — auto-detected
```

### Consumer Side (notification-service)

```
Kafka message arrives (with trace headers)
│
├─ KafkaTracingConsumerInterceptor (auto-registered)
│   ├─ Extracts b3 header from message metadata
│   ├─ Creates a new Span (child of the producer span)
│   ├─ Sets MDC: traceId=<same>, spanId=<new child>
│   └─ Stores TraceContext in thread-local
│
├─ @KafkaListener method runs
│   └─ All log statements include traceId
│
└─ After listener returns:
    ├─ MDC cleared (prevents leaking between messages)
    └─ Span closed
```

Again, **zero configuration** — just the dependency:

```yaml
spring:
  kafka:
    consumer:
      group-id: notification-service
      # No extra tracing config needed — auto-detected
```

---

## Step 4 — MDC → Log Line Bridge (Already Built)

The logback config from Phase 1 already wires MDC into JSON output:

```xml
<encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
    <providers>
        <mdc/>  <!-- traceId + spanId from MDC → JSON fields -->
    </providers>
</encoder>
```

When Micrometer Tracing sets `MDC.put("traceId", "abc123")`, every log line automatically includes:

```json
{
  "timestamp": "2026-07-06T14:30:00.123Z",
  "level": "INFO",
  "service": "notification-service",
  "instanceId": "a1b2c3d4-...",
  "traceId": "abc123",
  "message": "Push notification sent to user 42"
}
```

No logback config change needed when Phase 2 is implemented.

---

## Header Formats

| Format | Header Name | Example | Used By |
|---|---|---|---|
| **W3C traceparent** | `traceparent` | `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01` | HTTP (standard) |
| **B3 single** | `b3` | `4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1` | Kafka headers (compact) |
| **B3 multi** | `X-B3-TraceId`, `X-B3-SpanId`, `X-B3-Sampled` | 3 separate headers | HTTP (legacy, still supported) |

Spring Cloud + Brave supports all three. W3C is preferred for HTTP. B3 single is used for Kafka because it fits cleanly in a single message header.

---

## Debugging with traceId

Once Phase 2 is implemented:

```bash
# Follow one request across ALL services
docker compose logs | grep '"traceId":"abc123"'

# Or with jq for structured queries
docker compose logs chat-service notification-service gateway-service \
  | jq 'select(.traceId=="abc123")'

# Find the ERROR that broke a trace
docker compose logs | jq 'select(.traceId=="abc123" and .level=="ERROR")'

# Timeline of one request
docker compose logs | jq 'select(.traceId=="abc123") | {time: .timestamp, svc: .service, msg: .message}'
```

## Implementation Checklist (Phase 2)

- [ ] Add `micrometer-tracing-bridge-brave` to all 6 services
- [ ] Add `micrometer-tracing` to all 6 services
- [ ] Verify HTTP propagation: call gateway → see same traceId in downstream service logs
- [ ] Verify Kafka propagation: publish message in chat-service → see same traceId in notification-service consumer logs
- [ ] (Optional) Add `traceparent` header generation to Angular HTTP interceptor

## References

- [W3C Trace Context Specification](https://www.w3.org/TR/trace-context/)
- [Micrometer Tracing Documentation](https://micrometer.io/docs/tracing)
- [Spring Cloud Sleuth Migration Guide](https://docs.spring.io/spring-cloud-sleuth/docs/current/reference/htmlsingle/)
- [Brave (Zipkin Tracer)](https://github.com/openzipkin/brave)
- [Logging Architecture](LOGGING-ARCHITECTURE.md) — Phase 1 setup that this builds on
- [ADR-0001: Structured JSON Logging](adr/common/0001-structured-json-logging.md)
