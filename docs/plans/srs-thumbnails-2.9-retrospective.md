# SRS Snapshot Thumbnails (2.9) — Implementation Retrospective

**Date:** 2026-07-15
**Status:** Complete — SRS thumbnail infrastructure shipped; frontend `onerror` handler and watch.page.ts changes deferred to chat team

## 1. What was implemented (vs the original plan)

| Planned item | Files | Notes |
|-------------|-------|-------|
| SRS ffmpeg watchdog | `main/docker/srs/scripts/thumbnail-watchdog.sh` (new) | Polls SRS API every 30s, grabs 1 frame per active stream via bundled ffmpeg at `/usr/local/srs/objs/ffmpeg/bin/ffmpeg`. Writes to `srs_hls_data` volume → served by SRS HTTP server |
| SRS entrypoint wrapper | `main/docker/srs/scripts/entrypoint.sh` (new) | Starts SRS in background, then watchdog, waits on SRS PID. Trap forwards SIGTERM to both processes |
| Compose volume + command | `compose.yaml`, `main/docker/srs/docker-compose.yml` | Mount `./scripts:/usr/local/srs/scripts:ro`; command changed to `entrypoint.sh` |
| Thumbnail URL persistence | `StreamService.java` (modified) | `handlePublish()` sets `thumbnailUrl = srsHlsHost + "/thumbnails/" + srsName + ".jpg"` after `goLive()`, before `repository.save()` |
| Deterministic thumbnail URL | Architecture decision | URL is predictable from `srsName` — no callback from SRS needed. First frame appears within ~30s |

### Bonus fixes (not in the original plan)

| Item | Files | Notes |
|------|-------|-------|
| SRS host URL construction | `application.yml` (modified) | Changed `srs-hls-host` and `srs-rtmp-host` to use nested placeholder resolution (`${SRS_HLS_HOST:http://${SRS_HOST:localhost}:${SRS_HTTP_PORT:8085}}`). Ensures fully-qualified absolute URLs always — fixes the relative-path bug where the browser resolved `8085/...` against `localhost:4200` |
| `effect()` injection context fix | `stream-player.component.ts` (modified) | Moved `effect()` from `ngOnInit` to field initializer — fixes `NG0203` runtime error |
| Angular rule update | `.claude/rules/angular/patterns.md` (modified) | Added `effect() Injection Context (CRITICAL)` section documenting that `effect()` must be in constructor/field-initializer/`runInInjectionContext`, NOT lifecycle hooks |

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking doc | Reason |
|------|-----------|-------------|--------|
| Frontend `onerror` handler on `<img>` tags | Chat team | IMPLEMENTATION-PLAN.md §2.9 | ~30s gap before first thumbnail frame; `onerror` handler falls back to placeholder. Affects BrowsePage, StreamListComponent, StreamPlayerComponent, VideoTabComponent |
| SessionRailComponent CSS fallback | Chat team | IMPLEMENTATION-PLAN.md §2.9 | Uses CSS `background-image` which can't use `onerror` — needs `url(thumb), url(placeholder)` layered fallback |
| Periodic thumbnail refresh | Future | ADR-0005 | Single frame on start is sufficient for MVP |
| Custom cover art upload (MinIO) | Phase 8+ | ADR-0006 (planned) | Separate ADR, separate phase |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

None. All deferred items are explicitly tracked in this retro and the implementation plan.

## 4. Architectural decisions made during implementation

1. **ffmpeg runs inside SRS container (not host, not stream-service)** — The `ossrs/srs:5` image (Ubuntu 20.04) bundles ffmpeg at `/usr/local/srs/objs/ffmpeg/bin/ffmpeg`. Running the watchdog inside the SRS container keeps the deployment self-contained: no host dependencies, no cross-container ffmpeg networking. The watchdog is a simple shell loop — no additional processes or HTTP servers needed.

2. **Deterministic thumbnail URL (no callback needed)** — Thumbnail path is `{srsHlsHost}/thumbnails/{srsName}.jpg`. Stream-service sets `thumbnail_url` immediately in `handlePublish()` without waiting for the actual frame. The file exists within ~30s (first watchdog cycle). Frontend handles the gap with an `onerror` → placeholder fallback.

3. **Nested Spring placeholder resolution for SRS URLs** — Changed from single `SRS_HLS_HOST` env var to nested resolution: `${SRS_HLS_HOST:http://${SRS_HOST:localhost}:${SRS_HTTP_PORT:8085}}`. The explicitly-set var takes precedence (backward compatible); otherwise the URL is built from component parts. This prevents relative-path bugs (bare `8085` → `localhost:4200/8085`) and wrong-port bugs (default `8080` when compose maps `8085:8080`).

4. **`effect()` field initializer over `ngOnInit`** — Angular's `effect()` requires an injection context (constructor, field initializer, or `runInInjectionContext`). Lifecycle hooks are NOT injection contexts. The fix pattern: field-initializer `effect()` guarded with conditionals that skip when signal values are `null`/`undefined` (which they always are at construction time). Documented in `.claude/rules/angular/patterns.md`.

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action |
|----------|---------------|-------------|--------|
| `docs/adr/stream/0005-stream-thumbnails.md` | Status: Proposed | 2.9 is now implemented | Accept ADR, add implementation date + commit references + decisions |
| `docs/IMPLEMENTATION-PLAN.md` §Phase 2 | 2.9 unchecked | 2.9 SRS snapshot thumbnails is now implemented | Check off 2.9, update "Last updated" date |
| `docs/plans/composed-wibbling-firefly.md` | Blueprint (approved) | Blueprint is now implemented | No update needed (blueprints are ephemeral) |

## 6. Updated execution order (actual vs planned)

| Planned | Actual | Status |
|---------|--------|--------|
| 2.9 SRS snapshot thumbnails | 2026-07-15: scripts + service + config + bonus fixes | ✅ Done |
| Frontend onerror handler | Deferred to chat team | Remaining |

## 7. Key risks carried forward

1. **Watchdog is polling-based** — Every 30s, ffmpeg connects to each active RTMP stream. At small scale (<10 concurrent streams) this is negligible. At scale, consider SRS `on_publish`/`on_unpublish` HTTP callbacks to a lightweight trigger service instead of polling. **Mitigation:** Documented; no action needed until concurrent streams exceed ~10.

2. **No ffmpeg availability check at container start** — If the bundled ffmpeg path changes in a future SRS image version, the watchdog silently fails (thumbnails never generate). **Mitigation:** Add a startup check in `entrypoint.sh` — `test -x "$FFMPEG" || echo "WARNING: ffmpeg not found"` — as a quick follow-up.

3. **Thumbnail URL set even if SRS is unreachable** — Stream-service sets `thumbnail_url` immediately without verifying SRS HTTP server is reachable. If SRS is down, the browser gets a broken image (which falls back to placeholder via `onerror`). **Mitigation:** Acceptable for MVP — the `onerror` handler (chat team) closes this gap. Consider a reachability check in a future hardening pass.
