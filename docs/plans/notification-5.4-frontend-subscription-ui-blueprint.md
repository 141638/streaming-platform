# Blueprint: Notification 5.4 — Frontend Subscription UI

**Date:** 2026-07-16
**Status:** Implemented
**Depends on:** 5.1b (subscription REST API — committed), 5.0 (toast/bell prebuild — committed)

## Summary

Wire the notification subscription backend to the frontend. Three user-visible features:
1. **Follow button** on channel pages — activates the subscription backend
2. **Notification dropdown** on bell click — real notification history instead of dev mocks
3. **Notification settings page** — manage follows and delivery preferences

The 5.0 prebuild (toast surface, bell badge, SSE connection, notification cards) is already in place. This blueprint adds the missing interaction layer.

## Patterns to Mirror

| Category | Source | Pattern |
|----------|--------|---------|
| Service injection | `notification.service.ts:28-29` | `inject(HttpClient)`, `inject(AuthService)` — no constructor |
| DTO contracts | `notification.dto.ts` | Interfaces (not classes), `readonly` fields, backend wire types + mapped frontend types |
| Page component | `channel.page.ts:50-137` | Standalone, `OnPush`, `input()`, `inject()`, lazy-loaded route |
| HTTP error handling | `channel.page.ts:129-135` | `.subscribe({ next, error })` with `AbortError` passthrough |
| PrimeNG overlay | `app-shell.component.html:31` | `p-menu` with `[popup]="true"` + `toggle($event)` |
| Organism/molecule split | `chat-panel/` organism, `notification-card/` molecule | Smart container fetches data, dumb children receive inputs |
| Signal bridge | `notification-bell.component.ts:22-24` | `toSignal(observable$, { initialValue })` |

## Backend Prerequisite

The channel identity endpoint (`GET /v1/channels/{username}/identity`) returns only `username` + `verified`. The follow API needs the broadcaster's JWT `sub` as `targetId`. Add one field:

| File | Action | Why |
|------|--------|-----|
| `stream-service/.../dto/ChannelIdentityResponse.java` | Add `String broadcasterSubject` field | Enables follow API without a second HTTP call |
| `stream-service/.../StreamService.java` | Populate `broadcasterSubject` from session row | Already denormalized on `stream_session` |
| `frontend/.../channel-identity-response.dto.ts` | Add `broadcasterSubject: string` field | Mirror backend |

The broadcaster's subject is not a secret — it's a UUID already denormalized on `stream_session` and visible in JWT claims.

## Files to Change

| File | Action | Why |
|------|--------|-----|
| `stream-service/.../dto/ChannelIdentityResponse.java` | Modify | Add `broadcasterSubject` |
| `stream-service/.../StreamService.java` | Modify | Populate new field |
| `frontend/.../channel-identity-response.dto.ts` | Modify | Add `broadcasterSubject` |
| `frontend/.../subscription-response.dto.ts` | **New** | Mirror backend `SubscriptionResponse` |
| `frontend/.../subscription-request.dto.ts` | **New** | Mirror backend `SubscriptionRequest` |
| `frontend/.../preference-response.dto.ts` | **New** | Mirror backend `PreferenceResponse` |
| `frontend/.../preference-request.dto.ts` | **New** | Mirror backend `PreferenceRequest` |
| `frontend/.../subscription.service.ts` | **New** | HTTP client for subscription + preference APIs |
| `frontend/.../channel/channel.page.ts` | Modify | Inject SubscriptionService, follow/unfollow logic |
| `frontend/.../channel/channel.page.html` | Modify | Replace disabled Follow button with stateful one |
| `frontend/.../notification-bell/notification-dropdown.component.ts` | **New** | Bell dropdown with notification history |
| `frontend/.../notification-bell/notification-dropdown.component.html` | **New** | Dropdown template |
| `frontend/.../notification-bell/notification-bell.component.ts` | Modify | Replace `testMock()` with overlay toggle |
| `frontend/.../notification-bell/notification-bell.component.html` | Modify | Add `p-overlaypanel` + dropdown child |
| `frontend/.../settings/notification-settings.page.ts` | **New** | Settings page — follows + preferences |
| `frontend/.../settings/notification-settings.page.html` | **New** | Settings page template |
| `frontend/.../app.routes.ts` | Modify | Add `/settings/notifications` lazy route |
| `frontend/.../app-shell.component.ts` | Modify | Enable Subscriptions menu item, route to settings |
| `frontend/.../notification.dto.ts` | Modify | Wire `actionFromNotification()` for STREAM_LIVE → navigate |

