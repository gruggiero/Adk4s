#!/usr/bin/env bash
# metals-call.sh — call a Metals MCP tool from the shell (option B of the
# code-intelligence investigation: skills/scripts drive Metals' MCP HTTP
# endpoint directly, no agent-side MCP registration needed).
#
# Speaks MCP streamable-HTTP: initialize → notifications/initialized →
# tools/call, carrying the Mcp-Session-Id header across requests. Responses
# may be plain JSON or SSE; both are handled.
#
# Usage:
#   metals-call.sh list                      # list available tools
#   metals-call.sh <tool> '<json-args>'     # call a tool
#   metals-call.sh probe                    # exit 0 if the endpoint is up
#   metals-call.sh resolve <Name|fq.Name>   # canonical FQCN(s) for a symbol
#                                           # (nested/opaque symbols differ
#                                           # from their source path — e.g.
#                                           # RunnableOps.FallbackSemantic)
# Endpoint discovery (per-project — Metals is workspace-scoped, so each
# repo runs its own instance; see metals-start.sh):
#   1. METALS_MCP_URL environment variable (explicit override)
#   2. <repo-root>/.metals/mcp.url (written by metals-start.sh) — this is
#      what makes parallel projects safe: each repo's scripts find THEIR
#      instance, never another project's index
#   3. default http://localhost:8394/mcp
#
# Examples:
#   metals-call.sh glob-search '{"query":"AgentEvent"}'
#   metals-call.sh inspect '{"fqcn":"org.adk4s.core.interrupt.AgentEvent"}'
#   metals-call.sh get-usages '{"fqcn":"org.adk4s.core.interrupt.AgentEvent"}'
#
# Exit: 0 ok; 2 endpoint unreachable (callers should fall back to grep);
#       1 tool error.
set -euo pipefail

discover_url() {
  if [ -n "${METALS_MCP_URL:-}" ]; then
    printf '%s' "$METALS_MCP_URL"
    return
  fi
  local root
  root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
  if [ -n "$root" ] && [ -f "$root/.metals/mcp.url" ]; then
    head -1 "$root/.metals/mcp.url"
    return
  fi
  printf 'http://localhost:8394/mcp'
}
URL="$(discover_url)"
CMD="${1:-list}"
ARGS="${2:-{\}}"

post() { # $1=json body, $2=extra headers (optional "Mcp-Session-Id: x")
  local hdr=()
  [ -n "${2:-}" ] && hdr=(-H "$2")
  curl -sS --max-time 120 -D /tmp/metals-call-headers.$$ \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    "${hdr[@]}" \
    -X POST "$URL" --data "$1"
}

# SSE responses arrive as "id:/event:/data:" lines — extract JSON payloads.
unsse() {
  if printf '%s\n' "$1" | grep -q '^data:'; then
    printf '%s\n' "$1" | sed -n 's/^data: \{0,1\}//p'
  else
    printf '%s\n' "$1"
  fi
}

if ! curl -sS --max-time 5 -o /dev/null -X POST "$URL" \
     -H 'Content-Type: application/json' \
     -H 'Accept: application/json, text/event-stream' \
     --data '{"jsonrpc":"2.0","id":0,"method":"ping"}' 2>/dev/null; then
  echo "metals-call: endpoint $URL unreachable — start with scanner/metals-start.sh (fallback: git grep)" >&2
  exit 2
fi
[ "$CMD" = "probe" ] && { echo "metals-call: $URL reachable"; exit 0; }

# resolve: map a (possibly wrong) FQCN or simple name to canonical FQCN(s)
# via glob-search — needed because nested symbols (Outer.Inner) and opaque
# types (synthetic `package` object) differ from their source-path guess,
# and get-usages on an unresolvable FQCN silently degrades to PACKAGE usages.
if [ "$CMD" = "resolve" ]; then
  NAME="${ARGS:-}"; NAME="${NAME//\{\}/}"
  [ -n "$NAME" ] || { echo "usage: metals-call.sh resolve <Name|fq.Name>" >&2; exit 1; }
  SIMPLE="${NAME##*.}"
  FCTX="$(git ls-files -- '*/src/main/scala/*.scala' 2>/dev/null | head -1 || true)"
  CMD="glob-search"
  ARGS="$(printf '{"query":"%s","fileInFocus":"%s"}' "$SIMPLE" "$FCTX")"
  RESOLVE_FILTER="$SIMPLE"
fi

INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"metals-call.sh","version":"0.1"}}}'
init_resp="$(post "$INIT")"
SESSION="$(sed -n 's/^[Mm]cp-[Ss]ession-[Ii]d: *//p' /tmp/metals-call-headers.$$ | tr -d '\r' | head -1)"
rm -f /tmp/metals-call-headers.$$

sess_hdr=""
[ -n "$SESSION" ] && sess_hdr="Mcp-Session-Id: $SESSION"

post '{"jsonrpc":"2.0","method":"notifications/initialized"}' "$sess_hdr" >/dev/null || true

if [ "$CMD" = "list" ]; then
  body='{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
else
  body="$(printf '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"%s","arguments":%s}}' "$CMD" "$ARGS")"
fi

resp="$(post "$body" "$sess_hdr")"
rm -f /tmp/metals-call-headers.$$ 2>/dev/null || true
out="$(unsse "$resp")"

# Pretty-print: tool results carry content[].text; lists carry tools[].name
render() {
  if command -v python3 >/dev/null; then
    printf '%s\n' "$1" | python3 -c '
import sys, json, signal
signal.signal(signal.SIGPIPE, signal.SIG_DFL)
raw = sys.stdin.read().strip()
for line in [l for l in raw.splitlines() if l.strip()]:
    try:
        msg = json.loads(line)
    except json.JSONDecodeError:
        print(line); continue
    if "error" in msg:
        print("ERROR:", msg["error"].get("message", msg["error"])); sys.exit(1)
    result = msg.get("result", {})
    if "tools" in result:
        for t in result["tools"]:
            print(t["name"] + ": " + t.get("description", "")[:100])
    elif "content" in result:
        for c in result["content"]:
            if c.get("type") == "text":
                print(c["text"])
    elif result:
        print(json.dumps(result, indent=2))
'
  else
    printf '%s\n' "$1"
  fi
}

if [ -n "${RESOLVE_FILTER:-}" ]; then
  # keep symbols whose FQCN's last segment IS the requested simple name;
  # strip the kind prefix so the output is directly usable as an fqcn arg
  render "$out" | awk -v s="$RESOLVE_FILTER" '
    { fq = $NF; n = split(fq, seg, "."); if (seg[n] == s) print fq }' | sort -u
else
  render "$out"
fi
