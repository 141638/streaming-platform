# Chat Mentions & Emoji — Implementation Retrospective

**Date:** 2026-07-14
**Status:** Complete (2 known issues remain: Enter-lock + API suggestions render)

## 1. What was implemented (vs the original plan)

| Planned item | Commit(s) | Notes |
|-------------|-----------|-------|
| @mentions — schema + backend parsing | Uncommitted (V6 migration) | `TEXT[]` column on `chat_message`, regex `(?<!\w)@(\w{1,32})`, distinct author query for autocomplete |
| @mentions — frontend autocomplete | Uncommitted | Replaced custom dropdown with PrimeNG `p-autocomplete`; three-tier suggestion (local chatters → API participants → exact-match fallback) |
| Emoji input | Uncommitted | Replaced custom popover with PrimeNG `p-overlayPanel`; 50-emoji curated set; cursor-position save/restore via `captureCursor()` |
| System messages (producer) | Uncommitted | `ChatMessage.createSystem()` factory, `ChatService.sendSystemMessage()`, `StreamControlListener` wired for STREAM_CREATED / STREAM_ENDED |
| R2DBC type mapping fix | Uncommitted | `Set<String>` → `String[]` for PostgreSQL `TEXT[]` column; driver natively maps `String[]` but not `Set<String>` |
| p-autocomplete @-removal fix | Uncommitted | Snapshot `savedMentionStart` + `savedOriginalQuery` in `completeMentions` before p-autocomplete overwrites form control on Tab/Enter |
| Enter-lock when mention panel open | Uncommitted (in progress) | `mentionJustSelected` guard + `mentionPanelVisible` signal; prevents send on Enter when mention was just selected |
| API suggestions stale-response fix | Uncommitted (in progress) | `mentionRequestId` monotonic counter; only latest API response applied |
| ChatPanelComponent spec | Uncommitted (new file) | Basic creation + guards |
| ChatService spec | Uncommitted (new file) | HTTP method verification |

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking doc | Reason |
|------|-----------|-------------|--------|
| Notification actions for mentions | Phase 6 | Code comments in `ChatService.persistAndCache()` + `ChatMessage.mentions` | Needs SSE presence + notification-service foundation |
| Email digest for offline mentions | Phase 6 | Same code comments | Needs SMTP + batch-window infra |
| Mention user-subject resolution | Phase 6 | Same code comments | Needs auth-service lookup or local denormalization table |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

| Item | Context | Recommended action |
|------|---------|-------------------|
| ADR-0009 (mention precision) | Referenced in `ChatMessage.java` Javadoc but ADR file doesn't exist. Decision: backend parses @mentions regardless, but frontend gates on autocomplete selection for precision. | Write ADR-0009 |
| `[forceSelection]="false"` on p-autocomplete | Needed so users can type @mentions for users not in the suggestion list (exact-match fallback). Tradeoff: typos also go through. | Document in ADR-0009 |
| `[minLength]="1"` on p-autocomplete | Triggers completeMethod on every character, which is needed because @mention can start at any cursor position. Higher values would miss `@a` after typed text. | Note in component Javadoc |
| mentionSuggestions signal vs p-autocomplete async updates | p-autocomplete may not re-render the overlay panel when `[suggestions]` input changes asynchronously outside the `completeMethod` cycle. The `mentionRequestId` pattern helps but may not fully resolve. | Monitor; if the panel still doesn't update, switch to all-synchronous suggestions in completeMethod |

## 4. Architectural decisions made during implementation (candidates for new ADRs)

1. **p-autocomplete state-snapshot pattern** — p-autocomplete overwrites the entire form control value with the selected suggestion BEFORE `(onSelect)` fires. The fix: capture `savedMentionStart` + `savedOriginalQuery` in `completeMentions`, then reconstruct the full text in `onMentionSelect`. This is a PrimeNG architectural quirk that every consumer of p-autocomplete for inline mentions must handle. **Recommendation:** Pattern doc or ADR note.

