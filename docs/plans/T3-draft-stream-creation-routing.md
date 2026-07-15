# T3: Route to Draft Stream Immediately After Creation

**Status**: Ready
**Depends on**: None
**Scope**: Backend (stream-service) + Frontend (Angular) — 5 files

## Problem

When a user creates a stream as "Save Draft," the app navigates to `/home` (which redirects to `/browse`). The user must:
1. Navigate to their dashboard
2. Find the newly created stream
3. Click into its detail page
4. Press "Generate Key" to get the stream key for OBS

The stream key (`srsName`) is generated at creation time and stored in the entity, but it's never returned in the create response. This is the **only time** the plain-text stream key is available (besides key rotation, which generates a new key). The user should be routed directly to the draft stream's detail page with the stream key ready to copy.

## Design Decision

Pass the stream key via **Angular `NavigationExtras.state`** (not URL query params) to keep the key out of the browser URL bar (security). The stream detail page checks for state on init and pre-populates the publish key display.

If the user refreshes the page, the state is lost — but the key is still retrievable (masked) via `GET /v1/streams/{id}/publish-key`, and the user can re-rotate if needed.

## Files to Change

| # | File | Action |
|---|------|--------|
| 1 | `StreamResponse.java` (backend) | Add nullable `streamKey` field |
| 2 | `StreamService.java` (backend) | Populate `streamKey` in create response for DRAFT |
| 3 | `stream-response.dto.ts` (frontend) | Add `streamKey?: string` |
| 4 | `stream-create.page.ts` (frontend) | Navigate to `/channel/:id` with state |
| 5 | `stream-detail.page.ts` (frontend) | Read navigation state, pre-populate key |

### Backend Changes

#### 1. `StreamResponse.java`

Add a nullable `streamKey` field:
```java
public record StreamResponse(
        UUID id,
        String title,
        // ... existing fields ...
        OffsetDateTime endedAt,
        String streamKey        // NEW: null for SCHEDULED streams; plain srsName for DRAFT
) {
    public static StreamResponse from(StreamSessionEntity entity) {
        return new StreamResponse(
                // ... existing mappings ...
                entity.getEndedAt(),
                null  // never populated from entity alone — caller overrides
        );
    }
}
```

#### 2. `StreamService.java` — `createStream()` method (~line 110)

After creating a DRAFT stream, include the `srsName` in the response:
```java
.map(StreamResponse::from)
.map(response -> {
    if (isDraft) {
        // Return the plain stream key only at creation time
        return new StreamResponse(
                response.id(), response.title(), response.description(),
                response.category(), response.categoryId(), response.tags(),
                response.maxViewers(), response.status(),
                response.broadcasterSubject(), response.thumbnailUrl(),
                response.archivedUrl(), response.views(),
                response.createdAt(), response.updatedAt(),
                response.startedAt(), response.scheduledAt(),
                response.endedAt(),
                srsName  // ← the plain stream key
        );
    }
    return response;
});
```

Note: `srsName` is already available in the `Mono.defer` lambda at line 128. For SCHEDULED streams, `srsName` is null (no key issued) — keep `streamKey` null.

### Frontend Changes

#### 3. `stream-response.dto.ts`

```typescript
export interface StreamResponseDto {
  // ... existing fields ...
  readonly endedAt?: string;
  readonly streamKey?: string;  // NEW: only present at creation for DRAFT streams
}
```

#### 4. `stream-create.page.ts` — `onSaveDraft()` method (~line 78)

Change the navigation from:
```typescript
this.router.navigateByUrl('/home');
```
To:
```typescript
this.router.navigate(['/channel', response.id], {
    state: { streamKey: response.streamKey },
});
```

The `response` is the `StreamResponseDto` returned by `this.streamService.create(...)`.

#### 5. `stream-detail.page.ts` — `ngOnInit()` method (~line 123)

After `loadStream()`, check for navigation state:
```typescript
public ngOnInit(): void {
    // Check for stream key passed via navigation state (from stream create)
    const navState = this.router.lastSuccessfulNavigation?.extras?.state as
        { streamKey?: string } | undefined;
    if (navState?.streamKey) {
        // Pre-populate the publish key display with the plain key from creation
        this.publishKey.set({
            streamId: this.id(),
            srsName: navState.streamKey,
            rtmpUrl: '',     // will be computed
            playUrl: '',     // will be computed
            token: navState.streamKey,  // the plain key acts as token for display
            expiresAt: undefined,
        } as PublishKeyResponseDto);
        this.tokenRevealed.set(true);  // show the key immediately
    }
    this.loadStream();
}
```

Note: Import `Router` if not already imported (it already is at line 13).

##### Clarification on `rtmpUrl` / `playUrl`

