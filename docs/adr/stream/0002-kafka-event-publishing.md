# ADR-0002: Kafka Event Publishing

**Status:** Accepted
**Date:** 2026-07-07
**Domain:** Stream Service

## Context

The stream service must publish lifecycle events (`STREAM_CREATED`, `STREAM_STARTED`, `STREAM_ENDED`, etc.) to Kafka for downstream consumers (notification-service, chat-service). The existing `StreamEventPublisher` used hand-rolled JSON via `String.format()`, hardcoded the topic name, and was never wired into the service layer.

The stream service runs on Spring WebFlux (reactive). The Kafka producer must not block the Netty event loop.

## Decision

### Reactive Wrapper Pattern

Wrap `KafkaTemplate.send()` (which returns `CompletableFuture`) in `Mono.fromFuture()` to bridge into the reactive chain. Offload to `Schedulers.boundedElastic()` to keep the event loop free:

```java
public Mono<Void> publish(StreamEvent event) {
    return Mono.fromCallable(() -> objectMapper.writeValueAsString(event))
            .flatMap(payload ->
                    Mono.fromFuture(kafkaTemplate.send(topic, event.streamId(), payload))
                            .timeout(Duration.ofSeconds(2))
            )
            .doOnError(ex -> log.warn("Kafka publish failed: ...", ex))
            .onErrorComplete()
            .subscribeOn(Schedulers.boundedElastic())
            .then();
}
```

### Fire-and-Forget Integration

The service layer fires the publish as a side effect without blocking the HTTP response:

```java
return repository.save(entity)
        .doOnSuccess(saved -> eventPublisher.publish(event).subscribe())
        .map(StreamResponse::from);
```

### Event Envelope

Canonical `StreamEvent` record with factory methods for each event type. Jackson serialization replaces hand-rolled `String.format()` JSON. Topic name externalized to `application.yml`.

### Security

Stream key hashes are **not** included in event payloads. Events carry only: `eventType`, `streamId`, `eventId`, `timestamp`, `broadcasterSubject`.

### Trade-off: At-Most-Once Delivery

If Kafka is unavailable, events are logged as warnings and lost. The HTTP response succeeds regardless (state change is durable in PostgreSQL). This is intentional for Phase 2.0 — an outbox pattern would guarantee delivery but adds significant complexity.

### Alternatives Considered

| Approach | Verdict | Reason |
|----------|---------|--------|
| Synchronous `CompletableFuture.get()` | Rejected | Blocks Netty event loop; breaks reactive pipeline |
| Outbox pattern (DB table + CDC) | Deferred | Correct but complex; warrants a separate phase |
| `ReactiveKafkaProducerTemplate` | Deferred | Requires spring-kafka reactive module; evaluate in Phase 2.6 |

## Deferred Concerns (Future ADRs)

1. **DLQ / Retry**: Failed publishes should be retried with backoff and dead-lettered after N attempts
2. **Idempotency**: `eventId` on the envelope enables consumers to deduplicate; producers should set `enable.idempotence=true`
3. **Outbox Pattern**: Write events to an `outbox` table in the same DB transaction, then CDC to Kafka — guarantees at-least-once delivery
4. **Schema Registry**: Avro or JSON Schema for event evolution as downstream consumers multiply
5. **Exactly-Once Semantics**: Kafka transactions for atomic produce across topics (needed when chat-service and notification-service consume from separate topics)

## Consequences

- **Positive**: Fully non-blocking; zero impact on HTTP response latency
- **Positive**: Clean separation — entity transitions are DB-only, events are a side effect
- **Negative**: Events can be lost if Kafka is down (mitigated by structured logging; proper fix deferred)
- **Negative**: `.subscribe()` in `doOnSuccess` creates a "hot" subscription that runs independently of the request lifecycle (acceptable for fire-and-forget semantics)

## References

- [ADR-0001: Stream State Machine](0001-stream-state-machine.md)
- `StreamEventPublisher.java` — reactive wrapper implementation
- `StreamEvent.java` — event envelope record
- [Spring Kafka Documentation](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/listener-annotation.html)
