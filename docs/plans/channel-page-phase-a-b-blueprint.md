# Blueprint: `/@username` Channel Page — Phase A (Identity + Authenticated Read) & Phase B (Channel Shell)

**Status:** Ready for review
**Date:** 2026-07-09
**Decisions locked:** route `/@username`; denormalize username into stream-service; **authenticated-only** (no guest access this round); login rate-limiter + uniform-401 kept this round (auth/0003); social/playlist features are **shells** this round.
**ADRs:** [auth/0002](../adr/auth/0002-public-username-handle-and-login-hardening.md), [auth/0003](../adr/auth/0003-login-rate-limiting.md), [stream/0007](../adr/stream/0007-public-channel-read-and-channel-service-seam.md)
**Authoritative scope:** [channel-page-scope-review.md](channel-page-scope-review.md) holds the vision, the full feature→scope map, and the execution order. This blueprint has been reconciled to match it (authenticated-only: no gateway allowlist, no permitAll, safe projection guards cross-user leakage).

---

## Phase A — Identity, Security, Authenticated Read (backend-first)

### A1. auth-service: `username` in the JWT `attr` claim

Catalog-driven, so it's a seed row + a resolver case + a DTO field.

1. **Flyway `V9__seed_username_subject_attribute.sql`** (auth-service, schema `auth`, next free = V9):
   ```sql
   INSERT INTO auth.catalog_subject_attribute (attribute_key, json_value_kind, description) VALUES
     ('username', 'STRING', 'Public channel handle; mutable convenience claim (identity authority remains sub/UUID).');
   ```
