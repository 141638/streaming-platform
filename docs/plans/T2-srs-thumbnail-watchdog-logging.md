# T2: Add Logging to SRS Thumbnail Watchdog

**Status**: Ready
**Depends on**: None
**Scope**: DevOps — 1 shell script

## Problem

The thumbnail watchdog script (`thumbnail-watchdog.sh`) suppresses ALL output:
- `curl` errors go to `/dev/null` (line 25)
- `ffmpeg` stderr goes to `/dev/null` (line 48)
- No timestamped log lines for any operation

When thumbnails don't generate, there is zero visibility into why. The user reported "container log but seeing no thing."

## Root Cause Analysis

Possible failure modes with zero visibility:

| Failure | Why Silent |
|---------|-----------|
| SRS API unreachable | `curl -s --max-time 5 "$SRS_API" 2>/dev/null` — empty output, no error |
| ffmpeg binary missing | `$FFMPEG ... 2>/dev/null &` — exits immediately, no error |
| RTMP stream not yet published | ffmpeg fails to connect, stderr discarded |
| Disk full | ffmpeg write fails, stderr discarded |
| Thumb dir not writable | `mkdir -p` succeeds but subsequent write fails |
| SRS not ready on startup | Only 3s sleep in entrypoint before watchdog starts |

## Current Code

**File**: `main/docker/srs/scripts/thumbnail-watchdog.sh`

```bash
#!/bin/sh
FFMPEG="/usr/local/srs/objs/ffmpeg/bin/ffmpeg"
THUMB_DIR="/usr/local/srs/objs/nginx/html/thumbnails"
SRS_API="http://127.0.0.1:1985/api/v1/clients"
INTERVAL=30

mkdir -p "$THUMB_DIR"

echo "[watchdog] Started — polling SRS API every ${INTERVAL}s"

while true; do
    STREAMS=$(curl -s --max-time 5 "$SRS_API" 2>/dev/null \
        | grep -o '"stream":"[^"]*"' \
        | cut -d'"' -f4 \
        | sort -u)

    if [ -n "$STREAMS" ]; then
        for stream in $STREAMS; do
            [ -z "$stream" ] && continue
            case "$stream" in
                /*|*..*) continue ;;
            esac

            THUMB="${THUMB_DIR}/${stream}.jpg"

            timeout 10 "$FFMPEG" \
                -i "rtmp://127.0.0.1:1935/live/${stream}" \
                -vframes 1 \
                -update 1 \
                -y \
                -loglevel error \
                "$THUMB" 2>/dev/null &
        done
    fi

    sleep "$INTERVAL"
done
```

## What to Change

### 1. Add startup diagnostics (after `mkdir -p`)

```bash
echo "[watchdog] $(date -Iseconds) Startup diagnostics:"
echo "[watchdog]   ffmpeg: $([ -x "$FFMPEG" ] && echo 'found' || echo 'MISSING')"
echo "[watchdog]   thumb dir: $([ -w "$THUMB_DIR" ] && echo 'writable' || echo 'NOT WRITABLE')"
echo "[watchdog]   SRS API reachable: $(curl -s --max-time 5 -o /dev/null -w '%{http_code}' "$SRS_API" 2>/dev/null || echo 'unreachable')"
```

### 2. Add timestamped poll-cycle logging

At the top of each loop iteration:
```bash
echo "[watchdog] $(date -Iseconds) Polling SRS API..."
```

### 3. Log API result

After the curl:
```bash
STREAMS=$(curl -s --max-time 5 "$SRS_API" 2>/dev/null \
    | grep -o '"stream":"[^"]*"' \
    | cut -d'"' -f4 \
    | sort -u)

if [ -n "$STREAMS" ]; then
    echo "[watchdog] $(date -Iseconds) Found $(echo "$STREAMS" | wc -l) active stream(s): $(echo "$STREAMS" | tr '\n' ' ')"
```

Also log when no streams found:
```bash
else
    echo "[watchdog] $(date -Iseconds) No active RTMP publishers"
fi
```

### 4. Log ffmpeg results (capture exit codes)

Replace the background `&` with a wait pattern:
```bash
timeout 10 "$FFMPEG" \
    -i "rtmp://127.0.0.1:1935/live/${stream}" \
    -vframes 1 \
    -update 1 \
    -y \
    -loglevel warning \
    "$THUMB" 2>&1 | while IFS= read -r line; do
        echo "[watchdog] [ffmpeg:${stream}] ${line}"
    done &
FFMPEG_PID=$!

# Wait briefly then check
(
    wait $FFMPEG_PID
    EXIT_CODE=$?
    if [ $EXIT_CODE -eq 0 ]; then
        echo "[watchdog] $(date -Iseconds) Thumbnail generated: ${stream}.jpg"
    else
        echo "[watchdog] $(date -Iseconds) Thumbnail FAILED for ${stream} (exit code: ${EXIT_CODE})"
    fi
) &
```

### 5. Use `log()` helper for consistency

Wrap in a helper function to keep the script clean:
```bash
log() {
    echo "[watchdog] $(date -Iseconds) $*"
}
```

## Verification

```bash
# After rebuilding and restarting SRS container:
docker logs srs 2>&1 | grep "\[watchdog\]"

# Expected output pattern:
# [watchdog] 2026-07-15T... Started — polling SRS API every 30s
# [watchdog] 2026-07-15T... Startup diagnostics:
# [watchdog]   ffmpeg: found
# [watchdog]   thumb dir: writable
# [watchdog]   SRS API reachable: 200
# [watchdog] 2026-07-15T... Polling SRS API...
# [watchdog] 2026-07-15T... Found 1 active stream(s): abc123def456
# [watchdog] 2026-07-15T... Thumbnail generated: abc123def456.jpg
```

## Agent Execution

```
🤖 Delegating to general-purpose: Rewrite thumbnail-watchdog.sh with comprehensive logging
```
