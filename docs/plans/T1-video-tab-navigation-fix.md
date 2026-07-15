# T1: Fix Video Tab Navigation to `/watch/:id`

**Status**: Ready
**Depends on**: None
**Scope**: Frontend only — 1 file

## Problem

In `video-tab.component.html`, clicking any archived broadcast card navigates to `['/channel', session.id]` which goes to the streamer's creator detail page (`StreamDetailPage`). This page shows lifecycle action buttons (End, Archive, Rotate Key, Cancel) that are intended only for the stream owner. While the backend PBAC gates the API calls, displaying these buttons to non-owners is bad UX.

Non-owner viewers should navigate to `/watch/:id` instead — the viewer-facing watch page.

## Current Code

**File**: `main/source/frontend/streaming-ui/src/app/pages/channel/video-tab/video-tab.component.html`

Two locations navigate to the creator view:

1. **Grid view** (filtered/archived broadcasts), line 56:
```html
<a [routerLink]="['/channel', session.id]" ...>
```

2. **Rail view** (recent broadcasts rail), line 151:
```html
<a [routerLink]="['/channel', session.id]" ...>
```

Both use `['/channel', session.id]` which routes to `StreamDetailPage` (the creator management view at path `channel/:id`).

## What to Change

Change both `[routerLink]` values from `['/channel', session.id]` to `['/watch', session.id]`.

### Line 56 (grid view)
```html
<!-- BEFORE -->
<a [routerLink]="['/channel', session.id]" ...>

<!-- AFTER -->
<a [routerLink]="['/watch', session.id]" ...>
```

### Line 151 (rail view)
```html
<!-- BEFORE -->
<a [routerLink]="['/channel', session.id]" ...>

<!-- AFTER -->
<a [routerLink]="['/watch', session.id]" ...>
```

## Verification

1. Navigate to a channel page (`/@someuser/video`)
2. Select "Archived Broadcasts" filter
3. Click on an archived video card
4. **Expected**: Navigates to `/watch/:id` (the viewer watch page)
5. **Expected**: Does NOT show End/Archive/Cancel/Rotate Key buttons

## Agent Execution

```
🤖 Delegating to general-purpose: Fix video-tab navigation links from /channel/:id to /watch/:id in video-tab.component.html
```

### Prompt for agent:

> In the file `main/source/frontend/streaming-ui/src/app/pages/channel/video-tab/video-tab.component.html`, change two `[routerLink]` values from `['/channel', session.id]` to `['/watch', session.id]`:
>
> 1. Line ~56: The `<a>` tag inside the grid view (`@if (showBroadcastGrid())` block)
> 2. Line ~151: The `<a>` tag inside the rail view (`Recent Broadcasts` rail, within `@for (session of broadcasts(); track session.id)` block)
>
> This fixes the issue where clicking an archived broadcast in a channel page navigates to the creator's management view instead of the viewer watch page. Read the file first to confirm the exact lines, then apply both changes.
