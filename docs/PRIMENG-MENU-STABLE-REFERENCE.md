# PrimeNG Menu — Stable Array Reference Pattern

**Date:** 2026-07-17
**Discovered during:** Creator Dashboard visibility fix (Option A bug fix)
**Framework:** Angular + PrimeNG

## Problem

PrimeNG's `p-menu` component reads its `model` input on every change detection cycle. If the bound value is a **getter that returns a new array reference each time**, the menu:

- **Flashes on hover** — the menu re-renders mid-animation, destroying and recreating DOM nodes
- **Ignores clicks** — PrimeNG's internal `(click)` handlers fire on stale DOM that was replaced by the re-render
- **Appears briefly then vanishes** — the popup overlay opens, a subsequent CD cycle triggers a different reference, and the overlay collapse logic fires

The root cause: Angular's default `ChangeDetectionStrategy` compares object references (`===`). A getter returning `new Array()` or `[...items]` produces a new reference every read — PrimeNG's `ngOnChanges` fires, triggering a full menu teardown + rebuild.

## Solution

Use a **`readonly` field initialized once** at construction time. For conditional items that depend on runtime state (e.g., user role), build the array via a standalone factory function that receives the required services as parameters.

### ❌ Wrong — getter (new array every read)

```typescript
export class AppShellComponent {
  protected get userMenuItems(): MenuItem[] {
    const items: MenuItem[] = [
      { label: 'Channel', icon: 'pi pi-play', command: () => ... },
    ];
    if (this.authService.isStreamer()) {
      items.push({ label: 'Creator Dashboard', ... });
    }
    items.push({ label: 'Logout', ... });
    return items; // ← NEW array every CD cycle
  }
}
```

### ✅ Correct — `readonly` field + factory function

```typescript
export class AppShellComponent {
  protected readonly userMenuItems: MenuItem[] = buildUserMenu(
    inject(AuthService),
    inject(Router),
  );
}

/** Build the user menu once at construction time so PrimeNG gets a stable reference. */
function buildUserMenu(auth: AuthService, router: Router): MenuItem[] {
  const items: MenuItem[] = [
    { label: 'Channel', icon: 'pi pi-play', command: () => {
      const u = auth.myUsername();
      router.navigateByUrl(u ? `/@${u}/home` : '/dashboard/streams');
    }},
  ];

  if (auth.isStreamer()) {
    items.push({
      label: 'Creator Dashboard',
      icon: 'pi pi-chart-bar',
      command: () => router.navigateByUrl('/dashboard/streams'),
    });
  }

  items.push(
    { label: 'Watch History', icon: 'pi pi-history', command: () => router.navigateByUrl('/history') },
    { separator: true },
    { label: 'Logout', icon: 'pi pi-sign-out', command: () => auth.logout() },
  );

  return items;
}
```

## Why the factory function pattern works

1. **Stable reference** — `readonly` field is assigned once, never changes
2. **Conditional items evaluated at construction** — JWT claims don't change during a session, so evaluating `isStreamer()` once is correct
3. **`inject()` in field initializers** — Angular supports `inject()` outside the constructor in field initializers (v14+); the DI context is available during class initialization
4. **Standalone function** — keeps the component class clean; the function is testable independently

## When this pattern is needed

- Any PrimeNG component that reads an array input on every CD cycle: `p-menu`, `p-tieredMenu`, `p-tabMenu`, `p-steps`, `p-breadcrumb`
- Any Angular component with `ChangeDetectionStrategy.Default` (the default) where you pass an array to a child component's `@Input()`
- NOT needed when the array truly changes (e.g., dynamic list of notifications) — in those cases the re-render is intentional

## Trade-off

The factory function evaluates conditional logic (e.g., `auth.isStreamer()`) once at component construction. If the underlying state **changes during the component's lifetime** (e.g., a user upgrades to streamer mid-session), the menu won't reflect it until a full page reload. For JWT-claim-based conditions this is acceptable because claims are immutable for the session duration.

If the menu MUST react to runtime state changes, use a **signal** or **BehaviorSubject** with `async` pipe, but verify PrimeNG's compatibility first — some PrimeNG components don't handle async inputs well.

## Existing implementations

| File | Usage |
|------|-------|
| `frontend/.../app-shell.component.ts` | `userMenuItems` — stable array for user dropdown menu |
| `frontend/.../app-shell.component.ts` | `createMenuItems` — static create menu (unchanged, was already stable) |
