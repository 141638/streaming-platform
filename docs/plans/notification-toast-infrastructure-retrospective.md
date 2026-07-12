# Notification Toast Infrastructure — Implementation Retrospective

**Date:** 2026-07-12
**Status:** Complete (frontend prebuild — uncommitted, on `feat/chat-moderation-ux`)
**Related plans:** [Wave 2 Proactive Push](chat-moderation-wave2-proactive-push.md) · [ADR-0006](../adr/chat/0006-moderation-ux-capability-and-push-split.md) · [ADR-0007](../adr/chat/0007-proactive-push-infrastructure-gated.md)

## 1. What was implemented (vs the original blueprint)

| Planned item | Files | Notes |
|---|---|---|
| Notification DTO (category + action-based, extensible) | `core/contracts/notification.dto.ts` | `NotificationDto`, `ToastDto`, `NotificationSender`, `NotificationAction`. Aligned with notification-service plan — moderation first, stream/membership/system later. |
| Toast service (Subject-based global bus) | `core/services/toast.service.ts` | `ToastService` — `showNotification()`, severity helpers, `clear()`. Matches TaiyoYuka reference pattern. |
| Notification service scaffold | `core/services/notification.service.ts` | `pushNotification()` → ToastService. `testMock()` for bell-triggered E2E visual testing. SSE placeholder (Wave 2). |
| Notification sound utility | `core/lib/notification-sound.ts` | Web Audio API oscillator beep (800→1000 Hz, 200ms). Silent fallback on autoplay block. |
| UserProfilePicture atom | `shared/atoms/user-profile-picture/` | Avatar display (`p-avatar`) + channel navigation (`/@username`). System avatar via dicebear `bottts-neutral`. `pTooltip` showing `@username` or "Streaming Platform — system notification". |
| NotificationCard molecule | `shared/molecules/notification-card/` | Dumb — severity accent bar + icon + title (white/smoke) + message + relative time with full-date tooltip + sender avatar. Clickable wrapper (`cursor: pointer`, `user-select: none`, hover brightness 1.18). Emits `cardClick`. |
| NotificationToast host | `shared/molecules/notification-toast/` | Smart — subscribes `ToastService`, feeds `MessageService`, renders `p-toast` at `bottom-right` with custom `ng-template` embedding `NotificationCard`. Plays sound. Handles `cardClick` → navigation. |
| NotificationBell button | `shared/molecules/notification-bell/` | Header button (`p-button rounded text`, `pi pi-bell`). Click → `notificationService.testMock()` — 3 mock toasts (moderation warn, stream info, system info with bottts avatar + "2 days ago" relative time). |
| App shell integration | `app.config.ts`, `app.component.*`, `app-shell.*` | `MessageService` provider. Toast host at `AppComponent` level (outside router). Bell between Create btn and user avatar in toolbar. |
| Relative time formatter | `core/lib/time.ts` | `formatRelativeTime(iso, nowMs)` — full units ("5 minutes ago", "2 days ago", "Now"). Singular/plural-aware. |

### Shipped beyond the original blueprint
- **System avatar (dicebear bottts)** — not in the original blueprint. A fixed `bottts-neutral` seed for all system-notification avatars so it consistently reads as non-human.
- **Relative time** was originally planned as a card-level convenience but landed as a reusable `time.ts` utility (`formatRelativeTime`) with proper pluralization.
- **Three-tier mock** (moderation + stream + system) — blueprint only described one mock; expanded to exercise all card variants (with sender, with sender + action, system without sender).

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking doc | Reason |
|---|---|---|---|
| SSE handshake (`@microsoft/fetch-event-source`) | Wave 2 | ADR-0007, Wave 2 plan | Infrastructure-gated — Kafka broker + notification-service foundation |
| Bell unread badge (`p-overlay-badge`) | Wave 2 P2.5 | Wave 2 plan | Needs real notification data + SSE subscription |
| Bell click → popup menu with notification history | Wave 2 P2.5 | Wave 2 plan | Needs REST `GET /api/notifications` + mark-as-read |
| Per-message toast dismiss tracking | Wave 2 | Wave 2 plan | Current `messageService.clear()` clears all; need message-ID tracking when real notifications arrive |
| Notification persistence (indexedDB or service-level cache) | Wave 2 | Wave 2 plan | Bell menu needs offline-accessible history |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

None. All deferrals are already documented in ADR-0007 and the Wave 2 plan.

## 4. Architectural decisions made during implementation

### 4.1 Toast host placement: AppComponent (root), not AppShellComponent

**Decision:** `NotificationToastComponent` sits in `AppComponent` (outside `<router-outlet>`), not in `AppShellComponent`.

**Why:** The toast must be visible on auth pages (login, password reset) as well as authenticated pages. `AppShellComponent` is only loaded after `authGuard` passes — toast placed there would not render for guest routes. The TaiyoYuka reference also places toast at the `AppComponent` level.