## Tasks

### Task 1: Backend — expose broadcasterSubject on channel identity

- **Action**: Add `broadcasterSubject` field to `ChannelIdentityResponse` record. Populate from `stream_session.broadcaster_subject` in the identity query.
- **Files**:
  - `main/source/backend/stream-service/src/main/java/com/streaming/stream/api/dto/ChannelIdentityResponse.java`
  - `main/source/backend/stream-service/src/main/java/com/streaming/stream/application/StreamService.java`
- **Validate**: `./gradlew :stream-service:compileJava`

### Task 2: Frontend — subscription service + DTOs

- **Action**: Create DTO contracts matching the subscription/preference backend shapes. Create `SubscriptionService` with HTTP methods for all 8 endpoints.
- **Files**:
  - `main/source/frontend/streaming-ui/src/app/core/contracts/subscription-response.dto.ts` (new)
  - `main/source/frontend/streaming-ui/src/app/core/contracts/subscription-request.dto.ts` (new)
  - `main/source/frontend/streaming-ui/src/app/core/contracts/preference-response.dto.ts` (new)
  - `main/source/frontend/streaming-ui/src/app/core/contracts/preference-request.dto.ts` (new)
  - `main/source/frontend/streaming-ui/src/app/core/contracts/channel-identity-response.dto.ts` (modify — add `broadcasterSubject`)
  - `main/source/frontend/streaming-ui/src/app/core/services/subscription.service.ts` (new)

  `SubscriptionService` API surface:
  ```
  follow(targetType, targetId)       → PUT  /api/notifications/v1/subscriptions       → Observable<SubscriptionResponseDto>
  unfollow(id)                       → DEL  /api/notifications/v1/subscriptions/{id}  → Observable<void>
  getMySubscriptions(targetType?)    → GET  /api/notifications/v1/subscriptions        → Observable<SubscriptionResponseDto[]>
  checkSubscription(targetType, id)  → GET  /api/notifications/v1/subscriptions/check  → Observable<SubscriptionResponseDto>
  upsertPreference(channel, glob)    → PUT  /api/notifications/v1/preferences          → Observable<PreferenceResponseDto>
  getMyPreferences()                 → GET  /api/notifications/v1/preferences          → Observable<PreferenceResponseDto[]>
  updatePreference(id, body)         → PATCH /api/notifications/v1/preferences/{id}    → Observable<PreferenceResponseDto>
  deletePreference(id)               → DEL  /api/notifications/v1/preferences/{id}     → Observable<void>
  ```
- **Validate**: `ng build`

### Task 3: Follow button on channel page

- **Action**: Replace the disabled Follow button in `channel.page.html` with a stateful button driven by `SubscriptionService`. On init, call `checkSubscription("CHANNEL", broadcasterSubject)` to determine initial state. On click: follow → optimistic "Following" state; click again → unfollow → "Follow" state. Handle loading (spinner), error (toast), and 409 conflict (already following).
- **Files**:
  - `main/source/frontend/streaming-ui/src/app/pages/channel/channel.page.ts` (modify)
  - `main/source/frontend/streaming-ui/src/app/pages/channel/channel.page.html` (modify)
- **States**: loading (spinner in button), not-following (Follow, outlined, pi-heart), following (Following, filled, pi-heart-fill), error (toast via ToastService)
- **Validate**: `ng build` + manual: visit `/@username` → click Follow → button changes to Following → refresh page → still Following → click again → unfollow

### Task 4: Notification bell dropdown

