# Blueprint: Fix SRS Thumbnail Watchdog — Stream Detection

## Summary

The thumbnail watchdog (`thumbnail-watchdog.sh`) polls SRS's HTTP API every 30s to discover active RTMP publishers and capture thumbnail frames via ffmpeg. Logs show it consistently reports "no active streams found" even when streams should be publishing. The root cause is most likely a mismatch between the expected SRS v5 API response format and the shell-based JSON scraping approach, compounded by zero visibility into the raw API response. This blueprint diagnoses the parsing gap, switches to a more reliable endpoint, and adds diagnostic logging so similar issues are debuggable without exec-ing into the container.

## Problem Diagnosis

The watchdog uses:

```bash
curl -s --max-time 5 "http://127.0.0.1:1985/api/v1/clients" \
    | grep -o '"stream":"[^"]*"' \
    | cut -d'"' -f4 \
    | sort -u
```

Three possible failure modes (any or all may apply):

| # | Hypothesis | Likelihood | Evidence |
|---|-----------|------------|----------|
| 1 | SRS v5 `/api/v1/clients` wraps data in a `data` envelope or uses a different field structure, causing `grep` to miss the `"stream"` key | Medium | SRS v5 API may have changed response format |
| 2 | The `/api/v1/clients` endpoint returns *all* client connections (publishers + players) and the `"stream"` field may be absent or formatted differently for RTMP publishers vs HTTP players | Medium | Endpoint name implies "clients" not "streams" |
| 3 | No stream is actually publishing to SRS (OBS/ffmpeg not connected), so the watchdog is correct | Low-Medium | User confirmed they expect streams to be active |

Additionally, the watchdog has **zero diagnostic output** about the raw API response — when no streams are found, we can't tell whether the API returned `{"clients":[]}` or the parsing simply failed on a non-empty response.

## Patterns to Mirror

| Category | Source | Pattern |
|----------|--------|---------|
| Shell logging | `thumbnail-watchdog.sh:19-21` | `log()` helper function — use for all diagnostic output |
| Startup diagnostics | `thumbnail-watchdog.sh:26-52` | Check prerequisites at boot, log pass/fail clearly |
| Error handling | `thumbnail-watchdog.sh:90-93` | Log ffmpeg exit code + file state on failure |
| API healthcheck | `docker-compose.yml:26` | `curl -sf http://localhost:1985/api/v1/versions` — SRS API reachability check pattern |

## Design Decision: API Endpoint Strategy

### Choice: `/api/v1/streams` as primary, `/api/v1/clients` as fallback

SRS v5 exposes two relevant endpoints:

| Endpoint | Returns | Best for |
|----------|---------|----------|
| `/api/v1/streams` | Deduplicated list of active streams with metadata (name, app, video/audio stats, clients count) | **Stream discovery** — one entry per unique stream, field name is `"name"` |
| `/api/v1/clients` | Every connected client (publishers, players, HTTP, RTMP) | Low-level diagnostics — multiple entries per stream, field name varies by client type |

**Recommendation**: Switch the primary detection to `/api/v1/streams` because:
1. It returns one entry per active stream (no need for `sort -u`)
2. The stream key is in the `"name"` field
3. It includes stream health metadata (`live_ms`, `clients`, video/audio presence)
4. It's the canonical "what streams are currently being published?" endpoint
5. An empty `streams` array definitively means no streams are active

The `/api/v1/clients` approach has a conceptual mismatch: it returns individual *connections*, not *streams*. A single RTMP publisher creates one client; each HLS player creates another. The grep for `"stream"` could match player clients that happen to have a `"stream"` field too, or miss publishers if the field is structured differently.

### Fallback strategy

If `/api/v1/streams` returns a non-200 or unparseable response, try `/api/v1/clients` as a fallback (keep the existing grep logic). This ensures we don't break if the endpoint isn't available in a particular SRS build.

## Files to Change

| File | Action | Why |
|------|--------|-----|
| `main/docker/srs/scripts/thumbnail-watchdog.sh` | **MODIFY** | Fix stream detection, add diagnostic logging |

Only one file. The fix is entirely in the watchdog script — no backend or frontend changes needed.

## Tasks