2. **R2DBC PostgreSQL TEXT[] ↔ Java String[]** — The R2DBC PostgreSQL driver natively maps `String[]` to `TEXT[]` via `PostgresDialect`, but NOT `Set<String>` or `List<String>`. Attempting `Set<String>` causes a silent `MappingException` → 500 HTML error → `JSON.parse` fails on the frontend. The fix: entity uses `String[]`, API DTO uses `List<String>`, bridge at service boundary via `Arrays.asList()` / `.toArray(new String[0])`. **Recommendation:** Pattern doc (not ADR — it's a driver constraint, not an architectural choice).

3. **Backend as mention authority** — Backend parses @mentions with regex regardless of frontend behavior. The frontend autocomplete is UX-only; the server is the authoritative source of who was mentioned. This matches Slack/Discord/GitHub patterns. The "precision gate" (only count as mention if selected from dropdown) is described as ADR-0009. **Recommendation:** Write ADR-0009.

4. **Three-tier mention suggestion system** — Tier 1: local chatters from visible messages (instant, `computed` signal). Tier 2: API participants from database (async, merged on response). Tier 3: exact-match fallback row ("Add @user") when no matches. **Recommendation:** Document in ADR-0009.

5. **`mentionJustSelected` guard flag** — When Enter is pressed with the mention panel open, p-autocomplete's internal keydown handler on the `<input>` fires BEFORE the event bubbles to our `(keydown)` host binding. By the time our handler runs, `onMentionSelect` has already cleared `mentionSuggestions`, so the old `mentionSuggestions().length === 0` check incorrectly allows the send. The fix: `mentionJustSelected` flag set in `onMentionSelect`, auto-cleared via `setTimeout(0)`, checked first in `onInputKeydown`. **Recommendation:** Note in ADR-0009 or pattern doc.

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action |
|----------|---------------|-------------|--------|
| `docs/IMPLEMENTATION-PLAN.md` §3.3 table (line ~947-952) | Lists @mentions, emoji, system messages as deferred | All three are now implemented | Update table rows to ✅ Done |
| `docs/IMPLEMENTATION-PLAN.md` scaffold tree (lines 589-651) | Missing V6 migration, `ChatServiceSystemMessageTest`, `ChatMessageTest`, `chat-panel.component.spec.ts`, `chat.service.spec.ts` | New files not listed | Add new files |
| `docs/IMPLEMENTATION-PLAN.md` ADR table (lines 575-585) | Lists ADRs 0000–0008 | ADR-0009 (mention precision) referenced in code but not listed | Add ADR-0009 row |
| `docs/adr/chat/` | ADRs 0000–0008 exist | No ADR-0009 for mention precision | Write ADR-0009 |
| Phase 3 checklist (line 878-886) | All checked | Doesn't mention mention/emoji/system-message completion | Update to reflect expanded scope |

## 6. Updated execution order (actual vs planned)

All items in this session were built on top of the existing Phase 3 foundation. The original plan had these as "Later" or "3.4 or later" — they were pulled forward into the current session.

| Planned | Actual | Status |
|---------|--------|--------|
| @mentions: "Later" | Built 2026-07-14 (schema V6, backend parsing, p-autocomplete UI) | ✅ Done |
| Emoji input: "Later" | Built 2026-07-14 (p-overlayPanel, EMOJI_LIST) | ✅ Done |
| System messages: "3.4 or later" | Built 2026-07-14 (createSystem, sendSystemMessage, listener wiring) | ✅ Done |
| Enter-lock + API render fixes | Not planned | Built 2026-07-14 (in progress at session end) | ⚡ In progress |

## 7. Key risks carried forward

1. **p-autocomplete async suggestions rendering** — The API-enriched suggestions may not show in the overlay panel if p-autocomplete doesn't re-render on async `[suggestions]` changes. Mitigation: `mentionRequestId` pattern prevents stale data; if the panel still doesn't update, switch to blocking API call in `completeMentions` (tradeoff: slower first panel open).

2. **Enter-lock race condition** — The `mentionJustSelected` flag depends on event ordering (p-autocomplete's internal handler fires before our host binding). If PrimeNG changes this ordering in a future version, the guard breaks. Mitigation: `mentionPanelVisible` signal from `(onShow)`/`(onHide)` as fallback; test after every PrimeNG upgrade.

3. **No Docker host for integration tests** — V6 migration runs at startup (Flyway), but the Testcontainers test suite can't be executed. Mitigation: compile-only gates (`compileJava`, `compileTestJava`); Docker-gated tests listed in deferred validation section.

4. **Denormalized `author_username` staleness** — Username changes are not propagated to existing `chat_message.author_username` or `mentions` array. Accepted tradeoff per ADR-0008: subject is the identity; username is display-only.
