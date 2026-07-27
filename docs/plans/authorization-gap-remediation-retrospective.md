# Authorization Gap Remediation — Implementation Retrospective

**Date:** 2026-07-27
**Status:** Complete — 7 gaps closed across 5 services

## 1. What was implemented (vs the original plan)

| Planned item | Commit(s) | Notes |
|---|---|---|
| C1 — SRS on_unpublish token validation | `4eeb252` | Added `validateForUnpublish()` to `PublishTokenService`; controller now extracts + validates token before ending stream |
| C2 — Chat PBAC hard-fail guard | `793e5c1` | Changed default `CHAT_PBAC_ENABLED:true`; `@PostConstruct enforceOrWarn()` throws `IllegalStateException` in non-dev profiles |
| H1 — Watch endpoint PBAC | `4eeb252` | `getWatchData()` now enforces `stream:session read` via PBAC; mirrors `getStream()` pattern exactly |
| H2 — 403 handler for PBAC denials | `4eeb252` | `StreamAccessDeniedException` now returns 403 instead of falling through to generic 500 handler |
| H3 — Chat participants PBAC | `0e50021` | `getParticipants()` now enforces `chat:room read` via `authorizeRead()`; same pattern as every other read method |
| H4/H5 — Internal token audit logging | `20685e9` | Both `/v1/internal/access-tokens` and `/v1/internal/service-tokens` now log caller identity |
| M2 — Heartbeat LIVE guard | `4eeb252` | `sendHeartbeat()` silently ignores non-LIVE streams (defense-in-depth) |
| M5 — Gateway webhook POST-only | `68bcf6d` | Webhook public matcher scoped to `HttpMethod.POST` only |

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking doc | Reason |
|------|-------------|--------------|--------|
| M4 — Gateway `ent` enforcement | Future phase | `IMPLEMENTATION-PLAN.md` | Needs architectural design discussion |
| M6 — Auth no user-scoped endpoints | Feature work | — | Feature gap, not a bug; add when profile management features are built |
| M7 — Notification fetch-then-compare | Future hardening | `notification/.../PreferenceService.java`, `SubscriptionService.java` | Requires new repository query methods (`findByIdAndSubscriberSubject`) |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

| Item | Context | Recommended action |
|------|---------|-------------------|
| M1 — Profile PBAC not applicable | `BroadcasterProfileEntity` has no `broadcasterSubject` field — keyed by `username` only. The manual `jwtUsername.equals(username)` comparison IS the correct ownership check for this entity model. | When profile management features are built, consider adding a `broadcasterSubject` field to `BroadcasterProfileEntity` so PBAC can replace the username comparison. Not worth a migration now. |

## 4. Architectural decisions made during implementation (candidates for new ADRs)

No new architectural decisions — all changes followed existing patterns:

1. **on_unpublish token validation** follows the same `validateForPublish` pattern with one difference: expiry is skipped. Documented in ADR-0004 (SRS Webhook) update.
2. **Chat PBAC hard-fail** uses `@Autowired(required = false) Environment` + profile check — a standard Spring Boot pattern. Documented in ADR-0004 (Chat Authorization) update.
3. **Access-denied → 404 mapping** on watch endpoint mirrors the existing `getStream()` pattern exactly.

## 5. Documents to update (stale vs current state)

| Document | What was stale | Action taken |
|----------|---------------|-------------|
| `docs/adr/stream/0004-srs-webhook-publish-token.md` | `on_unpublish` flow showed no token validation | ✅ Updated — added token validation step to the unpublish flow, updated Consequences and References |
| `docs/adr/chat/0004-two-layer-chat-authorization.md` | Dark-launch language ("ships dark", "default false", "inert until flag flips") | ✅ Updated — changed to enabled-by-default, added hard-fail guard description, updated Alternative 3 resolution |
| `docs/IMPLEMENTATION-PLAN.md` | Phase 6.2 unchecked; no authorization gap work item | ✅ Updated — marked 6.2 complete, added 6.2a work item, updated header and checklist |

## 6. Updated execution order (actual vs planned)

| Planned | Actual commit | Status |
|---------|--------------|--------|
| C1 (stream on_unpublish) | `4eeb252` | ✅ |
| C2 (chat PBAC guard) | `793e5c1` | ✅ |
| H1 (watch PBAC) | `4eeb252` (bundled with C1+M2) | ✅ |
| H2 (403 handler) | `4eeb252` (bundled) | ✅ |
| H3 (participants PBAC) | `0e50021` | ✅ |
| H4/H5 (audit logging) | `20685e9` | ✅ |
| M2 (heartbeat guard) | `4eeb252` (bundled) | ✅ |
| M5 (gateway scoping) | `68bcf6d` | ✅ |
| M1 (profile PBAC) | — | Deferred (not applicable — entity has no subject field) |
| M3 (watch history) | — | Already fine (uses JWT subject, records own history only) |
| M7 (notification scoping) | — | Deferred (needs new repository methods) |

**Grouping decisions:** The stream-service changes (C1 + H1 + H2 + M2) were committed together because they overlap in `StreamService.java`. Chat C2 and H3 were kept separate because they touch different files with different reasons. Telemetry was isolated per project rules.

## 7. Key risks carried forward

1. **Chat PBAC with no dev profile**: Running locally without `-Dspring.profiles.active=dev` and without `CHAT_PBAC_ENABLED=true` will cause a hard startup failure. Mitigation: documented in `ChatPbacProperties.java` Javadoc and `application.yml` comments.
2. **on_unpublish token requirement**: If SRS delivers `on_unpublish` without a publish token (e.g., RTMP connection dropped before token was parsed), the webhook returns 403 and the stream stays in LIVE state. Mitigation: a follow-up heartbeat-based liveness check could auto-end streams with no active broadcaster presence.
3. **Pre-existing test failures**: `StreamServiceTest.java` has 7 pre-existing compilation errors (`CreateStreamRequest`/`UpdateStreamRequest` constructor mismatches) unrelated to these changes. Not addressed here — tracked separately.
