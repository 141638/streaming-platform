---
name: primeng-autocomplete-state-snapshot
description: "PrimeNG p-autocomplete overwrites the form control BEFORE (onSelect) — save original text in completeMethod, reconstruct in onSelect."
user-invocable: false
origin: auto-extracted
---

# PrimeNG p-autocomplete: Save State Before Selection (Inline Mentions Pattern)

**Extracted:** 2026-07-14
**Context:** Angular 19 + PrimeNG 19.1.4 `p-autocomplete` used for inline @mentions where `@query` is embedded in surrounding text (e.g., `"hello @al"`).

## Problem

p-autocomplete replaces the **entire** form control value with the selected suggestion **before** `(onSelect)` fires. For inline mentions — where the @trigger is embedded in surrounding text — the rest of the message is destroyed.

```
User types:  "hello @al"
p-autocomplete selects "alice"
Form control becomes: "alice"       ← "hello " is GONE
(onSelect) fires with value "alice" ← too late — context already lost
```

`detectMention("alice", ...)` returns `null` because there's no `@` in `"alice"`. The mention silently fails.

## Solution

Capture the full input text and @cursor position in `completeMethod` (before p-autocomplete overwrites), then reconstruct from the snapshot in `onSelect`.

```typescript
// In completeMethod — called BEFORE p-autocomplete overwrites the form control
private savedMentionStart: number | null = null;
private savedOriginalQuery: string | null = null;

protected completeMentions(event: AutoCompleteCompleteEvent): void {
  const mention = this.detectMention(event.query, event.query.length);
  if (mention !== null) {
    // SNAPSHOT: save the original text and @ position
    this.savedMentionStart = mention.start;
    this.savedOriginalQuery = event.query;
    this.fetchMentionSuggestions(mention.query);
  } else {
    this.savedMentionStart = null;
    this.savedOriginalQuery = null;
    this.mentionSuggestions.set([]);
  }
}

// In onSelect — called AFTER p-autocomplete has overwritten the form control
protected onMentionSelect(event: AutoCompleteSelectEvent): void {
  const selected = event.value as string;
  const start = this.savedMentionStart;
  const original = this.savedOriginalQuery;
  if (start === null || original === null) return;

  // Reconstruct from snapshot
  const before = original.slice(0, start);
  const afterAt = original.slice(start + 1);         // text after @
  const spaceOrEnd = afterAt.search(/[\s]|$/);
  const queryLen = spaceOrEnd === -1 ? afterAt.length : spaceOrEnd;
  const after = original.slice(start + 1 + queryLen);

  this.messageInput.setValue(before + '@' + selected + ' ' + after);
  this.mentionSuggestions.set([]);
  this.savedMentionStart = null;
  this.savedOriginalQuery = null;

  // Restore cursor after the inserted mention
  const input = document.getElementById('chat-message-input') as HTMLInputElement | null;
  const newPos = start + selected.length + 2; // @name + trailing space
  requestAnimationFrame(() => {
    input?.setSelectionRange(newPos, newPos);
    input?.focus();
  });
}
```

### Required p-autocomplete settings

```html
<p-autocomplete
  [formControl]="messageInput"
  [suggestions]="mentionSuggestions()"
  (completeMethod)="completeMentions($event)"
  (onSelect)="onMentionSelect($event)"
  [forceSelection]="false"   <!-- allow typing usernames not in suggestion list -->
  [dropdown]="false"         <!-- no dropdown button — suggestions triggered by @ -->
  [minLength]="1"            <!-- @mention can start at cursor position 0 -->
  inputId="chat-message-input"
/>
```

### detectMention helper

```typescript
private detectMention(
  text: string,
  cursorPos: number,
): { query: string; start: number } | null {
  const before = text.slice(0, cursorPos);
  const atIndex = before.lastIndexOf('@');
  if (atIndex === -1) return null;
  // @ must be preceded by whitespace or start-of-string (not mid-word like email@x.com)
  const charBeforeAt = atIndex > 0 ? before[atIndex - 1] : ' ';
  if (!/[\s]/.test(charBeforeAt)) return null;
  const query = before.slice(atIndex + 1);
  if (query.includes(' ') || query.length > 32) return null;
  return { query, start: atIndex };
}
```

## Additional Gotcha: Enter-Lock After Selection

p-autocomplete's internal keydown handler fires on the `<input>` BEFORE the event bubbles to the host `(keydown)` binding. After `onMentionSelect` clears `mentionSuggestions`, the host handler sees an empty list and sends the message immediately after the mention was inserted.

**Fix:** Set a guard flag in `onMentionSelect`, auto-clear via microtask:

```typescript
private mentionJustSelected = false;

// In onMentionSelect:
this.mentionJustSelected = true;
setTimeout(() => { this.mentionJustSelected = false; }, 0);

// In onInputKeydown (host binding):
protected onInputKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey) return;
  if (this.mentionJustSelected) {
    this.mentionJustSelected = false;
    event.preventDefault();
    return;  // p-autocomplete just selected a mention — don't send
  }
  if (this.mentionPanelVisible()) return;  // panel still open — let p-autocomplete handle
  event.preventDefault();
  this.send();
}
```

## When to Use

- Any PrimeNG `p-autocomplete` used for inline mentions, tags, or patterns where the selected value is embedded in surrounding text (not the entire input value)
- **Symptom:** `@` is removed from the string when pressing Tab or Enter to choose a suggestion
- **Symptom:** `detectMention` returns null in `onSelect` even though it worked in `completeMethod`
- **Not needed for:** Dropdown-style autocomplete where the entire input IS the selected value (standard search-box pattern)