### 4.2 Card action delegation: emit, don't navigate

**Decision:** `NotificationCardComponent` emits `cardClick<NotificationDto>()` instead of injecting `Router`.

**Why:** Keeps the card a pure dumb molecule. Different parents have different click behavior — toast navigates + dismisses, future bell menu marks-as-read + navigates. This follows the project's existing smart/dumb split convention.

### 4.3 System avatar identity: dicebear bottts-neutral, fixed seed

**Decision:** System notifications use `api.dicebear.com/9.x/bottts-neutral/svg?seed=streaming-platform` with a blue background — a consistent, recognizable non-human avatar.

**Why:** The `bottts` style reads immediately as "robot/system" — no ambiguity with user avatars. A fixed seed ensures every system notification looks identical, building a visual brand. The alternative (`identicon` — geometric hash patterns, `glyphs` — abstract shapes) would vary per seed and not clearly communicate "system."

### 4.4 Relative time: capital "Now", full word units, proper plurals

**Decision:** `formatRelativeTime` outputs "Now" (capitalized), "1 minute ago", "2 days ago" — not abbreviated "2d" or "2m."

**Why:** Toast cards are ephemeral (6s auto-dismiss) and benefit from instant readability. Abbreviated units (`2d`, `3mo`) are appropriate for dense list views (ban roster) but not for notification cards where users parse at a glance. The `formatExpiresIn` formatter keeps its abbreviated style for the ban roster.

### 4.5 Dual-close resolution: keep PrimeNG's built-in close, drop the card's X

**Decision:** Removed the card's dismissible X button. The PrimeNG `p-toast` wrapper provides its own close icon per message.

**Why:** Two X buttons on one toast card confused the visual. PrimeNG's close is anchored to the message lifecycle (`messageService.clear()`) and handles edge cases (hover states, auto-dismiss timing). The card doesn't need its own.

## 5. Documents to update

| Document | Current status | What's stale | Action |
|---|---|---|---|
| `docs/IMPLEMENTATION-PLAN.md` | Last updated 2026-07-11 | Phase 5 has no frontend prebuild listed | Add Phase 5 frontend prebuild row — toast + card + bell infrastructure |
| `docs/plans/chat-moderation-wave2-proactive-push.md` | Planned, gate in place | P2.5 "frontend" now has a prebuilt toast/card layer | Add § *Frontend prebuild* noting the toast infrastructure is ready; SSE wiring is the remaining P2.5 work |
| `docs/adr/chat/0006-moderation-ux-capability-and-push-split.md` | Accepted (Wave 1); Wave 2 proposed | Wave 2 frontend prebuild exists | Minor: note in Consequences that toast infrastructure is prebuilt |

## 6. Updated execution order

This session was a parallel prebuild — it does not advance the Wave 2 gate. Execution order within the session:

| Planned (blueprint) | Actual | Status |
|---|---|---|
| Task 1: Notification DTO | `notification.dto.ts` | ✅ |
| Task 2: Toast service | `toast.service.ts` | ✅ |
| Task 3: Notification card molecule | `notification-card/` | ✅ |
| Task 4: Notification toast shell | `notification-toast/` | ✅ |
| Task 5: App shell integration | `app.config.ts`, `app.component.*`, `app-shell.*` | ✅ |
| Task 6: Notification service scaffold | `notification.service.ts` | ✅ |
| Task 7: Sound infrastructure | `notification-sound.ts` | ✅ |
| Task 8: Ng build + verify | ✅ green | ✅ |
| (unplanned) UserProfilePicture atom | `user-profile-picture/` | ✅ |
| (unplanned) NotificationBell component | `notification-bell/` | ✅ |
| (unplanned) Relative time formatter | `time.ts` extended | ✅ |
| (unplanned) System avatar design | dicebear bottts | ✅ |

## 7. Key risks carried forward

1. **Toast auto-dismiss vs. relative time staleness.** Toast cards show relative time computed once on render ("Now", "2 minutes ago"). A toast that stays visible for 6s will still show "Now" at t=5s. Harmless for the current 6s lifecycle but worth noting when longer-lived toasts arrive.
2. **`messageService.clear()` clears all toasts.** When a user dismisses one card, all visible toasts vanish. Not an issue for the current mock (all 3 fire within 800ms) but will need per-message-ID tracking for real production use. Tracked in §2 deferrals.
3. **Audio autoplay policy.** The `AudioContext` creation in `playNotificationSound()` may be blocked by browsers before the first user gesture. The try/catch swallows this silently. When real notifications arrive via SSE (background delivery), the first few toasts may be silent until the user interacts with the page. Mitigation: call `AudioContext.resume()` on the first user gesture (click, keydown) — trivial to add later.