2. **`SubjectAttributeResolver.java`** — add `KEY_USERNAME = "username"`, a `resolveUsername(user)` returning `user.getUsername()` (never null per schema), and a `case KEY_USERNAME -> resolveUsername(user)` in the switch.
3. **`SubjectAttributes.java`** — add `@JsonProperty("username") String username` to the record (it's `@JsonInclude(NON_NULL)`, ordering after `verified_streamer` is fine).
4. No change needed in `AccessTokenIssuanceService` — it already spreads the resolved `attr` map into the token.

**Verify:** log in, decode the JWT, confirm `attr.username` is present.

### A2. auth-service: login hardening (independent auth-hardening step — not a gate)

> Kept this round by decision (auth/0003), but **decoupled** from the channel feature — it is
> no longer a prerequisite because channels are authenticated-only. Ship it as its own step.

Reuse the established Redis-Lua pattern from `RefreshTokenRedisService` (`RedisTemplate<String,String>` + classpath Lua via `DefaultRedisScript`).

1. **`src/main/resources/redis/login_rate_limit.lua`** — atomic sliding-window/fixed-window counter: `INCR` key, set `EXPIRE` on first hit, return count. Keys: `login_attempts:user:<username>` and `login_attempts:ip:<ip>`.
2. **`LoginRateLimitService.java`** (`infrastructure/redis/`) — constructor-injected `RedisTemplate`; `checkAndIncrement(username, ip)` executes the Lua for both keys, throws a new `TooManyLoginAttemptsException` (`@ResponseStatus(TOO_MANY_REQUESTS)` = 429) when either exceeds threshold. Thresholds via `@ConfigurationProperties` (e.g. `auth.login.max-attempts`, `auth.login.window-seconds`) — no magic numbers.
   - **Anti-DoS:** prefer graduated slowdown + IP-scoped lockout over hard per-username lockout (per ADR-0002 risk note). Reset the username counter on successful auth.
3. **`AuthController` / `AuthService.authenticate`** — call the rate-limit check **before** the DB lookup; needs the client IP (inject `HttpServletRequest`, honour `X-Forwarded-For` from the gateway).
4. **Uniform errors** — collapse the enumeration oracle: make unknown-user and bad-password return the **same** status + body. Simplest: in `authenticate`, throw `InvalidCredentialsException` (401, "Invalid user credentials") for **both** the empty-`Optional` and the password-mismatch case, instead of `UserAccountNotFoundException` (404). Keep `UserAccountNotFoundException` for other internal callers if any (grep first).

**Verify:** N failed logins → 429; unknown user and wrong password return identical 401 body; successful login resets the counter.

### A3. stream-service: denormalize broadcaster identity

1. **Flyway `V7__add_broadcaster_identity.sql`** (schema `stream`, next free = V7):
   ```sql
   ALTER TABLE stream.stream_session
     ADD COLUMN broadcaster_username VARCHAR(128),
     ADD COLUMN broadcaster_verified BOOLEAN;
   -- Backfill: existing rows have no resolvable handle → left NULL, rendered with placeholder.
   ```
2. **`StreamSessionEntity.java`** — add `@Column("broadcaster_username") String broadcasterUsername` and `@Column("broadcaster_verified") Boolean broadcasterVerified`.
3. **`StreamService.createStream`** — read the nested `attr` map (net-new in this service):
   ```java
   Map<String,Object> attr = jwt.getClaimAsMap("attr"); // may be null → guard
   String username = attr == null ? null : (String) attr.get("username");
   Boolean verified = attr == null ? null : (Boolean) attr.get("verified_streamer");
   ```
   Change `buildEntity(...)` signature to accept `username`/`verified` (currently takes only `sub`) and set them. **Client-read-only** — never from the request body (mirrors `thumbnailUrl`).
4. Consider a tiny `JwtAttr` helper (static) so the map-casting isn't inlined — future channel-service will want it too.

**Verify:** create a stream, inspect the row — `broadcaster_username` / `broadcaster_verified` populated from the token.

### A4. stream-service: authenticated read endpoint `GET /v1/channels/{username}`

> **Authenticated-only.** Any logged-in user can view any channel; there is **no** guest
> access, **no** `permitAll` matcher, and **no** gateway allowlist. The endpoint stays under
> the service's existing `anyExchange().authenticated()`. The critical property is the *safe
> cross-user projection* — user A must never receive user B's subject / publish key / RTMP.

1. **`ChannelResponse.java`** (`api/dto/`) — safe cross-user projection. Include: `username`, `verified`, `sessions` (list of the existing `StreamSummaryResponse`), `recentCategories` (list of category names). **Exclude, enforced server-side:** `broadcasterSubject`, publish key, `rtmpUrl`, lifecycle affordances. Static `from(...)`.
2. **`StreamSessionRepository`** — add `Flux<StreamSessionEntity> findAllByBroadcasterUsernameOrderByCreatedAtDesc(String username)` (or a limited variant for the rail).
3. **`StreamService.getChannel(username)`** — assemble the projection: sessions (rail, cap ~15), distinct recent categories, identity (username + verified from the newest session **that has a non-null username** — older backfill-gap rows may be NULL). Return empty-but-200 for a known handle with no sessions; 404 only if the handle is entirely unknown. **Open decision — pick and document at implementation time.**
4. **`StreamController`** — `@GetMapping("/channels/{username}")`. It still takes the request principal like every other authenticated endpoint; it simply does not *owner-scope* the query (any authed caller may read any handle). Owner-vs-visitor affordances are decided client-side (B3), never trusted server-side for control surfaces.
5. **`SecurityConfig` — no change.** Do **not** add a `permitAll` matcher; the default `authenticated()` is correct.
6. **Gateway — no change.** The gateway keeps requiring a valid token for `/api/streams/**`, which is what we want.

**Verify:** call `GET /api/streams/v1/channels/{username}` **with** a valid token as a *different* user → 200 with the safe projection; assert the body contains **no** `broadcasterSubject` / publish key / `rtmpUrl` (the primary correctness test). Call it **without** a token → 401 (authenticated-only is working).

---

## Phase B — Channel Page Shell (Angular)

### B1. Contracts + auth service

1. **`token-payload.dto.ts`** — add `readonly username: string;` to `TokenAttrDto`.
2. **`auth.service.ts`** — mirror the `_roles` pattern: `_username` signal initialized via a static `parseUsername` (reads `parseJwtPayload(token)?.attr?.username`), exposed as `myUsername = this._username.asReadonly()`, set in `persistSession` and cleared in `logout`.
3. **`channel-response.dto.ts`** (new) — matches `ChannelResponse` (`username`, `verified`, `sessions: StreamSummaryResponseDto[]`, `recentCategories: string[]`).
4. **`stream.service.ts`** — add `getChannel(username)` → GET `${base}/channels/${username}`.

### B2. Route

- Add a route for `/@:username`. Angular can't put `@` in a path segment token directly, so use `path: '@:username'` (literal `@` prefix + `:username` param) → `ChannelPage`. Place it **inside the `authGuard` shell** (like the rest of the app) — channels are authenticated-only, so it renders with the normal header/nav for logged-in users. Keep the existing guarded `channel/:id` owner control panel untouched.
- `withComponentInputBinding()` is already on, so `:username` binds to `input.required<string>()`.

### B3. Components (atomic design, reuse the existing stage)

New `pages/channel/channel.page.ts` (owner mode = `authService.myUsername() === username()`), composing:
- **`channel-header` organism** — full-width, ≥40% top padding so the stage shows behind. Left: avatar + (name + **verified check**, real from `verified`) + follower count. Right: Follow / Subscribe / Gift buttons — **disabled placeholders**; username (smaller) + subscriber/video counts — **placeholder**; Subscribe button bottom.
- **Background stage** — reuse `StreamStageComponent` full-height (`h-full`) with the current/latest session behind header+body.
- **Body = `p-tabs`** (lazy, `@if (selectedTab()==='...')` per existing dashboard pattern). **Home** tab:
  - **`session-rail` organism** — horizontal, latest ~15 sessions; drag-to-scroll + chevron float buttons. New `useDragScroll` behaviour; compositor-friendly `transform` only (per web/performance rules).
  - **playlists rail** — empty "No playlists yet" shell (no backend).
  - **category strip** — from `recentCategories` (real).
- **`format-count` util** (`lib/`) — K/M/B number formatting for follower/subscriber counts.

### B4. Header entry point

- `app-shell.component.ts`: point "Channel" at the user's own `/@<myUsername>()` instead of `/dashboard/streams`. Retire or redirect `dashboard/streams` → `/@me`-style once the channel page covers it (keep the owner control panel reachable from the channel page in owner mode).

**Verify:** logged in, visit `/@<someuser>` → header + verified badge + session rail render from real data; social/playlist controls visibly disabled; owner sees manage affordances (visiting own handle); K/M formatting correct; drag + chevron scroll work; no console errors; responsive at 375/768/1440. Visiting `/@<user>` while logged out → redirected to login (authenticated-only).

---

## Out of scope (later phases, per ADRs)
- Real followers / subscriptions / gifting → **channel-service** extraction (stream/0007).
- Playlist domain + wiring the rail.
- Email-OR-username login, distinct public handle/slug, MFA, HIBP (auth/0002 deferred list).
- Rename-drift reconciliation (`UserRenamed` event / reconcile job) — lands with channel-service.

## Execution order

See the **9-commit feature-by-feature order in [channel-page-scope-review.md §"Proposed execution order"](channel-page-scope-review.md)** — that is the single source of truth. Summary: A1 (username claim) → uniform-401 → rate limiter → A3 (denormalize) → A4 (authenticated read) → B1 (contracts) → B2 (route) → B3 (organisms) → B4 (header entry). Each step is independently verifiable. Nothing in Phase A gates Phase B now that channels are authenticated-only.

## Risk callouts
1. **Cross-user field leakage (A4.1/A4.3)** — the projection must exclude another user's `broadcasterSubject` / publish key / RTMP. This is the endpoint's primary correctness risk; test it explicitly (call another user's channel, assert absence).
2. **`attr` map casting (A3.3)** — net-new in stream-service; guard for null `attr` / missing keys (tokens issued before A1 have no `username`).
3. **Backfill gap (A3.1 / A4.3)** — pre-existing sessions have NULL `broadcaster_username`; the identity resolution and rail must tolerate NULL and fall back to a placeholder handle.
4. **DoS via lockout (A2)** — favour IP-scoped / graduated throttling over hard per-username lockout.
