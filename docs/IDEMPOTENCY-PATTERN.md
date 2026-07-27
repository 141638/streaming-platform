# Idempotency Key Pattern

**Status:** Implemented (2026-07-27) — Phase 6.1 complete, uncommitted  
**Prerequisite for:** Safe POST/PUT/PATCH/DELETE retry in auth interceptor ✅

## Problem

HTTP requests may be duplicated by several mechanisms:

| Source | Example |
|--------|---------|
| Auth interceptor | Token expires → refreshed → retries POST |
| Browser | Automatic retry on connection timeout |
| Network | Load balancer or proxy retry |
| User | Double-click / double-tap |
| Mobile SDK | Platform-level auto-retry |

Without server-side deduplication, a duplicated POST can create duplicate
resources (e.g., two stream sessions, two purchases, two chat messages).

## Why the Interceptor Must NOT Generate the Key

The idempotency key represents the **user's intent**: one action = one key.

If the interceptor generated the key per HTTP request:
- A double-click would produce **two different keys** → both pass through → duplicate resource
- The interceptor cannot distinguish "retry of the same action" from "two separate clicks"

**The component that initiates the action owns the key.** The component knows
the user's intent; the interceptor only sees HTTP requests.

## Frontend Pattern

### IdempotencyService

```typescript
import { Injectable } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class IdempotencyService {
  /** Generate a fresh key per user action. */
  public newKey(): string {
    return crypto.randomUUID();
  }
}
```

### Component Usage

```typescript
@Component({ ... })
export class CreateStreamComponent {
  private readonly idempotency = inject(IdempotencyService);
  private readonly streamService = inject(StreamService);

  public createStream(): void {
    const idempotencyKey = this.idempotency.newKey();    // ← one key per click
    this.streamService.create(request, idempotencyKey)
      .subscribe(...);
  }
}
```

### Service Layer

```typescript
public create(request: CreateStreamRequest, idempotencyKey: string): Observable<StreamResponse> {
  return this.http.post<StreamResponse>('/api/stream/v1/streams', request, {
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}
```

### Key Lifetime

- A key represents **one attempt** at an action
- If the action fails with a non-retryable error (4xx), the key is consumed — a
  retry should generate a new key (it's a new attempt)
- If the action fails with a retryable error (network, 5xx, token expiry), the
  component retries with the **same key**

## Backend Pattern

### Gateway Filter

The gateway is the natural enforcement point — it sees all requests before
they reach any service.

```
┌──────────┐     ┌─────────────────┐     ┌──────────┐
│  Client  │ ──► │  Gateway Filter │ ──► │  Service  │
└──────────┘     │  ┌───────────┐  │     └──────────┘
                 │  │   Redis   │  │
                 │  └───────────┘  │
                 └─────────────────┘
```

**Filter logic:**

```
1. Read Idempotency-Key header
2. If absent → forward (non-idempotent request)
3. GET/HEAD/OPTIONS → skip (already idempotent by HTTP spec)
4. Check Redis: GET idempotent:{key}
   a. Found → return cached response (duplicate)
   b. Not found → forward to service
5. Cache response in Redis: SET idempotent:{key} <response> EX 86400 (24h TTL)
6. Return response to client
```

### Redis Key Design

| Key | Value | TTL |
|-----|-------|-----|
| `idempotent:{uuid}` | `{ "status": 201, "body": {...}, "contentType": "application/json" }` | 24 hours |

### Safety

- The filter only caches **successful** responses (2xx). Errors are not cached —
  the client can retry with the same key.
- TTL of 24 hours prevents unbounded Redis growth.
- Keys are UUIDv4 (128 bits of randomness) — collisions are astronomically
  unlikely.

## Migration Path

1. **Implement `IdempotencyService` in `frontend/streaming-ui/src/app/core/services/`**
2. **Implement gateway filter** in `gateway-service` with Redis
3. **Add `Idempotency-Key` header** to all POST/PUT/PATCH/DELETE service calls
4. **Enable POST retry in auth interceptor** — safe now because duplicates are caught at the gateway
5. **Audit** — verify every state-changing endpoint has idempotency coverage

## References

- [Stripe: Designing robust and predictable APIs with idempotency](https://stripe.com/docs/api/idempotent_requests)
- [IETF Draft: The Idempotency-Key HTTP Header Field](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)
