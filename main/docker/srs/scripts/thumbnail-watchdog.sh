#!/bin/sh
# ── SRS Thumbnail Watchdog ───────────────────────────────────────────────
# Polls the SRS HTTP API every 30s for active RTMP publishers and grabs
# a single frame from each stream via ffmpeg.
#
# Thumbnails are written to the HLS serving directory so SRS's embedded
# HTTP server serves them at /thumbnails/{srsName}.jpg on port 8080.
#
# Depends on:
#   - SRS HTTP API on 127.0.0.1:1985
#   - ffmpeg at /usr/local/srs/objs/ffmpeg/bin/ffmpeg (bundled with SRS v5)

FFMPEG="/usr/local/srs/objs/ffmpeg/bin/ffmpeg"
THUMB_DIR="/usr/local/srs/objs/nginx/html/thumbnails"

# Primary: /api/v1/streams returns one entry per active stream (key in "name" field).
# Fallback: /api/v1/clients returns individual connections (stream key also in "name"
# field; note "stream" is a server-generated ID, not the stream key).
# IMPORTANT: SRS v5 collection endpoints REQUIRE trailing slashes. Without them,
# the API returns a redirect message instead of JSON.
SRS_STREAMS_API="http://127.0.0.1:1985/api/v1/streams/"
SRS_CLIENTS_API="http://127.0.0.1:1985/api/v1/clients/"
INTERVAL=30

# ── Logging helper ──────────────────────────────────────────────────────
log() {
    echo "[watchdog] $(date -Iseconds) $*"
}

# ── Startup diagnostics ─────────────────────────────────────────────────
mkdir -p "$THUMB_DIR"

log "Started — polling SRS API every ${INTERVAL}s"

# Check ffmpeg binary
if [ -x "$FFMPEG" ]; then
    log "ffmpeg: found and executable at $FFMPEG"
else
    if [ -e "$FFMPEG" ]; then
        log "ffmpeg: exists at $FFMPEG but is NOT executable"
    else
        log "ffmpeg: NOT FOUND at $FFMPEG"
    fi
fi

# Check thumb dir is writable
if [ -w "$THUMB_DIR" ]; then
    log "thumb dir: writable — $THUMB_DIR"
else
    log "thumb dir: NOT WRITABLE — $THUMB_DIR"
fi

# Check SRS API reachability (primary endpoint)
API_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$SRS_STREAMS_API" 2>&1)
if [ "$API_STATUS" = "200" ]; then
    log "SRS streams API: reachable (HTTP ${API_STATUS})"
else
    log "SRS streams API: unreachable or unexpected status (HTTP ${API_STATUS})"
fi

# Check fallback endpoint too
CLIENTS_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$SRS_CLIENTS_API" 2>&1)
if [ "$CLIENTS_STATUS" = "200" ]; then
    log "SRS clients API: reachable (HTTP ${CLIENTS_STATUS})"
else
    log "SRS clients API: unreachable or unexpected status (HTTP ${CLIENTS_STATUS})"
fi

# ── Stream discovery ─────────────────────────────────────────────────────
# Tries /api/v1/streams first (canonical "what's live?" endpoint).
# Falls back to /api/v1/clients if the primary endpoint fails or returns
# an unexpected HTTP status.
fetch_active_streams() {
    API_USED=""
    RAW=""
    HTTP_CODE=""

    # ── Primary: /api/v1/streams ──────────────────────────────────────────
    RAW=$(curl -s --max-time 5 "$SRS_STREAMS_API" 2>/dev/null)
    HTTP_CODE=$(echo "$RAW" | grep -o '"code":[0-9]*' | head -1 | cut -d: -f2)

    # SRS API returns "code": 0 on success. If we got a valid response,
    # extract stream names from the "name" field in the "streams" array.
    if [ "$HTTP_CODE" = "0" ]; then
        STREAMS=$(echo "$RAW" \
            | grep -o '"name":"[^"]*"' \
            | cut -d'"' -f4 \
            | sort -u)
        API_USED="streams"
        return 0
    fi

    # ── Fallback: /api/v1/clients ─────────────────────────────────────────
    log "streams API returned unexpected code=${HTTP_CODE}, trying clients API..."
    RAW=$(curl -s --max-time 5 "$SRS_CLIENTS_API" 2>/dev/null)
    HTTP_CODE=$(echo "$RAW" | grep -o '"code":[0-9]*' | head -1 | cut -d: -f2)

    if [ "$HTTP_CODE" = "0" ]; then
        STREAMS=$(echo "$RAW" \
            | grep -o '"name":"[^"]*"' \
            | cut -d'"' -f4 \
            | sort -u)
        API_USED="clients"
        return 0
    fi

    # Both endpoints failed
    STREAMS=""
    API_USED="none"
    return 1
}

# ── Main poll loop ──────────────────────────────────────────────────────
while true; do
    log "=== poll cycle start ==="

    fetch_active_streams

    if [ -n "$STREAMS" ]; then
        STREAM_COUNT=$(echo "$STREAMS" | wc -l)
        log "found ${STREAM_COUNT} active stream(s) via /api/v1/${API_USED}: $(echo "$STREAMS" | tr '\n' ' ')"

        for stream in $STREAMS; do
            # Skip empty or bogus stream names
            [ -z "$stream" ] && continue
            case "$stream" in
                /*|*..*) continue ;;  # skip path traversal attempts
            esac

            THUMB="${THUMB_DIR}/${stream}.jpg"

            # Grab one frame with verbose-enough logging to see failures.
            log "grabbing thumbnail for stream '${stream}' ..."

            timeout 10 "$FFMPEG" \
                -i "rtmp://127.0.0.1:1935/live/${stream}" \
                -vframes 1 \
                -update 1 \
                -y \
                -loglevel warning \
                "$THUMB" 2>&1
            FFMPEG_EXIT=$?

            if [ $FFMPEG_EXIT -eq 0 ] && [ -s "$THUMB" ]; then
                log "thumbnail OK — stream '${stream}' -> ${THUMB} ($(stat -c%s "$THUMB" 2>/dev/null || echo '?') bytes)"
            else
                log "thumbnail FAILED — stream '${stream}', ffmpeg exit code ${FFMPEG_EXIT}, file $( [ -f "$THUMB" ] && echo 'exists' || echo 'missing' )"
            fi
        done
    else
        # Log truncated raw response for diagnosis when no streams found
        RAW_PREVIEW=$(echo "$RAW" | head -c 500)
        log "no active streams found (api=${API_USED}, http_code=${HTTP_CODE})"
        log "raw response preview: ${RAW_PREVIEW}"
    fi

    log "=== poll cycle end, sleeping ${INTERVAL}s ==="
    sleep "$INTERVAL"
done