The `rtmpServerUrl()` and `streamKey()` computed values in `stream-detail.page.ts` derive from the `PublishKeyResponseDto`. Since we only have `srsName` (not the full URL), the computed values will need the host. Use the existing `publishTokenProps`-derived URLs from the backend. Alternatively, also pass `rtmpUrl` and `playUrl` in the creation response.

**Better approach**: Include the RTMP URL and play URL in `StreamResponse` alongside `streamKey`, so the frontend has everything:
```java
// StreamResponse additions:
String streamKey,       // srsName (plain)
String streamKeyRtmpUrl, // full RTMP URL with token embedded
String streamKeyPlayUrl  // full HLS play URL
```

But this makes `StreamResponse` complex. **Simpler**: Keep only `streamKey` (srsName) and compute URLs on the frontend. The frontend already knows how from the `PublishKeyResponseDto` pattern. Wait — the frontend does NOT know the host. The host comes from the backend.

**Final decision**: Pass `streamKey` (srsName) only. The detail page already calls `getPublishKey()` which returns masked data. The `streamKey` from navigation state supplements this. Show:
- Server: can't compute without host → show "Configure in OBS" placeholder
- Stream Key: `{srsName}?token={srsName}` (using srsName as token placeholder until rotated)
- User can press "Generate Key" to get a proper publish JWT

Actually, the simplest UX: show the stream key as plain text (the srsName IS effectively the stream key part), and tell them to generate a proper key for OBS. OR — even better — auto-generate a publish key immediately after creation so they have a ready-to-copy stream key.

**Revised approach**: After `createStream()` succeeds in `stream-create.page.ts`, immediately call `issuePublishKey(response.id)` and pass the full `PublishKeyResponseDto` via navigation state. This gives the user a ready-to-use OBS key.

```typescript
// stream-create.page.ts — onSaveDraft
next: (response) => {
    this.loading.set(false);
    // Immediately issue a publish key so the user has a ready-to-copy stream key
    this.streamService.issuePublishKey(response.id).subscribe({
        next: (key) => {
            this.router.navigate(['/channel', response.id], {
                state: { publishKey: key },
            });
        },
        error: () => {
            // Fallback: navigate without key — user can generate manually
            this.router.navigate(['/channel', response.id]);
        },
    });
},
```

Then in `stream-detail.page.ts`:
```typescript
const navState = this.router.lastSuccessfulNavigation?.extras?.state as
    { publishKey?: PublishKeyResponseDto } | undefined;
if (navState?.publishKey) {
    this.publishKey.set(navState.publishKey);
    this.tokenRevealed.set(true);
}
```

This approach keeps the backend changes minimal (no new fields in StreamResponse) and gives the best UX — the user lands on the stream detail page with the key already generated and revealed.

Actually wait — `createStream` already generates an `srsName` AND hashes it for DRAFT. The plain srsName is effectively the stream key. But OBS needs the full RTMP URL (`rtmp://host/live/{srsName}?token={jwt}`). The publish JWT is generated by `PublishTokenService.issueToken()`.

For the *very first* key, we could either:
1. Call `issuePublishKey()` after create (separate API call, as shown above)
2. Have `createStream()` return a publish key inline when creating DRAFT

Option 2 is actually more RESTful — one round trip:
```java
// StreamService.createStream() — when DRAFT, return srsName + issue initial token
if (request.scheduledAt() == null) {
    // DRAFT: generate srsName + initial publish token
    String srsName = newSrsName();
    String token = publishTokenService.issueToken(id, srsName, sub);
    // ... store hash, set srsName ...
    // Return response with streamKey info
}
```

But `createStream` currently doesn't have the `publishTokenService` wired for creating tokens inline. And `StreamResponse` is a pure entity projection.

**Final, cleanest approach**: Keep `createStream()` as-is. In the Angular `stream-create.page.ts`, after create succeeds, chain a call to `issuePublishKey(response.id)`. On success, navigate with the key data. On failure, navigate without (user can generate manually). This adds at most one extra HTTP call and keeps backend changes minimal (zero changes).

## Verification

1. Go to `/streams/create`
2. Fill in title, leave `scheduledAt` empty (draft mode)
3. Click "Save Draft"
4. **Expected**: Navigate to `/channel/:newStreamId`
5. **Expected**: Stream key panel is populated and revealed (token shown)
6. **Expected**: Can copy Server URL and Stream Key immediately
7. Refresh the page → key is masked (normal behavior, can re-reveal or rotate)

## Agent Execution

```
🤖 Delegating to general-purpose: Modify stream-create.page.ts to issue publish key after creation and navigate with state. Modify stream-detail.page.ts to read navigation state.
```
