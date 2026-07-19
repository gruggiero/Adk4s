#!/usr/bin/env bash
# metals-start.sh — start (or stop) the PER-PROJECT headless Metals MCP
# server and record its endpoint for discovery.
#
# Metals is workspace-scoped: one instance serves ONE build. Parallel
# projects each run their own instance on their own port. This helper:
#   1. finds a free port (8394 upward),
#   2. finds a JDK 17+ (JAVA_HOME, or derives it from the PATH java),
#   3. starts `metals-mcp --workspace <repo-root>` detached,
#   4. writes the endpoint to <repo-root>/.metals/mcp.url — which
#      metals-call.sh discovers automatically, so every schema recipe in
#      this repo talks to THIS repo's index and never another project's,
#   5. prints the line to record in openspec/capability-profile.md.
#
# Usage:
#   metals-start.sh [repo-root]     # start (no-op if already running)
#   metals-start.sh stop [repo-root]
#
# Requires: `metals-mcp` on PATH (cs install metals-mcp). RAM: ~1.7 GB
# resident per instance — stop instances you are not actively using.
set -euo pipefail

CMD="start"
if [ "${1:-}" = "stop" ]; then CMD="stop"; shift; fi
ROOT="${1:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
ROOT="$(cd "$ROOT" && pwd)"
META="$ROOT/.metals"
URL_FILE="$META/mcp.url"
PID_FILE="$META/mcp.pid"
LOG_FILE="$META/metals-mcp.log"

endpoint_up() { # $1=url
  curl -sS --max-time 3 -o /dev/null -X POST "$1" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    --data '{"jsonrpc":"2.0","id":0,"method":"ping"}' 2>/dev/null
}

if [ "$CMD" = "stop" ]; then
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    kill "$(cat "$PID_FILE")"
    echo "metals-start: stopped instance for $ROOT (pid $(cat "$PID_FILE"))"
  else
    echo "metals-start: no running instance recorded for $ROOT"
  fi
  rm -f "$PID_FILE" "$URL_FILE"
  exit 0
fi

# already running?
if [ -f "$URL_FILE" ] && endpoint_up "$(cat "$URL_FILE")"; then
  echo "metals-start: already running for $ROOT at $(cat "$URL_FILE")"
  exit 0
fi

command -v metals-mcp >/dev/null || {
  echo "metals-start: metals-mcp not found — install with: cs install metals-mcp" >&2
  exit 1
}

# JDK 17+: prefer JAVA_HOME; otherwise derive from the PATH java
java_major() { # $1=java binary
  "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] \
   && [ "$(java_major "$JAVA_HOME/bin/java")" -ge 17 ] 2>/dev/null; then
  :
elif command -v java >/dev/null && [ "$(java_major java)" -ge 17 ] 2>/dev/null; then
  JAVA_HOME="$(java -XshowSettings:properties -version 2>&1 \
    | sed -n 's/^ *java\.home = //p' | head -1)"
  export JAVA_HOME
else
  echo "metals-start: no JDK 17+ found — set JAVA_HOME to one (metals-mcp needs it)" >&2
  exit 1
fi

# free port from 8394 upward
PORT=8394
while (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; do
  exec 3>&- 3<&- || true
  PORT=$((PORT + 1))
  [ "$PORT" -gt 8420 ] && { echo "metals-start: no free port in 8394-8420" >&2; exit 1; }
done

mkdir -p "$META"
URL="http://localhost:$PORT/mcp"
nohup metals-mcp --workspace "$ROOT" --port "$PORT" > "$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"
echo "$URL" > "$URL_FILE"

echo "metals-start: starting for $ROOT on port $PORT (pid $(cat "$PID_FILE"), log: $LOG_FILE)"
printf "metals-start: waiting for endpoint"
for _ in $(seq 1 60); do
  if endpoint_up "$URL"; then
    echo " — up."
    echo ""
    echo "Record in openspec/capability-profile.md (Code Intelligence):"
    echo "  Metals MCP endpoint: $URL — discovery: .metals/mcp.url (this file); start/stop: scanner/metals-start.sh"
    echo ""
    echo "Note: the endpoint answers immediately but indexing may continue for"
    echo "a while on first import — early symbol queries can be incomplete."
    exit 0
  fi
  printf "."
  sleep 2
done
echo ""
echo "metals-start: endpoint not up after 120s — check $LOG_FILE (build import can be slow on first run; the URL file stays in place and metals-call.sh will use it once the server is up)" >&2
exit 1
