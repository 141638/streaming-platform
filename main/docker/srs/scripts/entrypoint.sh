#!/bin/sh
set -e

# ── SRS Thumbnail Entrypoint ─────────────────────────────────────────────
# Starts SRS in the background, then runs the thumbnail watchdog.
# The SRS process is the primary — when it exits, the container exits.

# Ensure curl is available (ossrs/srs:5 base image ships without it).
# The thumbnail watchdog depends on curl for SRS HTTP API calls.
if ! command -v curl >/dev/null 2>&1; then
    echo "[entrypoint] curl not found, installing..."
    apt-get update -qq
    apt-get install -y -qq curl
    echo "[entrypoint] curl installed"
fi

echo "[entrypoint] Starting SRS..."
/usr/local/srs/objs/srs -c /usr/local/srs/conf/custom.conf &
SRS_PID=$!

# Give SRS a moment to bind ports before starting the watchdog
sleep 3

echo "[entrypoint] Starting thumbnail watchdog..."
/usr/local/srs/scripts/thumbnail-watchdog.sh &
WATCHDOG_PID=$!

echo "[entrypoint] SRS PID=$SRS_PID, Watchdog PID=$WATCHDOG_PID"

# Trap SIGTERM/SIGINT and forward to both processes
cleanup() {
    echo "[entrypoint] Shutting down..."
    kill "$WATCHDOG_PID" 2>/dev/null || true
    kill "$SRS_PID" 2>/dev/null || true
    wait "$SRS_PID" 2>/dev/null || true
    exit 0
}
trap cleanup TERM INT

# Wait for SRS (primary process)
wait "$SRS_PID"
echo "[entrypoint] SRS exited, stopping watchdog..."
kill "$WATCHDOG_PID" 2>/dev/null || true
