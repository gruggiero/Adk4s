#!/usr/bin/env bash
# gate.sh — the harness-agnostic hook entry point for verified-scala3.
#
# WHY THIS EXISTS
# Every check in this workflow lives in scanner/*.sh, and every one of them is
# opt-in: an agent that does not run the script never sees the fact that would
# have corrected it. A review once recorded the ALTITUDE rule as "N/A" on a
# repository holding 30 concept files, because it answered a factual question
# from memory instead of from the filesystem. The scripts fixed what happens
# WHEN they run; a hook fixes WHETHER they run.
#
# SCOPE — Tier B only (context injection). This script NEVER blocks and always
# exits 0. Blocking gates (spec-lint on spec.md writes, registry-check before
# commit) are deliberately not here yet: a blocking hook that misfires strands
# the agent, and that risk is only worth taking once the non-blocking path has
# proven itself.
#
# PORTABILITY — bash + git only, same rule as the gate checks. No JVM, no
# network, no jq. It must run wherever the workflow runs, including a
# restricted CI runner, or it is not a dependable part of the workflow.
#
# Usage:
#   gate.sh --event session-start [--format hook-json|text] [--repo PATH]
#
#   --format hook-json  {"hookSpecificOutput":{...,"additionalContext":"..."}}
#                       Claude Code, Codex CLI, Devin CLI (shared contract)
#   --format text       plain text; for harnesses that embed it themselves
#                       (the pi extension, or any stdout-as-context harness)
#
# Exit codes: always 0. A hook that fails must never be the reason a session
# cannot start.
set -uo pipefail          # NOT -e: a failed probe degrades, it does not abort

EVENT="session-start"
FORMAT="hook-json"
REPO=""

while [ $# -gt 0 ]; do
  case "$1" in
    --event)  EVENT="${2:-}"; shift 2 ;;
    --format) FORMAT="${2:-}"; shift 2 ;;
    --repo)   REPO="${2:-}"; shift 2 ;;
    *)        shift ;;
  esac
done

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
SCANNER="$SELF_DIR/../scanner"
SCHEMA_YAML="$SELF_DIR/../schema.yaml"

# ── locate the repository ────────────────────────────────────────────────
# Hooks are invoked from an unpredictable cwd, so the repo is resolved from
# the most explicit signal available and only then guessed.
if [ -z "$REPO" ]; then
  # the event JSON carries "cwd"; extract it without jq, which may not exist
  if [ ! -t 0 ]; then
    payload="$(cat 2>/dev/null || true)"
    REPO="$(printf '%s' "$payload" \
      | sed -n 's/.*"cwd"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
  fi
fi
[ -z "$REPO" ] && REPO="${CLAUDE_PROJECT_DIR:-}"
[ -z "$REPO" ] && REPO="$(git rev-parse --show-toplevel 2>/dev/null || true)"
[ -z "$REPO" ] && REPO="$PWD"

# ── trace: proof that the HARNESS invoked this, not just that it works ───
# Set VERIFIED_SCALA3_HOOKS_TRACE=/path/to/file to append one line per
# invocation. It records EVERY call, including the ones that emit nothing —
# because "fired and stayed silent" and "never fired" are indistinguishable
# from outside, and telling them apart is the whole point of verifying an
# install. Off unless the variable is set.
trace() {
  [ -n "${VERIFIED_SCALA3_HOOKS_TRACE:-}" ] || return 0
  printf '%s  event=%-14s format=%-9s repo=%-42s %s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$EVENT" "$FORMAT" "$REPO" "$1" \
    >> "$VERIFIED_SCALA3_HOOKS_TRACE" 2>/dev/null || true
}

# ── relevance guard ──────────────────────────────────────────────────────
# A repository that does not use this workflow gets NOTHING. A hook installed
# globally must be invisible where it does not apply, or it becomes noise that
# people disable — and a disabled hook enforces nothing.
if [ ! -d "$REPO/openspec" ]; then trace "skip: no openspec/ here"; exit 0; fi
if [ "${VERIFIED_SCALA3_HOOKS:-on}" = "off" ]; then trace "skip: VERIFIED_SCALA3_HOOKS=off"; exit 0; fi

schema_ver="$(awk -F': *' '/^version:/ {print $2; exit}' "$SCHEMA_YAML" 2>/dev/null)"

# ── section 1: applicability facts ───────────────────────────────────────
# Delegated to spec-lint.sh --context-only. Recomputing them here would create
# a second implementation that could disagree with the one the lint reports —
# the drift defect, rebuilt by hand.
context=""
if [ -x "$SCANNER/spec-lint.sh" ] || [ -f "$SCANNER/spec-lint.sh" ]; then
  context="$(cd "$REPO" && bash "$SCANNER/spec-lint.sh" --context-only "$REPO" 2>/dev/null || true)"
fi

# ── section 2: workflow position ─────────────────────────────────────────
# Which artifact comes next is derived from the schema's own artifact DAG, not
# from a list copied into this script — a copied list is a future drift bug.
artifact_order="$(awk '
  /^artifacts:/            { in_a = 1; next }
  in_a && /^[a-z_-]+:/     { in_a = 0 }
  in_a && /^  - id: /      { id = $3 }
  in_a && /^    generates: / { print id "\t" $2 }
' "$SCHEMA_YAML" 2>/dev/null)"

position=""
for chg in "$REPO"/openspec/changes/*/; do
  [ -d "$chg" ] || continue
  case "$chg" in */archive/*) continue ;; esac
  name="$(basename "$chg")"
  present=""
  next=""
  while IFS="$(printf '\t')" read -r aid gen; do
    [ -z "${gen:-}" ] && continue
    case "$gen" in
      */*\**) exists=$([ -d "$chg/specs" ] && echo 1 || echo 0) ;;  # specs/**/*.md
      *)      exists=$([ -f "$chg/$gen" ] && echo 1 || echo 0) ;;
    esac
    if [ "$exists" = "1" ]; then
      present="${present:+$present, }$aid"
    elif [ -z "$next" ]; then
      next="$aid ($gen)"
    fi
  done <<EOF
$artifact_order
EOF
  position="$position
  active change        $name
    artifacts present  ${present:-none}
    next artifact      ${next:-none — all planning artifacts exist}"
done

if [ -z "$context" ] && [ -z "$position" ]; then
  trace "skip: nothing to report (no facts, no active change)"
  exit 0
fi

# ── assemble ─────────────────────────────────────────────────────────────
body="verified-scala3 — session context (schema v${schema_ver:-?}, injected by hooks/gate.sh)
${context}${position}

  gate checks          scanner/spec-lint.sh · registry-check.sh · danger-scan.sh

The lines above were READ FROM DISK at session start; they are facts, not
recollection. Where a conditional check is marked APPLIES, \"N/A\" is not a
valid verdict for it — see the spec-lint artifact instruction."

# ── emit ─────────────────────────────────────────────────────────────────
trace "emit: ${#body} chars"
case "$FORMAT" in
  text)
    printf '%s\n' "$body"
    ;;
  hook-json)
    # JSON-escape without jq: backslash, quote, then newlines to \n.
    esc="$(printf '%s' "$body" \
      | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' \
      | awk '{ printf "%s\\n", $0 }')"
    case "$EVENT" in
      session-start)  ev="SessionStart" ;;
      prompt-submit)  ev="UserPromptSubmit" ;;
      *)              ev="SessionStart" ;;
    esac
    printf '{"hookSpecificOutput":{"hookEventName":"%s","additionalContext":"%s"}}\n' "$ev" "$esc"
    ;;
  *)
    printf '%s\n' "$body"
    ;;
esac
exit 0
