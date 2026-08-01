# Reactive Scheduled-Method Pattern

**Status:** Established (2026-08-02)
**Applies to:** Any Spring `@Scheduled` or `@KafkaListener` method that runs a reactive chain

## Problem

Spring's `@Scheduled` and `@KafkaListener` annotations invoke methods on **imperative carrier threads** — the scheduler's thread pool or the Kafka consumer thread. These threads do not understand reactive types. Two failure modes arise when a scheduled method builds a reactive chain:

### Failure mode 1: Returning a reactive type (silent no-op)

```java
// BROKEN — never executes
@Scheduled(fixedDelay = 60_000)
public Mono<Void> doWork() {
    return reactiveChain.then();   // assembles chain, returns Mono, nobody subscribes
}
```

Spring sees `Mono<Void>` as the return type, assumes reactive subscription is handled elsewhere, and **discards the Mono**. The chain assembles but never executes. No log, no error — a silent no-op.

### Failure mode 2: `.subscribe()` on a daemon thread (swallowed errors)

```java
// BROKEN — errors swallowed
@Scheduled(fixedDelay = 60_000)
public void doWork() {                     // void — method body runs
    reactiveChain
        .onErrorComplete()                 // error → "everything's fine"
        .subscribe();                      // subscribes on daemon thread
}
// Scheduler thread: method returned normally ✓
// Reality: chain errored, but nobody knows
```

`.subscribe()` runs the chain on a **daemon thread**. The scheduler thread returns from `doWork()` immediately — it has no visibility into what happens on that daemon thread. If the chain errors, `.onErrorComplete()` converts it to a completion signal, and the error vanishes without a stack trace.

## Solution

**Use `.blockOptional(Duration)` to bind the reactive chain to the carrier thread.**

```java
// CORRECT — executes AND propagates errors
@Scheduled(fixedDelay = 60_000)
public void doWork() {
    reactiveChain
        .doOnError(ex -> log.error("Scheduled work failed", ex))
        .then()                                // Flux<T> → Mono<Void>
        .blockOptional(Duration.ofSeconds(55)); // blocks carrier thread, throws on error
}
```

### What `.blockOptional()` does

| Aspect | `.subscribe()` | `.blockOptional(Duration)` |
|--------|---------------|---------------------------|
| **Thread** | Daemon thread | Carrier (scheduler) thread |
| **Error visibility** | Swallowed (unless error handler provided) | Propagated as exception to Spring |
| **Spring's view** | Method returned OK | Spring catches exception, logs stack trace |
| **Timeout safety** | None — runs forever if hung | Throws `NoSuchElementException` after duration |
| **Monitoring** | Invisible | Visible in actuator metrics, error logs |

### Why this is acceptable

Scheduled methods run on Spring's `TaskScheduler` thread pool — **not the Netty event loop**. Blocking here does not affect request-serving threads. The `OutboxPoller` Javadoc explicitly documents this:

> Runs on a Spring scheduler thread (not the Netty event loop), so `.block()` is acceptable — this is background infrastructure, not request-serving code.

## When to Use Each Pattern

| Context | Pattern | Rationale |
|---------|---------|-----------|
| `@Scheduled` method | `void` + `.then().blockOptional(Duration)` | Only subscriber on the carrier thread |
| `@KafkaListener` method | `void` + `.then().blockOptional(Duration)` | Same — imperative carrier thread |
| Request-scoped fire-and-forget | `.subscribeOn(Schedulers.boundedElastic()).subscribe()` | Must NOT block the request thread |
| Reactive pipeline side-effect | `.onErrorComplete()` inline in `flatMap` | Must not fail the request; error is logged only |

## The `.then()` Requirement

`blockOptional` is a **Mono-only** method. Reactive chains built with `flatMap` return `Flux<T>`. Always convert with `.then()` first:

```java
// WRONG — blockOptional doesn't exist on Flux
reactiveChain
    .flatMap(this::processItem, 16)
    .blockOptional(Duration.ofSeconds(30));   // ❌ compilation error

// CORRECT — .then() converts Flux<T> → Mono<Void>
reactiveChain
    .flatMap(this::processItem, 16)
    .then()                                    // Flux → Mono
    .blockOptional(Duration.ofSeconds(30));    // ✅
```

Alternative: `.collectList().blockOptional(Duration)` if you need the collected results.

## Timeout Formula

```
blockOptional timeout = scheduled interval − buffer

Buffer = ~5 seconds (prevents overlap if the chain runs long)
```

| Interval | Timeout |
|----------|---------|
| 10 seconds | 8 seconds |
| 30 seconds | 25 seconds |
| 60 seconds | 55 seconds |
| 5 minutes (300s) | 290 seconds |

## `.onErrorComplete()` — When to Keep It

For **bulk operations** (e.g., SCAN + flatMap over many keys), keep `.onErrorComplete()` if one item's failure shouldn't abort the whole batch. The chain still completes successfully; individual failures are logged inside the flatMap handler.

```java
// Bulk flush: one bad key shouldn't kill the whole cycle
redisTemplate.scan(...)
    .flatMap(this::flushOne, 16)   // flushOne handles its own errors
    .doOnError(ex -> log.error(...))
    .onErrorComplete()             // keep — bulk resilience
    .then()
    .blockOptional(Duration.ofSeconds(290));
```

For **single-operation scheduled methods** (archive, harvest), remove `.onErrorComplete()` so errors propagate to the scheduler.

## Existing Implementations

| Service | Class | Method | Pattern |
|---------|-------|--------|---------|
| stream-service | `OutboxPoller` | `poll()` | `.onErrorResume().collectList().blockOptional(30s)` |
| stream-service | `HeartbeatHarvestService` | `harvest()` | `.then().blockOptional(25s)` |
| stream-service | `ChatArchiveScheduler` | `archiveExpiredChats()` | `.then().blockOptional(55s)` |
| stream-service | `ViewerCountPushService` | `pushViewerCounts()` | `.then().blockOptional(8s)` |
| stream-service | `ViewCountFlushService` | `flushViewCounts()` | `.onErrorComplete().then().blockOptional(290s)` |
| notification-service | `StreamControlListener` | Kafka listener | `.onErrorResume().blockOptional(10s)` |

## References

- `OutboxPoller.java` — the canonical reference implementation with Javadoc justification
- [C4 Retrospective](plans/C4-fire-and-forget-subscribe-fix-retrospective.md)
