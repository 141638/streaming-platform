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
SRS_API="http://127.0.0.1:1985/api/v1/clients"
INTERVAL=30

mkdir -p "$THUMB_DIR"

echo "[watchdog] Started — polling SRS API every ${INTERVAL}s"

while true; do
    # Fetch active RTMP publishers from the SRS HTTP API.
    # The /api/v1/clients endpoint returns connected clients with "stream" fields.
    STREAMS=$(curl -s --max-time 5 "$SRS_API" 2>/dev/null \
        | grep -o '"stream":"[^"]*"' \
        | cut -d'"' -f4 \
        | sort -u)

    if [ -n "$STREAMS" ]; then
        for stream in $STREAMS; do
            # Skip empty or bogus stream names
            [ -z "$stream" ] && continue
            case "$stream" in
                /*|*..*) continue ;;  # skip path traversal attempts
            esac

            THUMB="${THUMB_DIR}/${stream}.jpg"

            # Grab one frame. timeout + background prevents a stuck ffmpeg
            # from blocking the watchdog loop.
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
