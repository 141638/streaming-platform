# Notification 5.4 — Frontend Subscription UI Retrospective

**Date:** 2026-07-16
**Status:** Complete — uncommitted; all 6 tasks implemented
**Blueprint:** [notification-5.4-frontend-subscription-ui-blueprint.md](notification-5.4-frontend-subscription-ui-blueprint.md)

## 1. What was implemented (vs the original plan)

| Plan item | Files | Notes |
|-----------|-------|-------|
| Task 1: Add `broadcasterSubject` to channel identity | `ChannelIdentityResponse.java`, `StreamService.java` (backend), `channel-identity-response.dto.ts` (frontend) | One-field addition to existing record. Backend populates from `stream_session.broadcaster_subject`. Frontend DTO mirrors it. |
| Task 2: Subscription service + DTOs | `subscription.service.ts` (new), 4 DTOs (new) | 8 HTTP methods covering all subscription + preference endpoints. Follows `inject(HttpClient)` pattern from `notification.service.ts`. |
| Task 3: Follow button on channel page | `channel.page.ts`, `channel.page.html` (modified) | Replaced disabled placeholder with stateful button: Loading → Following (filled, click to unfollow) → Follow (outlined, click to follow). Optimistic UI with error toast on failure. `checkSubscription()` on page load determines initial state. |
| Task 4: Bell dropdown with notification history | `notification-dropdown.component.ts` + `.html` (new), `notification-bell.component.ts` + `.html` (modified) | Replaced `testMock()` dev helper with `p-overlaypanel` containing cursor-paginated notification list. Skeleton loading, empty state, "Load more" pagination, mark-as-read + badge refresh on card click. |
| Task 5: Notification settings page | `notification-settings.page.ts` + `.html` (new), `app.routes.ts`, `app-shell.component.ts` (modified) | Lazy-loaded `/settings/notifications` route. Two sections: Following list (with unfollow) + Delivery Channels (toggle switches for in_app/email/push). Enabled "Subscriptions" user menu item. |
| Task 6: Click actions on notification cards | `notification.dto.ts` (modified) | Wired `actionFromNotification()`: `stream.started`/`stream.ended` → parses `streamId` from metadata → navigates to `/channel/:streamId`. Will change to `/watch/:id` for followers after fan-out (5.2b). |

**Total: 9 new files, 11 modified files.**

### Post-implementation fixes

| # | Issue | Fix |
|---|-------|-----|
| 1 | Follow button hidden when `broadcasterSubject` null | Removed `@if (broadcasterSubject())` gate — button always visible, `onFollow()` guards internally |
| 2 | Bell badge clipped by circular button frame | Moved `p-overlay-badge` from `p-button-rounded` to wrapping `<span>` with `position: relative` |
| 3 | Notification dropdown rendered empty (all 4 `@if` branches as `<!--container-->`) | Two fixes: (1) `itemSize="auto"` → `[itemSize]="100"` — the string `"auto"` is not a valid CDK 19 input (only `CdkFixedSizeVirtualScroll` directive accepts `[itemSize]` as a number); (2) injected `ChangeDetectorRef` + `detectChanges()` in all 4 subscription callbacks — the component runs inside `p-popover` with `appendTo="body"` and `OnPush`, so signal-triggered CD may not reach the detached view |

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking doc | Reason |
|------|------------|-------------|--------|
| Subscribe button (paid membership) | Phase 7+ (monetization) | Channel page template | Separate concept from Follow — needs payment infra, subscriber badge design, ad-free logic, custom emoji system, private-stream gating |
| Follower fan-out wiring | 5.2b | [ADR-0002 §4](adr/notification/0002-notification-delivery-architecture.md) | Follow button works end-to-end but notifications only go to the broadcaster; followers need fan-out |
| Real follow/subscriber counts in channel header | 5.2b or later | `channel-header.component.ts` | Still hardcoded ("1.2K", "340") — needs `GET /v1/subscriptions?target_type=CHANNEL&target_id=...` wired into channel page |
| Email template rendering + real SMTP | 5.3 | [EmailAdapter skeleton](../main/source/backend/notification-service/src/main/java/com/streaming/notification/infrastructure/email/EmailAdapter.java) | `EmailAdapter.send()` is still a skeleton |
| Dedup key scoping refactor | 5.2b | [ADR common/0003](adr/common/0003-cross-service-event-dedup-key-scoping.md) | Safe today |
| Category follow button in category strip | Post-MVP | `category-strip.component.html` | Disabled with tooltip "Category subscriptions coming soon" |
| Unit/integration tests | Dedicated testing pass | — | All compile-verified; no runtime tests for new frontend components or backend field addition |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

The following unplanned work was found in the unstaged changes alongside 5.4. These were not part of the blueprint but improve UX quality:

| Item | Files | Context |
|------|-------|---------|
| Global overlay scrollbar pattern | `styles.scss`, `index.html`, `app-shell.component.html` | `.fixed-scroll` class provides macOS-style transparent scrollbars (show on hover, thin track, rounded thumb). Applied to `<html>` globally and to `<main>` in app shell. |
| Notification card unread dot | `notification-card.component.html`, `notification-card.component.scss` | Replaced 4px left severity border with YouTube-style unread indicator dot (8px, `var(--color-brand)`, positioned absolute). Cleaner visual and decoupled from severity color. |
| Stream dashboard layout polish | `stream-dashboard.page.html` | Stage height 40rem→30rem, player width fixed at 60rem, centered layout. |
| Stream player poster removal | `stream-player.component.html` | Removed static `<img>` poster fallback — only show video element when live; removed the `@else` poster branch entirely. |
| Chat shell live guard | `stream-chat-shell.component.html` | Added `live()` check to `@if` — only shows wired chat panel when the stream is live, otherwise shows the placeholder. |
| Stream list thumbnail container | `stream-list.component.html` | Fixed thumbnail height (220px container with `h-full w-full` image) for consistent card sizing. |

