# ADR-0009: @mention Precision and Autocomplete Architecture

**Date**: 2026-07-14
**Status**: accepted
**Deciders**: 141638, Claude

## Context

Phase 3.5 shipped chat with a basic `<input>` — no emoji picker, no @mention support. The deferred table in the implementation plan listed both as "Later" with no schema for mentions. We needed to add @mention autocomplete with three constraints:

1. **Mentions must be precise** — typing `@al` and sending should NOT count as mentioning "alice" unless the user explicitly selected "alice" from the dropdown (Slack/Discord/GitHub pattern).
2. **Autocomplete must work for everyone who has ever chatted** — not just the currently visible messages.
3. **Must use PrimeNG primitives** — no custom dropdown/popover components.

Additionally, a PrimeNG architectural quirk complicated the implementation: `p-autocomplete` replaces the entire form control value with the selected suggestion BEFORE firing `(onSelect)`, destroying the surrounding text context.

## Decision

### 1. Backend as Mention Authority

The backend parses @mentions from message bodies using regex `(?<!\w)@(\w{1,32})` regardless of whether the frontend autocomplete was used. The backend is the authoritative source of who was mentioned; the frontend autocomplete is purely UX convenience.

**Precision gate (frontend only):** The frontend is designed to only insert `@username` syntax when a user selects from the autocomplete dropdown. Free-form typing of `@username` still sends, and the backend still parses it — but the intended UX path is selection. This is a convention, not a server-enforced constraint.

### 2. Three-Tier Suggestion System

| Tier | Source | Latency | Scope |
|------|--------|---------|-------|
| 1 — Local | `uniqueChatters` computed from visible messages | Instant | Currently visible chatters, deduped, most-recent-first |
| 2 — API | `GET /rooms/{roomKey}/participants?q=&limit=10` | Async (~100-500ms) | Anyone who has ever chatted in this room (database query on `author_username`) |
| 3 — Fallback | Exact-match fallback row | Instant | When no results match, offer the typed query as "Add @user" |

Tier 1 renders immediately (synchronous `signal.set()` in `completeMethod`). Tier 2 merges asynchronously via `subscribe()`. A monotonic `mentionRequestId` counter discards stale API responses.

### 3. p-autocomplete State-Snapshot Pattern

Because p-autocomplete overwrites the form control value BEFORE `(onSelect)` fires, the @mention position and original text must be captured before p-autocomplete mutates them:

- `completeMentions(event)`: Detect the @mention trigger → save `savedMentionStart` and `savedOriginalQuery` → fetch suggestions
- `onMentionSelect(event)`: Reconstruct full text from saved state → replace only the `@query` portion with `@selected` + trailing space → reset saved state

### 4. Configuration Choices

| Setting | Value | Reason |
|---------|-------|--------|
| `[forceSelection]` | `false` | Users can type @mentions for users not in the suggestion list (Tier 3 fallback) |
| `[minLength]` | `1` | @mention can start at any cursor position; `@` itself is 1 character |
| `[dropdown]` | `false` | No dropdown button — suggestions only appear on @ trigger |

### 5. Enter Key Guard

When the mention panel is open and Enter is pressed, p-autocomplete's internal keydown handler (on the `<input>`) selects the highlighted item and fires `onSelect` BEFORE the event bubbles to the host `(keydown)` binding. By the time the host handler runs, `mentionSuggestions` is already cleared, making the old `mentionSuggestions().length === 0` check unreliable.

**Fix:** `mentionJustSelected` flag set in `onMentionSelect`, auto-cleared via `setTimeout(0)`, checked before sending in `onInputKeydown`. Supplemental: `mentionPanelVisible` signal driven by p-autocomplete `(onShow)`/`(onHide)` for cases where Enter is pressed but no selection occurs.

## Alternatives Considered

### Alternative 1: Custom dropdown (Phase 0 approach)
- **Pros**: Full control over event ordering, no state-snapshot needed
- **Cons**: ~200 lines of custom positioning, keyboard nav, ARIA, and scroll management
- **Why not**: Explicitly rejected by the team in favor of PrimeNG primitives

### Alternative 2: Client-side-only mention parsing (no backend)
- **Pros**: Simpler — no schema, no migration, no backend changes
- **Cons**: Notifications impossible without server knowledge of mentions; no audit trail; client bugs silently drop mentions
- **Why not**: Phase 6 notification service needs authoritative mention data

### Alternative 3: `Set<String>` for entity mentions field
- **Pros**: Natural Java representation — no duplicates
- **Cons**: R2DBC PostgreSQL driver does NOT map `Set<String>` to `TEXT[]`; causes silent `MappingException` at persist time → 500 HTML error → JSON.parse failure on frontend
- **Why not**: Driver constraint. `String[]` is the only collection type natively supported for `TEXT[]` columns.

## Consequences

### Positive
- Mention data is durable in PostgreSQL (`TEXT[]` column with GIN index) — available for notifications, analytics, and audit
- Autocomplete works for all historical chatters, not just visible messages
- No custom dropdown code to maintain
- PrimeNG handles keyboard nav (arrow keys, Escape, Tab) natively

### Negative
- p-autocomplete state-snapshot pattern is fragile — depends on PrimeNG's internal event ordering
- Tier 2 API results may not render if p-autocomplete doesn't update the overlay on async `[suggestions]` changes (being investigated)
- `[forceSelection]="false"` means typos in @mentions go through to the backend (acceptable — backend still parses; typos just won't match real users)

### Risks
- **PrimeNG upgrade breaks event ordering** → `mentionJustSelected` guard fails → Enter sends immediately after mention selection. Mitigation: `mentionPanelVisible` fallback; test after every PrimeNG version bump.
- **API suggestions don't render** → users can't discover chatters outside the visible message list. Mitigation: if async updates prove unreliable, switch to blocking API call in `completeMentions` (tradeoff: slower first panel open).

## References
- [ADR-0000: Chat Architecture Foundation](0000-architecture-foundation.md)
- [ADR-0002: JWT-Derived Author Identity](0002-jwt-derived-author-identity.md)
- [IMPLEMENTATION-PLAN.md §Phase 3](../../IMPLEMENTATION-PLAN.md#phase-3--real-time-chat-)
- [Chat Mentions & Emoji Retrospective](../../plans/chat-mentions-emoji-retrospective.md)
- Source: `ChatMessage.java` — `mentions` field + `createSystem()` factory
- Source: `ChatService.java` — `parseMentions()`, `getParticipants()`, `sendSystemMessage()`
- Source: `chat-panel.component.ts` — `completeMentions()`, `onMentionSelect()`, `fetchMentionSuggestions()`, `onInputKeydown()`
- Migration: `V6__add_message_mentions.sql`
