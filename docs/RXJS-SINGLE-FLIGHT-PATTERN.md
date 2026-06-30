# RxJS Single-Flight Deduplication Pattern

**Status:** Implemented (in `AuthService.refresh()`) — reusable for any concurrent async operation  
**Source:** [auth.service.ts:37-53](../main/source/frontend/streaming-ui/src/app/core/services/auth.service.ts#L37-L53)

## Problem

When multiple parts of an application trigger the same async operation concurrently, each trigger produces its own HTTP request. If the operation has side effects (e.g., token rotation), duplicate requests can cause **correctness failures**, not just wasted bandwidth.

### Concrete Failure: Family Wipeout

```
Request A ──► 401 ──► POST /token/refresh (browser sends cookie: token_v1)
Request B ──► 401 ──► POST /token/refresh (browser sends cookie: token_v1)

Server processes A:
  → Finds token_v1, revokedAt=null ✓
  → Revokes token_v1, issues token_v2
  → Sets Set-Cookie: refresh_token=token_v2

Server processes B:
  → Finds token_v1, revokedAt ≠ null
  → REPLAY DETECTED → revokeAllActiveInFamily()
  → Every token in the family is now revoked
  → User is forcibly logged out on all devices
```

**Root cause**: The browser serializes `Set-Cookie` writes but does NOT synchronize cookie reads across concurrent requests. Multiple requests departing simultaneously all carry the same stale cookie value.

Server-side locking (`PESSIMISTIC_WRITE`) can't fix this — the duplicate requests haven't reached the server yet. The only reliable fix is to prevent duplicate HTTP calls from leaving the browser in the first place.

## Pattern

A **single-flight guard** ensures only one HTTP call is ever in-flight at a time. All concurrent callers subscribe to the same shared Observable.

```typescript
@Injectable({ providedIn: 'root' })
export class MyService {
  private readonly http = inject(HttpClient);

  /** Single-flight guard: only one call in-flight at a time. */
  private _inProgress: Observable<MyResponse> | null = null;

  public expensiveOperation(): Observable<MyResponse> {
    // If a call is already in progress, return the shared Observable
    if (this._inProgress) {
      return this._inProgress;
    }

    // Create the shared Observable
    this._inProgress = this.http
      .post<MyResponse>('/api/some-endpoint', payload)
      .pipe(
        // tap runs ONCE at the source level (before share multicasts)
        tap((res) => this.handleSideEffect(res)),
        // finalize clears the guard when the source completes or errors
        finalize(() => {
          this._inProgress = null;
        }),
        // share() multicasts one HTTP subscription to all subscribers
        share(),
      );

    return this._inProgress;
  }
}
```

## Operator Ordering — Why It Matters

The pipeline order is critical:

```
tap → finalize → share
```

### `tap` before `share`

`tap` runs at the **source** level — once per HTTP call, before `share` multicasts. If `tap` were after `share`, it would run once per subscriber (N times), causing duplicate side effects.

```
Without tap-before-share:  3 subscribers → 3 persistSession() calls
With tap-before-share:     3 subscribers → 1 persistSession() call
```

### `finalize` before `share`

`finalize` runs when the **source** completes (all subscribers unsubscribe → refCount drops to 0). It clears the `_inProgress` guard so the next refresh cycle can start fresh. If `finalize` were after `share`, it would run per-subscriber and clear the guard prematurely while other subscribers are still waiting.

### `share()` last

`share()` multicasts the single source subscription to all subscribers. It uses `refCount` — the source is subscribed when the first subscriber arrives and unsubscribed when the last departs.

## Caller Experience

All callers use `expensiveOperation()` identically — the dedup is transparent:

```typescript
// Component A
this.myService.expensiveOperation().subscribe(res => console.log('A', res));

// Component B (called simultaneously)
this.myService.expensiveOperation().subscribe(res => console.log('B', res));

// Result: ONE HTTP call. Both A and B receive the same emitted value.
```

## When to Use

| Scenario | Use Single-Flight? |
|----------|-------------------|
| Token refresh (POST with side effects) | **Yes** — correctness-critical |
| User profile fetch (GET, cached) | No — use a separate cache strategy |
| Form submission (deliberate per-click) | No — each click is a distinct intent |
| Config fetch at app init | **Yes** — prevents duplicate bootstrapping |
| Search with debounce | No — `switchMap` already handles this |

## When NOT to Use

- **Distinct user actions**: Two separate button clicks should produce two requests. Single-flight would drop the second one.
- **Polling or recurring**: Use `interval` or `timer` instead.
- **Operations that must never share state**: If each caller needs its own independent result, single-flight is wrong.

## Generic Variant

The pattern can be extracted for any service:

```typescript
/**
 * Wraps a factory function with single-flight deduplication.
 * All concurrent callers share the result of a single invocation.
 */
export function singleFlight<T>(factory: () => Observable<T>): () => Observable<T> {
  let inProgress: Observable<T> | null = null;

  return () => {
    if (inProgress) {
      return inProgress;
    }
    inProgress = factory().pipe(
      finalize(() => { inProgress = null; }),
      share(),
    );
    return inProgress;
  };
}

// Usage
const refresh = singleFlight(() => this.http.post<LoginResponse>('/api/auth/v1/token/refresh', {}));
refresh().subscribe(...);
```

## Related Docs

- [Refresh Token Rotation](REFRESH-TOKEN-ROTATION.md) — The server-side rotation that this pattern protects
- [Auth Interceptor Pattern](AUTH-INTERCEPTOR-PATTERN.md) — Where this pattern is called from

## References

- [RxJS: share() operator](https://rxjs.dev/api/operators/share)
- [RxJS: Multicasting operators guide](https://rxjs.dev/guide/subject#multicasted-observables)