- **Action**: Create `NotificationDropdownComponent` (molecule) that renders inside a `p-overlaypanel` attached to the bell button. Fetches notifications via `NotificationService.getNotifications()` with cursor pagination. Each card marks as read on click. Shows: loading skeleton, empty state ("No notifications yet"), error state, "Load more" at bottom. Replace `onBellClick()` in `NotificationBellComponent` from `testMock()` to `overlay.toggle($event)`.
- **Files**:
  - `main/source/frontend/streaming-ui/src/app/shared/molecules/notification-bell/notification-dropdown.component.ts` (new)
  - `main/source/frontend/streaming-ui/src/app/shared/molecules/notification-bell/notification-dropdown.component.html` (new)
  - `main/source/frontend/streaming-ui/src/app/shared/molecules/notification-bell/notification-bell.component.ts` (modify)
  - `main/source/frontend/streaming-ui/src/app/shared/molecules/notification-bell/notification-bell.component.html` (modify)
- **Validate**: `ng build` + manual: click bell → dropdown opens → shows history → click card → marks read → unread badge decrements

### Task 5: Notification settings page (5.4)

- **Action**: Create `/settings/notifications` route (lazy-loaded). Two sections: "Following" (list subscriptions with target type icon + unfollow button) and "Preferences" (per-channel toggles: in_app, email). Enable the "Subscriptions" user menu item.
- **Files**:
  - `main/source/frontend/streaming-ui/src/app/pages/settings/notification-settings.page.ts` (new)
  - `main/source/frontend/streaming-ui/src/app/pages/settings/notification-settings.page.html` (new)
  - `main/source/frontend/streaming-ui/src/app/app.routes.ts` (modify — add `/settings/notifications` route)
  - `main/source/frontend/streaming-ui/src/app/pages/app/app-shell.component.ts` (modify — enable "Subscriptions" item, route to `/settings/notifications`)
- **Sections**:
  - **Following tab/card**: List of active subscriptions. Each row: target type icon (channel/chat/stream), target name, "Unfollow" button. Empty state: "You're not following anyone yet."
  - **Preferences tab/card**: Per-channel toggles. Each row: channel label (In-App, Email, Push), active toggle (`p-toggleSwitch`), topic filter display. "Add preference" button opens inline form.
- **Validate**: `ng build` + manual: user menu → Subscriptions → page loads → shows follows → unfollow works → toggle preferences

### Task 6: Wire notification click actions

- **Action**: Update `actionFromNotification()` in `notification.dto.ts` to return actual navigation actions based on category + metadata. `STREAM_LIVE` → navigate to `/@username`. This makes both toast cards and dropdown cards clickable.
- **Files**:
  - `main/source/frontend/streaming-ui/src/app/core/contracts/notification.dto.ts` (modify)
- **Validate**: `ng build` + manual: receive STREAM_LIVE notification → click card → navigates to channel page

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| `broadcasterSubject` is NULL on backfilled sessions | Medium | Identity query already skips NULL `broadcaster_username` rows; add same guard for `broadcaster_subject` |
| 409 conflict on double-follow (network race) | Low | Backend returns existing subscription on 409; frontend treats 409 as success |
| SSE connection already calls `refreshUnreadCount()` after each event but dropdown doesn't refresh | Medium | Dropdown fetches from REST on open, not from SSE cache |
| "Subscriptions" menu item already exists but is disabled | None | Just flip `disabled: false` and add `command` |
| `p-overlaypanel` positioning with the bell in the toolbar | Low | Use `appendTo="body"` to avoid toolbar overflow clipping |

## Validation

```bash
# Backend
./gradlew :stream-service:compileJava

# Frontend
cd main/source/frontend/streaming-ui && ng build

# Manual flow
# 1. Visit /@someuser → Follow button works → state persists on reload
# 2. Click bell → dropdown shows notification history → click card → marks read
# 3. User menu → Subscriptions → settings page loads → follows listed → unfollow works
```

## Execution Order

```
Task 1 (backend) → Task 2 (DTOs + service) → Task 3 (Follow button) ↗
                                                                    → Task 6 (click actions)
                                               Task 4 (Dropdown)  ↗
                                               Task 5 (Settings)  ↗
```

Tasks 1-2 must be sequential (2 depends on 1's contract). Tasks 3, 4, 5 can run in parallel after 2. Task 6 is independent.