These are quality improvements, not functional requirements. No planning docs exist — they were applied directly to the working tree.

## 4. Architectural decisions made during implementation

### 4.1 `broadcasterSubject` on channel identity — ADR-0007 amendment

**Decision:** Added `broadcasterSubject` to `ChannelIdentityResponse`, which ADR-0007 originally said to exclude for "safe cross-user projection."

**Why changed:** The follow API (`PUT /v1/subscriptions`) needs the broadcaster's JWT `sub` as `targetId`. Without it, every channel page visit would require a second HTTP call to resolve the follow target. The `broadcasterSubject` is a UUID — not a secret, not PII, already denormalized on `stream_session` and visible in JWT claims.

**Risk assessment:** Low. The subject is already exposed in `StreamResponse` (for owner-only endpoints) and in the JWT itself. Adding it to the public identity endpoint doesn't leak anything an authenticated user couldn't already discover.

**ADR-0007 should be updated** to note this amendment — the exclusion was overly conservative for a non-secret identifier.

### 4.2 Follow state management pattern

**Decision:** Channel page manages follow state with three signals (`isFollowing`, `subscriptionId`, `isFollowLoading`) and a single `checkSubscription()` call on page load. Optimistic UI updates on follow/unfollow with error-toast rollback.

**Why not NgRx/signals store:** Single-page concern, not cross-app state. Three signals + one service call is sufficient.

### 4.3 Bell badge container fix

**Decision:** `p-overlay-badge` must be on a non-clipping container, not on `p-button-rounded`. Wrapping in `<span style="position: relative">` fixes badge visibility without custom CSS.

### 4.4 CDK virtual scroll in PrimeNG Popover — CD workaround

**Decision:** Components inside `p-popover` with `appendTo="body"` + `OnPush` may not receive change detection after signal updates because the view is detached from the CD tree (the Popover wraps content in `*ngIf="render"` + `<ng-content>`). Explicit `ChangeDetectorRef.detectChanges()` in subscription callbacks is required.

**CDK version note:** In CDK 19, `CdkVirtualScrollViewport` only accepts `orientation` and `appendOnly` as inputs. The `itemSize` input comes from the `CdkFixedSizeVirtualScroll` directive (selector: `cdk-virtual-scroll-viewport[itemSize]`), which must be imported separately in standalone components. Passing `"auto"` coerces to `NaN` and silently breaks the viewport.

**Pattern:** When a component uses signals + `OnPush` and is projected through a lazy overlay (`*ngIf` + `<ng-content>`), always call `detectChanges()` after async signal updates to ensure the view is current when reattached.

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action taken |
|----------|---------------|-------------|--------------|
| `IMPLEMENTATION-PLAN.md` | 5.4 unchecked, 5.1b listed as "uncommitted" | 5.1b committed; 5.4 implemented; "Active blueprint" stale | ✅ Updated (5.4 checked, status header refreshed) |
| `notification-5.4-frontend-subscription-ui-blueprint.md` | Status: "Planned" | Implemented | ✅ Updated to "Implemented" |
| `docs/adr/stream/0007-*.md` | Says `broadcasterSubject` excluded | Now included in `ChannelIdentityResponse` | ✅ Amendment note added (2026-07-16) |
| `docs/.telemetry.json` | Modified by hook | Side-effect artifact | Commit separately per convention |

## 6. Updated execution order (actual vs planned)

| Planned | Actual | Status |
|---------|--------|--------|
| Task 1: Backend `broadcasterSubject` | Task 1 | ✅ |
| Task 2: DTOs + SubscriptionService | Task 2 | ✅ |
| Task 3: Follow button | Task 3 | ✅ |
| Task 4: Bell dropdown | Task 4 | ✅ |
| Task 5: Settings page | Task 5 | ✅ |
| Task 6: Click actions | Task 6 | ✅ |

All tasks executed in blueprint order. Three post-implementation fixes applied (button visibility, badge clipping, dropdown rendering). Six unplanned UI polish changes shipped alongside (scrollbar pattern, card dot, dashboard layout, player poster, chat guard, thumbnail container).

## 7. Key risks carried forward

1. **`broadcasterSubject` may be NULL on backfill-gap channels**: Channels with no sessions (or sessions from before the denormalization migration) return `null`. The Follow button is always enabled but `onFollow()` returns early when `broadcasterSubject` is null — a silent no-op. **Mitigation**: The identity query falls back gracefully; users on backfill-gap channels simply can't be followed until they create a stream. This is rare and self-correcting.

2. **Follow state lost on channel page re-navigation**: `isFollowing` / `subscriptionId` are component-local signals, not cached across navigations. Each visit to `/@username` re-fetches via `checkSubscription()`. This is correct behavior but adds latency to the button render. **Mitigation**: Acceptable for MVP — the checkSubscription call is fast (single index lookup). A cross-page cache could be added later if needed.

3. **No integration tests**: All compile-verified only. The follow button, bell dropdown, and settings page have no runtime test coverage. **Mitigation**: Consistent with prior phases (5.0, 5.1a, 5.1b all shipped without integration tests). A dedicated testing pass is planned.

---

*Session focus: frontend implementation (5.4) + UI polish + dropdown rendering fix. 9 new files, 11 modified (5.4) + 6 additional files polished. Both backends + frontend compile clean. 3 post-implementation fixes applied.*