### Task 1: Add raw API response diagnostic logging

- **Action**: When no streams are found in a poll cycle, log a truncated (first 500 chars) raw response body and the HTTP status code. This eliminates the current blind spot.
- **Files**: `main/docker/srs/scripts/thumbnail-watchdog.sh`
- **Details**:
  - Capture the HTTP response body and status code into variables instead of piping directly
  - Log HTTP status on every poll cycle
  - When `STREAMS` is empty, log `"raw API response (truncated): <first 500 chars>"`
  - Truncation prevents log flooding from large response bodies

### Task 2: Switch primary detection to `/api/v1/streams`

- **Action**: Try `/api/v1/streams` first. Extract stream names from the `"name"` field in the `streams` array. Fall back to `/api/v1/clients` if the streams endpoint fails.
- **Files**: `main/docker/srs/scripts/thumbnail-watchdog.sh`
- **Details**:
  - New variable: `SRS_STREAMS_API="http://127.0.0.1:1985/api/v1/streams"`
  - New function: `fetch_active_streams()` that:
    1. Calls `/api/v1/streams`, greps for `"name":"<value>"` in the JSON response
    2. If that returns streams → use them
    3. If empty or curl fails → try `/api/v1/clients` with the existing `"stream":"<value>"` grep
  - Log which endpoint was used and how many streams found
  - The `"name"` field in `/api/v1/streams` is the equivalent of the `"stream"` field in `/api/v1/clients`

### Task 3: Verify the fix end-to-end

- **Action**: Test with a real or simulated SRS publish to confirm stream detection works
- **Files**: `main/docker/srs/scripts/thumbnail-watchdog.sh`
- **Validate**:
  ```bash
  # 1. Rebuild the SRS container
  docker compose -f main/docker/srs/docker-compose.yml up -d --build
  
  # 2. Check watchdog startup diagnostics
  docker logs streaming-srs 2>&1 | grep "\[watchdog\]" | head -20
  
  # 3. Publish a test stream with ffmpeg
  ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=30 -f flv rtmp://localhost:1935/live/test-stream
  
  # 4. Verify watchdog detects it (should see "found 1 active stream(s)")
  docker logs streaming-srs 2>&1 | grep "\[watchdog\]" | tail -20
  
  # 5. Verify thumbnail file is created
  docker exec streaming-srs ls -la /usr/local/srs/objs/nginx/html/thumbnails/
  ```

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| `/api/v1/streams` not available in SRS v5 | Low | Fall back to `/api/v1/clients` automatically; log which endpoint succeeded |
| `grep` still misses fields if JSON is minified with unusual formatting | Low | The raw response logging (Task 1) makes this immediately diagnosable from logs |
| SRS Docker image lacks `grep` or `cut` | Very Low | Both are POSIX standard; ossrs/srs:5 is Ubuntu-based and has both |
| `jq` not available for proper JSON parsing | Confirmed | ossrs/srs:5 does not include `jq`; we must use grep-based parsing. The truncation log lets us verify parsing correctness visually |

## Validation

```bash
# Full verification sequence
cd /home/tomosanekun/Repositories/personal_repository/streaming-platform

# 1. Verify script syntax
bash -n main/docker/srs/scripts/thumbnail-watchdog.sh

# 2. Rebuild and restart SRS container
docker compose -f main/docker/srs/docker-compose.yml down
docker compose -f main/docker/srs/docker-compose.yml up -d

# 3. Wait for startup, check diagnostics
sleep 10
docker logs streaming-srs 2>&1 | grep "\[watchdog\]" | head -15

# 4. Publish a test stream
ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=30 \
  -c:v libx264 -preset ultrafast -f flv \
  rtmp://localhost:1935/live/test-stream &
FFMPEG_PID=$!

# 5. Wait for one poll cycle, check detection
sleep 35
docker logs streaming-srs --since 30s 2>&1 | grep "\[watchdog\]"

# 6. Verify thumbnail
docker exec streaming-srs ls -la /usr/local/srs/objs/nginx/html/thumbnails/

# 7. Cleanup
kill $FFMPEG_PID 2>/dev/null
docker compose -f main/docker/srs/docker-compose.yml down
```
