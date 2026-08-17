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
# spec: gate-payload (change: add-correctness-substratum). Turns this from a
# positional status line (schema version, active change, next artifact) into
# the standing statement of the correctness invariant PLUS the live count of
# claims that outrun their evidence, injected on every turn — not once per
# session, so it survives context compaction — and suppressed only when the
# underlying facts have not changed since the last injection in the SAME
# session.
#
# SCOPE — Tier B only (context injection). This script NEVER blocks and always
# exits 0. Blocking gates (spec-lint on spec.md writes, registry-check before
# commit) are deliberately not here yet: a blocking hook that misfires strands
# the agent, and that risk is only worth taking once the non-blocking path has
# proven itself.
#
# PREREQUISITES (schema v12) — bash, git and jq at runtime; no JVM, no
# network. See ./README.md for the full set and what requires each entry.
#
# Usage:
#   gate.sh --event session-start|prompt-submit [--format hook-json|text]
#           [--repo PATH] [--session ID]
#   gate.sh --event post-edit --file PATH [--repo PATH] [--format hook-json|text]
#   gate.sh --event tool-call --file PATH [--tool NAME] [--repo PATH]
#           [--session ID] [--format hook-json|text]
#   gate.sh --event completion [--turn-text TEXT] [--stop-hook-active true|false]
#           [--repo PATH] [--session ID] [--format hook-json|text]
#   gate.sh --check-installed [--repo PATH]
#
#   --format hook-json  {"hookSpecificOutput":{...,"additionalContext":"..."}}
#                       Claude Code, Codex CLI, Devin CLI (shared contract)
#   --format text       plain text; for harnesses that embed it themselves
#                       (the pi extension, or any stdout-as-context harness)
#
# TESTABILITY SEAM: set CHAIN_STATE_OVERRIDE to point at an alternate
# chain-state.sh (same pattern as SPEC_LINT_OVERRIDE in chain-state.sh
# itself), so the oracle can construct exact chain states without depending
# on chain-state.sh's own already-tested classification. SPEC_LINT_OVERRIDE
# and DANGER_SCAN_OVERRIDE do the same for the post-edit tier.
#
# Exit codes: --event post-edit is always 0 (Tier A' is informational only,
# and can never block — see spec:hook-tiers). --event tool-call is 0 allow,
# 2 refuse (oracle phase blocks a production edit — the pre-execution tier,
# spec:oracle-ordering-lock). --event completion is 0 allow, 1 refuse
# (unresolved requirements, named), 2 refuse (chain state undetermined — a
# distinct reason, never conflated with "unresolved"). Every other event is
# unconditionally 0, unchanged.
set -uo pipefail # NOT -e: a failed probe degrades, it does not abort

EVENT="session-start"
FORMAT="hook-json"
REPO=""
SESSION=""
CHECK_INSTALLED=0
FILE_PATH=""
TURN_TEXT=""
STOP_HOOK_ACTIVE=""
STOP_HOOK_ACTIVE_GIVEN=0
TOOL_NAME=""
GATE_COMMAND=""
GATE_EXIT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --event)
      EVENT="${2:-}"
      shift 2
      ;;
    --format)
      FORMAT="${2:-}"
      shift 2
      ;;
    --repo)
      REPO="${2:-}"
      shift 2
      ;;
    --session)
      SESSION="${2:-}"
      shift 2
      ;;
    --check-installed)
      CHECK_INSTALLED=1
      shift
      ;;
    --file)
      FILE_PATH="${2:-}"
      shift 2
      ;;
    --turn-text)
      TURN_TEXT="${2:-}"
      shift 2
      ;;
    --stop-hook-active)
      STOP_HOOK_ACTIVE="${2:-}"
      STOP_HOOK_ACTIVE_GIVEN=1
      shift 2
      ;;
    --tool)
      TOOL_NAME="${2:-}"
      shift 2
      ;;
    --command)
      GATE_COMMAND="${2:-}"
      shift 2
      ;;
    --exit)
      GATE_EXIT="${2:-}"
      shift 2
      ;;
    *) shift ;;
  esac
done

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
SCANNER="$SELF_DIR/../scanner"
SCHEMA_YAML="$SELF_DIR/../schema.yaml"
CHAIN_STATE="${CHAIN_STATE_OVERRIDE:-$SCANNER/chain-state.sh}"
RECONCILE="${RECONCILE_OVERRIDE:-$SCANNER/reconcile.sh}"
SPEC_LINT="${SPEC_LINT_OVERRIDE:-$SCANNER/spec-lint.sh}"
DANGER_SCAN="${DANGER_SCAN_OVERRIDE:-$SCANNER/danger-scan.sh}"

# ── the harness payload, read AT MOST ONCE ───────────────────────────────
# stdin is a STREAM: whoever reads it first consumes it. The repo probe below
# used to `cat` it, and each event handler then `cat`-ed again for its own
# fields — a second read that returns EOF. Every adapter invokes this script
# WITHOUT --repo, so in production the probe always won the race and the
# handlers always got nothing: post-edit saw an empty file path and ran no
# check, and tool-call saw an empty path and classified every production edit
# as out of scope. Both tiers reported healthy while doing nothing.
#
# It survived the oracle because every test passes --repo, which skips the
# probe and leaves stdin intact for the handler — the tests exercised a path
# production never takes.
#
# Read ONCE, HERE, in the parent shell — not from inside payload_field.
# payload_field is called as `x="$(payload_field …)"`, and a command
# substitution is a subshell: a read done in there assigns to a copy of the
# variable that dies with it, so every later call would re-read a stream the
# first call had already drained. That is the same consume-once defect this
# block exists to fix, just relocated. Memoizing only works in the shell that
# keeps the value.
#
# Read only when something might actually want it: an event with a payload to
# parse, or a repo that still has to be discovered. The injection tier called
# with an explicit --repo never touches stdin, exactly as before.
PAYLOAD=""
case "$EVENT" in
  post-edit | tool-call | completion | post-bash) NEEDS_PAYLOAD=1 ;;
  *) NEEDS_PAYLOAD=0 ;;
esac
if { [ "$NEEDS_PAYLOAD" -eq 1 ] || [ -z "$REPO" ]; } && [ ! -t 0 ]; then
  PAYLOAD="$(cat 2>/dev/null || true)"
fi

# Reads one field from the payload. Empty when the payload is absent, when jq
# is unavailable, or when the field is not present — every caller treats empty
# as "not supplied".
payload_field() { # $1=jq filter
  [ -n "$PAYLOAD" ] || return 0
  command -v jq >/dev/null 2>&1 || return 0
  printf '%s' "$PAYLOAD" | jq -r "$1 // empty" 2>/dev/null
}

# ── locate the repository ────────────────────────────────────────────────
# Hooks are invoked from an unpredictable cwd, so the repo is resolved from
# the most explicit signal available and only then guessed.
if [ -z "$REPO" ]; then
  REPO="$(payload_field '.cwd')"
fi
[ -z "$REPO" ] && REPO="${CLAUDE_PROJECT_DIR:-}"
[ -z "$REPO" ] && REPO="$(git rev-parse --show-toplevel 2>/dev/null || true)"
[ -z "$REPO" ] && REPO="$PWD"

# ── session identity ─────────────────────────────────────────────────────
# Fingerprint-based suppression (moved here from the pi adapter, so every
# harness gets it, not only pi) needs to know which invocations belong to the
# SAME session. Resolution order, weakest assumption last:
#   1. --session ID          explicit, adapter-provided — the strongest signal
#   2. $CLAUDE_CODE_SESSION_ID  VERIFIED present in a live Claude Code hook
#      process (checked directly, not assumed, at the Step 1 gate)
#   3. $VERIFIED_SCALA3_SESSION_ID  a generic, harness-neutral override any
#      adapter can set — pi's adapter sets this to its own process PID, since
#      one pi process IS one session for the extension's lifetime
#   4. $PPID                 best-effort fallback for a harness with no
#      verified session signal (this is an INFERENCE, not a confirmed fact —
#      recorded as such; see design.md)
if [ -z "$SESSION" ]; then SESSION="${CLAUDE_CODE_SESSION_ID:-}"; fi
if [ -z "$SESSION" ]; then SESSION="${VERIFIED_SCALA3_SESSION_ID:-}"; fi
if [ -z "$SESSION" ]; then SESSION="ppid-$PPID"; fi
# The raw session id must become safe as a filename component. Found at
# Ring 8: `tr -c 'A-Za-z0-9._-' '_'` is LOSSY — it collapses every
# disallowed character to the same '_', so two genuinely different raw
# session ids that differ only outside that set (e.g. "abc!def" and
# "abc?def") sanitize to the identical "abc_def" and collide onto the same
# suppression-state file, wrongly treating a brand-new session as a repeat
# of another one. base64 (RFC 4648) is a lossless, total encoding of any
# byte string; substituting its three non-filename-safe characters
# (`+ / =`) for three that never otherwise appear in base64 output keeps it
# lossless while making it filename-safe — this is the standard
# "base64url" transform, just with `.` in place of stripped padding so the
# encoding stays a pure character substitution.
if command -v jq >/dev/null 2>&1; then
  SESSION="$(printf '%s' "$SESSION" | jq -Rr '@base64' | tr '+/=' '-_.')"
else
  # jq is a declared prerequisite (see hooks/README.md); this is a defensive
  # fallback only. It is intentionally still lossy — degrading to the OLD
  # collision-prone behaviour is preferable to failing a hook that must
  # never fail a session.
  SESSION="$(printf '%s' "$SESSION" | tr -c 'A-Za-z0-9._-' '_')"
fi

# ── state directory: heartbeat + per-session fingerprint, inside .git/ ───
# Per-repo (never committed — .git/ contents are never tracked), always
# writable if .git exists. If it does not (a repository before `git init`),
# state is simply not persisted; the gate still injects, just without
# suppression or a heartbeat for that call.
#
# `.git` is not always a directory: in a git WORKTREE it is a plain file
# (a "gitdir:" pointer), so `[ -d "$REPO/.git" ]` is false there and state
# was silently never persisted in any worktree — found by hand-verifying
# this very obligation from inside a worktree. `--absolute-git-dir` resolves
# either shape (ordinary repo, worktree, or submodule) to the real git-dir.
#
# D8 FIX: STATE_DIR creation is deferred to AFTER the relevance guard below.
# The previous version created .git/verified-scala3-gate/ in EVERY git repo
# the hook fired in, including non-openspec ones. Now the directory is only
# created in repos that pass the relevance guard (have an openspec/ dir).
STATE_DIR=""

# ── heartbeat: "did the gate run", without operator setup ────────────────
# The OLD verification path (VERIFIED_SCALA3_HOOKS_TRACE, kept below for
# backward compatibility) is INERT unless an operator exports it first, and a
# three-level manual procedure exists BECAUSE nothing automated reads it.
# This heartbeat is written AFTER the relevance guard, so only openspec
# repos get a .git/verified-scala3-gate/ directory.
write_heartbeat() {
  [ -n "$STATE_DIR" ] || return 0
  command -v jq >/dev/null 2>&1 || return 0
  jq -cn --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg event "$EVENT" --arg format "$FORMAT" \
    '{ts:$ts, event:$event, format:$format}' >"$STATE_DIR/heartbeat" 2>/dev/null || true
}

# ── --check-installed: pure read of the heartbeat, no side effects ───────
# Reads the heartbeat if it exists (from a prior run in an openspec repo)
# but does NOT create the state directory. This check works in any repo
# because it only reads — it never writes.
if [ "$CHECK_INSTALLED" -eq 1 ]; then
  hb=""
  git_dir_check="$(cd "$REPO" 2>/dev/null && git rev-parse --absolute-git-dir 2>/dev/null || true)"
  if [ -n "$git_dir_check" ] && [ -f "$git_dir_check/verified-scala3-gate/heartbeat" ]; then
    hb="$(cat "$git_dir_check/verified-scala3-gate/heartbeat" 2>/dev/null || true)"
  fi
  if [ -n "$hb" ] && command -v jq >/dev/null 2>&1 && printf '%s' "$hb" | jq -e . >/dev/null 2>&1; then
    ts="$(printf '%s' "$hb" | jq -r '.ts // empty')"
    ev="$(printf '%s' "$hb" | jq -r '.event // empty')"
    jq -cn --arg last_run "$ts" --arg event "$ev" '{installed:true, last_run:$last_run, event:$event}'
  else
    jq -cn '{installed:false, last_run:null, event:null}' 2>/dev/null ||
      printf '{"installed":false,"last_run":null,"event":null}\n'
  fi
  exit 0
fi

# ── trace: proof that the HARNESS invoked this, not just that it works ───
# Set VERIFIED_SCALA3_HOOKS_TRACE=/path/to/file to append one line per
# invocation. It records EVERY call, including the ones that emit nothing —
# because "fired and stayed silent" and "never fired" are indistinguishable
# from outside, and telling them apart is the whole point of verifying an
# install. Off unless the variable is set. RETAINED alongside the heartbeat
# above: this is opt-in and human-readable for manual debugging; the
# heartbeat is unconditional and machine-readable for automated checks —
# different audiences, not a duplicate mechanism.
trace() {
  [ -n "${VERIFIED_SCALA3_HOOKS_TRACE:-}" ] || return 0
  printf '%s  event=%-14s format=%-9s repo=%-42s %s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$EVENT" "$FORMAT" "$REPO" "$1" \
    >>"$VERIFIED_SCALA3_HOOKS_TRACE" 2>/dev/null || true
}

# ── relevance guard ──────────────────────────────────────────────────────
# A repository that does not use this workflow gets NOTHING. A hook installed
# globally must be invisible where it does not apply, or it becomes noise that
# people disable — and a disabled hook enforces nothing.
if [ ! -d "$REPO/openspec" ]; then
  trace "skip: no openspec/ here"
  exit 0
fi
if [ "${VERIFIED_SCALA3_HOOKS:-on}" = "off" ]; then
  trace "skip: VERIFIED_SCALA3_HOOKS=off"
  exit 0
fi

# ── state directory creation (AFTER relevance guard) ─────────────────────
# Only openspec repos get a .git/verified-scala3-gate/ directory. This
# prevents pollution of non-openspec git repos. (D8 fix.)
if git_dir="$(cd "$REPO" 2>/dev/null && git rev-parse --absolute-git-dir 2>/dev/null)" && [ -n "$git_dir" ]; then
  STATE_DIR="$git_dir/verified-scala3-gate"
  mkdir -p "$STATE_DIR" 2>/dev/null || STATE_DIR=""
fi

write_heartbeat

# ── checkpoint-presentation sweep (spec: human-grant-lock) ───────────────
# On EVERY event, scan for checkpoint-output files left by checkpoint.sh
# report. For each, hash the content, write a presentation record, and
# consume (delete) the output file. This makes "the agent ran the Step-13
# checkpoint command" a recorded fact the gate can check on the next event.
# If STATE_DIR is unavailable, this is silently skipped (fail-open).
if [ -n "$STATE_DIR" ] && [ -d "$STATE_DIR" ]; then
  for ckpt_out in "$STATE_DIR"/checkpoint-output-*-*-*; do
    [ -f "$ckpt_out" ] || continue
    # Parse change and spec from the filename: checkpoint-output-<change>-<spec>-<session>
    # The change name and spec name can contain hyphens, so we extract from
    # the end: the last segment is the session, the second-to-last is the
    # spec, and everything before is the change name (with the prefix).
    base_name="$(basename "$ckpt_out")"
    # Strip the "checkpoint-output-" prefix
    rest="${base_name#checkpoint-output-}"
    # Session is the last component
    ckpt_session="${rest##*-}"
    rest_without_session="${rest%-*}"
    # Spec is the last component of what remains
    ckpt_spec="${rest_without_session##*-}"
    ckpt_change="${rest_without_session%-*}"
    # Hash the content
    ckpt_hash="$(sha256sum "$ckpt_out" 2>/dev/null | cut -d' ' -f1)"
    if [ -n "$ckpt_hash" ] && [ -n "$ckpt_change" ] && [ -n "$ckpt_spec" ] && [ -n "$ckpt_session" ]; then
      printf '%s' "$ckpt_hash" >"$STATE_DIR/presentation-$ckpt_change-$ckpt_spec-$ckpt_session" 2>/dev/null || true
      trace "presentation recorded for $ckpt_change/$ckpt_spec (session $ckpt_session)"
    fi
    # Consume the output file regardless (don't reprocess on every event)
    rm -f "$ckpt_out" 2>/dev/null || true
  done
fi

# ── event: tool-call (pre-execution gate — oracle ordering lock) ─────────
# spec: oracle-ordering-lock. The universal blocking tier: refuses a
# write/edit to */src/main/** while the (change, spec) phase state is
# `oracle`, and transitions the phase forward only on recorded ledger
# evidence (RED run → implementation, GREEN run after RED → verified).
# The agent is never blocked from producing the unblocking evidence: test
# edits, change-artifact edits, and workflow-tooling edits are ALWAYS
# allowed. Same bounded-refusal discipline as completion: fail open when
# STATE_DIR is unavailable, one refusal per turn, escape hatch honored.
if [ "$EVENT" = "tool-call" ]; then
  # Resolve --file from the harness payload if not given on the command line
  # (same pattern as post-edit).
  if [ -z "$FILE_PATH" ]; then
    FILE_PATH="$(payload_field '.tool_input.file_path // .tool_input.path')"
    [ -z "$TOOL_NAME" ] && TOOL_NAME="$(payload_field '.tool_name')"
  fi

  # Normalize to absolute path (same as post-edit — relative paths match
  # neither the production nor the test path shape).
  case "$FILE_PATH" in
    /*) : ;;
    "") : ;;
    *) FILE_PATH="$REPO/$FILE_PATH" ;;
  esac

  # Only production source edits are in scope for the oracle lock.
  # */src/main/**/*.scala — the same shape danger-scan and post-edit use.
  is_prod_edit=0
  case "$FILE_PATH" in
    */src/main/*.scala) is_prod_edit=1 ;;
  esac

  if [ "$is_prod_edit" -eq 0 ]; then
    # spec: human-grant-lock — non-production paths that target spec N+1's
    # Step-0 signature (implementation-progress.md or spec N+1's spec dir)
    # are blocked if spec N has a presentation but no grant. This is the
    # tacit-approval fix: the agent cannot begin the next spec without a
    # human grant (a user prompt after the checkpoint).
    if [ -z "$STATE_DIR" ]; then
      trace "tool-call: non-production path, allow — $FILE_PATH (STATE_DIR unavailable)"
      exit 0
    fi

    # Discover the active change (same as the oracle phase check below)
    grant_change=""
    for chg in "$REPO"/openspec/changes/*/; do
      [ -d "$chg" ] || continue
      case "$chg" in */archive/*) continue ;; esac
      grant_change="$(basename "$chg")"
      break
    done

    if [ -n "$grant_change" ]; then
      # Determine if this file is a Step-0 signature for spec N+1:
      # 1. edits to implementation-progress.md
      # 2. edits under a spec dir
      is_step0_signature=0
      target_spec=""
      case "$FILE_PATH" in
        */openspec/changes/$grant_change/implementation-progress.md)
          is_step0_signature=1
          # Any edit to implementation-progress.md is treated as a Step-0
          # signature — the gate can't see which section is being edited.
          # The target spec is "the next spec after the last granted spec".
          ;;
        */openspec/changes/$grant_change/specs/*)
          is_step0_signature=1
          # Extract the spec name from the path
          spec_part="${FILE_PATH##*/openspec/changes/"$grant_change"/specs/}"
          target_spec="${spec_part%%/*}"
          ;;
      esac

      if [ "$is_step0_signature" -eq 1 ]; then
        # Get the ordered list of spec dirs in IMPLEMENTATION ORDER.
        # Read the order from implementation-order.md (the table's Spec
        # column has `specs/<name>/spec.md` entries in order). Fall back
        # to directory listing (alphabetical) only if the file is missing
        # or no specs are parsed from it.
        impl_order_file="$REPO/openspec/changes/$grant_change/implementation-order.md"
        spec_list=""
        if [ -f "$impl_order_file" ]; then
          # Extract spec names from `specs/<name>/spec.md` patterns in the
          # implementation order table and checklist. The table rows and
          # the checklist both contain these patterns in implementation order.
          spec_list="$(grep -oE 'specs/[^/` ]+/spec\.md' "$impl_order_file" \
            | sed 's|specs/||;s|/spec\.md||' \
            | awk '!seen[$0]++' \
            | tr '\n' ' ')"
        fi
        if [ -z "$spec_list" ]; then
          # Fallback: directory listing (alphabetical) if impl order unavailable
          for spec_d in "$REPO"/openspec/changes/$grant_change/specs/*/; do
            [ -d "$spec_d" ] || continue
            spec_list="$spec_list $(basename "$spec_d")"
          done
          spec_list="$(printf '%s' "$spec_list" | tr ' ' '\n' | sort | tr '\n' ' ')"
        fi

        # If target_spec is set (from a spec-dir path) and that spec is
        # already past the oracle phase (implementation or verified), the
        # Step-0 has already been done — no grant is needed. The grant lock
        # prevents STARTING a new spec without human approval, not fixing
        # or revising an already-started spec. Grants are session-scoped,
        # so without this check a completed spec's spec-dir would be
        # uneditable in every new session (the prior session's grant is
        # gone, but the spec is already done).
        if [ -n "$target_spec" ]; then
          target_phase_file="$STATE_DIR/phase-$grant_change-$target_spec"
          target_phase="oracle"
          [ -f "$target_phase_file" ] && target_phase="$(cat "$target_phase_file" 2>/dev/null || echo oracle)"
          case "$target_phase" in
            oracle | implementation | verified) ;;
            *) target_phase="oracle" ;;
          esac
          if [ "$target_phase" != "oracle" ]; then
            trace "tool-call: target spec $target_spec is $target_phase (past oracle), no grant needed"
            is_step0_signature=0
          fi
        fi

        # Find the first spec that has a presentation but no grant — that's
        # the spec N whose grant is required. The target is spec N+1.
        # If target_spec is set (from a spec-dir path), check that the
        # immediately prior spec has a grant.
        required_grant_spec=""
        prev_spec=""
        if [ "$is_step0_signature" -eq 1 ]; then
          for s in $spec_list; do
            if [ -n "$target_spec" ] && [ "$s" = "$target_spec" ]; then
              # The target is this spec; the required grant is for the prior spec
              required_grant_spec="$prev_spec"
              break
            fi
            # For implementation-progress.md edits, find the first spec with
            # a presentation but no grant — the next spec after it is the target
            pres_f="$STATE_DIR/presentation-$grant_change-$s-$SESSION"
            grant_f="$STATE_DIR/grant-$grant_change-$s-$SESSION"
            if [ -f "$pres_f" ] && [ ! -f "$grant_f" ]; then
              required_grant_spec="$s"
              break
            fi
            prev_spec="$s"
          done
        fi

        if [ -n "$required_grant_spec" ]; then
          # The grant is a durable record of human checkpoint approval.
          # It was originally session-scoped (grant-<change>-<spec>-<session>),
          # but that creates a contradiction with the separate-session-per-spec
          # discipline: a spec verified and approved in session A requires
          # re-approval in session B, which forces the agent to re-present
          # the prior spec's checkpoint (filling the fresh session with
          # implementation context) just to get a rubber-stamp grant.
          #
          # Two-layer fix:
          # 1. Check for a grant from ANY session, not just the current one.
          #    The human's approval is durable — if they approved spec N's
          #    checkpoint in any session, that approval stands.
          # 2. If no grant exists from any session, but the prior spec's
          #    phase is past `oracle` (implementation or verified), waive
          #    the grant. A phase past oracle means the spec was started
          #    with oracle evidence (RED run recorded). The grant mechanism
          #    is fragile — it depends on the prompt-submit handler matching
          #    the presentation to the session, which can fail on session
          #    ID mismatch or handler issues. The phase file is the durable
          #    on-disk record; if the spec is past oracle and the human is
          #    directing the agent to start the next spec, that IS the
          #    approval. Re-presenting a completed spec's checkpoint in
          #    every new session just to get a rubber-stamp grant defeats
          #    the separate-session-per-spec discipline.
          grant_f="$STATE_DIR/grant-$grant_change-$required_grant_spec-$SESSION"
          if [ ! -f "$grant_f" ]; then
            # No grant in the current session — check for one from any session
            any_grant="$(ls "$STATE_DIR"/grant-"$grant_change"-"$required_grant_spec"-* 2>/dev/null | head -1)"
            if [ -n "$any_grant" ]; then
              trace "tool-call: grant for $required_grant_spec found from a prior session: $(basename "$any_grant")"
              grant_f="$any_grant"
            fi
          fi
          if [ -f "$grant_f" ]; then
            trace "tool-call: grant satisfied for $required_grant_spec"
            required_grant_spec=""
          else
            # No grant from any session — check if the prior spec is past oracle
            grant_spec_phase="oracle"
            grant_spec_phase_file="$STATE_DIR/phase-$grant_change-$required_grant_spec"
            [ -f "$grant_spec_phase_file" ] && grant_spec_phase="$(cat "$grant_spec_phase_file" 2>/dev/null || echo oracle)"
            case "$grant_spec_phase" in
              oracle | implementation | verified) ;;
              *) grant_spec_phase="oracle" ;;
            esac
            if [ "$grant_spec_phase" != "oracle" ]; then
              trace "tool-call: required-grant spec $required_grant_spec is $grant_spec_phase (past oracle), waive grant"
              required_grant_spec=""
            fi
          fi
        fi

        if [ -n "$required_grant_spec" ]; then
          # Bounded refusal: one grant-refusal per turn
          grant_refusal_marker="$STATE_DIR/grant-refused-$SESSION"
          if [ -f "$grant_refusal_marker" ]; then
            trace "tool-call: already refused grant once this turn, allow"
            exit 0
          fi
          if [ ! -f "$grant_f" ]; then
            # Block: no grant for the required spec
            if ! : >"$grant_refusal_marker" 2>/dev/null; then
              trace "tool-call: failed to write grant-refusal marker, failing open"
              exit 0
            fi
            reason="$required_grant_spec grant: next-spec Step-0 blocked — no human grant for spec $required_grant_spec. A user prompt must arrive after the checkpoint presentation (prompt-submit event) to write the grant."
            trace "tool-call: refuse (missing grant for $required_grant_spec)"
            if [ "$FORMAT" = "text" ]; then
              printf '%s\n' "$reason" >&2
              exit 2
            fi
            if command -v jq >/dev/null 2>&1; then
              jq -cn --arg r "$reason" '{decision:"block", reason:$r}'
            else
              printf '{"decision":"block","reason":"%s"}\n' "$reason"
            fi
            exit 0
          fi
        fi
      fi
    fi

    trace "tool-call: non-production path, allow — $FILE_PATH"
    exit 0
  fi

  # Fail open when STATE_DIR is unavailable — a block without a bound is
  # not safe (same discipline as the completion gate).
  if [ -z "$STATE_DIR" ]; then
    trace "tool-call: allow — bounded-refusal state unavailable (STATE_DIR unset), failing open"
    exit 0
  fi

  # Bounded refusal: one block per turn. If already refused in this
  # session, allow (prevents a loop where every edit is blocked with no
  # way forward). Cleared on prompt-submit (new turn), same as completion.
  tool_refusal_marker="$STATE_DIR/tool-call-refused-$SESSION"
  if [ -f "$tool_refusal_marker" ]; then
    trace "tool-call: already refused once this turn, allow"
    exit 0
  fi

  # Discover the active change and spec from the file path.
  # The file path must be under openspec/changes/<change>/specs/<spec>/
  # for the phase to be relevant — but a PRODUCTION edit is NOT under
  # openspec/, so the change/spec is discovered from the ACTIVE change
  # (the first non-archived change dir), and the spec from the first
  # spec dir under it. This is deliberately coarse: the gate's job is to
  # prevent oracle inversion for the active change, and there is exactly
  # one active change at a time in the normal apply flow.
  active_change=""
  for chg in "$REPO"/openspec/changes/*/; do
    [ -d "$chg" ] || continue
    case "$chg" in */archive/*) continue ;; esac
    active_change="$(basename "$chg")"
    break
  done

  if [ -z "$active_change" ]; then
    trace "tool-call: no active change, allow"
    exit 0
  fi

  # Discover the active spec. Three strategies, in priority order:
  #
  # 0. MANUAL OVERRIDE (highest): if VERIFIED_SCALA3_ACTIVE_SPEC is set,
  #    use that spec's phase unconditionally. This lets the user pin the
  #    active spec when the automatic detection doesn't match intent —
  #    e.g. fixing a completed spec whose files the file-path mapping
  #    doesn't cover, or working out of order. The override is per-session
  #    (env var, not a state file), so it doesn't persist beyond the
  #    terminal that set it. Escape hatch: unset the var to return to
  #    automatic detection.
  #
  # 1. FILE-PATH MAPPING (precise): parse the Expected Changed Production
  #    Files table in implementation-order.md to find which spec owns the
  #    file being edited. If found, use that spec's phase. This correctly
  #    allows editing a verified spec's files (e.g. a fix to spec 4) while
  #    a later spec is still in oracle.
  #
  # 2. FIRST NON-VERIFIED (fallback): if the file isn't in the table (new
  #    file, unlisted file, or table missing), use the first spec in
  #    implementation order whose phase is not `verified`. This preserves
  #    the depth-first discipline for unlisted files.
  #
  # If all specs are verified, there is no active spec to gate (allow).
  active_spec=""
  chg_dir="$REPO/openspec/changes/$active_change"

  # Strategy 0: manual override via env var.
  if [ -n "${VERIFIED_SCALA3_ACTIVE_SPEC:-}" ]; then
    active_spec="$VERIFIED_SCALA3_ACTIVE_SPEC"
    trace "tool-call: active spec overridden via env var: $active_spec"
  fi

  # Read the ordered spec list from implementation-order.md (the table's
  # Spec column has `specs/<name>/spec.md` entries in order). Fall back to
  # directory listing (alphabetical) only if the file is missing or no
  # specs are parsed from it. Same pattern as the grant-lock section above.
  impl_order_file="$chg_dir/implementation-order.md"
  spec_list=""
  if [ -f "$impl_order_file" ]; then
    spec_list="$(grep -oE 'specs/[^/` ]+/spec\.md' "$impl_order_file" \
      | sed 's|specs/||;s|/spec\.md||' \
      | awk '!seen[$0]++' \
      | tr '\n' ' ')"
  fi
  if [ -z "$spec_list" ] && [ -d "$chg_dir/specs" ]; then
    for spec_dir in "$chg_dir"/specs/*/; do
      [ -d "$spec_dir" ] || continue
      spec_list="$spec_list $(basename "$spec_dir")"
    done
    spec_list="$(printf '%s' "$spec_list" | tr ' ' '\n' | sort | tr '\n' ' ')"
  fi

  # Strategy 1: map the edited file path to its owning spec via the
  # Expected Changed Production Files table. The table rows are:
  #   | # | spec-name | `relative/path/File.scala`, `...` |
  # Match the absolute FILE_PATH against $REPO/<backtick-quoted path>.
  # Skipped if the manual override (Strategy 0) already set active_spec.
  if [ -z "$active_spec" ] && [ -f "$impl_order_file" ]; then
    rel_path="${FILE_PATH#$REPO/}"
    owning_spec="$(awk -v repo="$REPO" -v fp="$FILE_PATH" -v rp="$rel_path" '
      /^## Expected Changed Production Files/ { in_table=1; next }
      in_table && /^## / { in_table=0 }
      in_table && /^\|/ {
        n = split($0, cols, "|")
        if (n >= 4) {
          spec = cols[3]; gsub(/^[ \t]+|[ \t]+$/, "", spec)
          if (spec == "" || spec ~ /^---/) next
          files = cols[4]
          while (match(files, /`[^`]+`/)) {
            p = substr(files, RSTART+1, RLENGTH-2)
            abs = repo "/" p
            if (abs == fp || p == rp) { print spec; exit }
            files = substr(files, RSTART+RLENGTH)
          }
        }
      }
    ' "$impl_order_file")"
    if [ -n "$owning_spec" ]; then
      active_spec="$owning_spec"
      trace "tool-call: file mapped to spec $active_spec via Expected Files table"
    fi
  fi

  # Strategy 2: fallback — first non-verified spec in implementation order.
  if [ -z "$active_spec" ]; then
    for s in $spec_list; do
      s_phase="oracle"
      s_phase_file="$STATE_DIR/phase-$active_change-$s"
      [ -f "$s_phase_file" ] && s_phase="$(cat "$s_phase_file" 2>/dev/null || echo oracle)"
      case "$s_phase" in
        oracle | implementation | verified) ;;
        *) s_phase="oracle" ;;
      esac
      if [ "$s_phase" != "verified" ]; then
        active_spec="$s"
        break
      fi
    done
    if [ -n "$active_spec" ]; then
      trace "tool-call: file not in Expected Files table, fallback to first non-verified: $active_spec"
    fi
  fi

  if [ -z "$active_spec" ]; then
    trace "tool-call: all specs verified for $active_change, allow"
    exit 0
  fi

  phase_file="$STATE_DIR/phase-$active_change-$active_spec"

  # Read current phase (default: oracle if no phase file exists)
  current_phase="oracle"
  [ -f "$phase_file" ] && current_phase="$(cat "$phase_file" 2>/dev/null || echo oracle)"
  case "$current_phase" in
    oracle | implementation | verified) ;;
    *) current_phase="oracle" ;;
  esac

  # The ledger file: the default location inside the change directory.
  ledger_file="$chg_dir/evidence-ledger.jsonl"

  # ── polarity predicate ───────────────────────────────────────────────
  # Pure function of ledger rows ⊕ git merge-base --is-ancestor.
  # Evaluates whether a RED or GREEN run exists for (change, spec) at a
  # baseline that is an ancestor of HEAD.
  head_sha="$(cd "$REPO" && git rev-parse HEAD 2>/dev/null || echo "")"

  # Check if a RED row exists (exit != 0, baseline ancestor of HEAD)
  # R8 FIX: the typed contract specifies .ring == "R3" — only test-execution
  # evidence (Ring 3) advances the oracle phase. A row from another ring
  # (e.g. R8 adversarial review) must NOT advance the phase, or a self-
  # assessed review could unlock implementation without a real test run.
  red_exists=0
  red_baseline=""
  if [ -n "$head_sha" ] && [ -f "$ledger_file" ] && command -v jq >/dev/null 2>&1; then
    while IFS= read -r line || [ -n "$line" ]; do
      [ -n "$line" ] || continue
      row_change="$(printf '%s' "$line" | jq -r '.change // empty' 2>/dev/null)"
      row_spec="$(printf '%s' "$line" | jq -r '.spec // empty' 2>/dev/null)"
      row_ring="$(printf '%s' "$line" | jq -r '.ring // empty' 2>/dev/null)"
      [ "$row_change" = "$active_change" ] && [ "$row_spec" = "$active_spec" ] || continue
      [ "$row_ring" = "R3" ] || continue
      row_exit="$(printf '%s' "$line" | jq -r '.exit // empty' 2>/dev/null)"
      row_base="$(printf '%s' "$line" | jq -r '.baseline // empty' 2>/dev/null)"
      [ -n "$row_exit" ] && [ -n "$row_base" ] || continue
      [ "$row_exit" != "0" ] || continue
      # Check ancestry: is row_base an ancestor of HEAD?
      if (cd "$REPO" && git merge-base --is-ancestor "$row_base" HEAD) 2>/dev/null; then
        red_exists=1
        red_baseline="$row_base"
        break
      fi
    done <"$ledger_file"
  fi

  # Check if a GREEN row exists (exit == 0, baseline ancestor of HEAD,
  # and descendant of the RED row's baseline). Same R3 ring requirement.
  green_exists=0
  if [ -n "$head_sha" ] && [ -f "$ledger_file" ] && command -v jq >/dev/null 2>&1; then
    while IFS= read -r line || [ -n "$line" ]; do
      [ -n "$line" ] || continue
      row_change="$(printf '%s' "$line" | jq -r '.change // empty' 2>/dev/null)"
      row_spec="$(printf '%s' "$line" | jq -r '.spec // empty' 2>/dev/null)"
      row_ring="$(printf '%s' "$line" | jq -r '.ring // empty' 2>/dev/null)"
      [ "$row_change" = "$active_change" ] && [ "$row_spec" = "$active_spec" ] || continue
      [ "$row_ring" = "R3" ] || continue
      row_exit="$(printf '%s' "$line" | jq -r '.exit // empty' 2>/dev/null)"
      row_base="$(printf '%s' "$line" | jq -r '.baseline // empty' 2>/dev/null)"
      [ -n "$row_exit" ] && [ -n "$row_base" ] || continue
      [ "$row_exit" = "0" ] || continue
      # Green row must be an ancestor of HEAD
      (cd "$REPO" && git merge-base --is-ancestor "$row_base" HEAD) 2>/dev/null || continue
      # If we have a RED baseline, green must be a descendant of it
      if [ -n "$red_baseline" ]; then
        (cd "$REPO" && git merge-base --is-ancestor "$red_baseline" "$row_base") 2>/dev/null || continue
      fi
      green_exists=1
      break
    done <"$ledger_file"
  fi

  # ── phase transitions (additive, read-only-into-state) ───────────────
  # oracle → implementation: on a recorded RED run
  # implementation → verified: on a GREEN run after a RED run
  new_phase="$current_phase"
  if [ "$current_phase" = "oracle" ] && [ "$red_exists" -eq 1 ]; then
    new_phase="implementation"
  fi
  if [ "$new_phase" = "implementation" ] && [ "$red_exists" -eq 1 ] && [ "$green_exists" -eq 1 ]; then
    new_phase="verified"
  fi

  # Write the new phase if it changed
  if [ "$new_phase" != "$current_phase" ]; then
    printf '%s' "$new_phase" >"$phase_file" 2>/dev/null || true
    trace "tool-call: phase $current_phase → $new_phase ($active_change/$active_spec)"
  fi

  # ── decision ─────────────────────────────────────────────────────────
  # Block only if the phase is still oracle (no RED run advanced it).
  # implementation and verified phases allow production edits.
  if [ "$new_phase" = "oracle" ]; then
    # R8 FIX: if the refusal marker cannot be written, fail open rather than
    # risk a deadlock where every tool-call in the turn is blocked with no
    # bound. Same discipline as the completion gate's bounded-refusal safety.
    if ! : >"$tool_refusal_marker" 2>/dev/null; then
      trace "tool-call: failed to write refusal marker, failing open to avoid deadlock"
      exit 0
    fi
    reason="$active_spec oracle: production edit blocked — the oracle phase has not advanced. Run the test oracle first (ledger.sh run -- … -- sbt <module>/test) to record a RED run and advance to implementation."
    trace "tool-call: refuse (oracle phase, $active_change/$active_spec)"
    if [ "$FORMAT" = "text" ]; then
      printf '%s\n' "$reason" >&2
      exit 2
    fi
    if command -v jq >/dev/null 2>&1; then
      jq -cn --arg r "$reason" '{decision:"block", reason:$r}'
    else
      printf '{"decision":"block","reason":"%s"}\n' "$reason"
    fi
    exit 0
  fi

  # ── predecessor check ────────────────────────────────────────────────
  # Depth-first discipline: a spec's production code may be edited only if
  # ALL specs before it in implementation order are `verified`. This prevents
  # starting spec N+1 while spec N is still in `implementation` (tests pass
  # but the checkpoint hasn't been presented and approved, or the GREEN run
  # hasn't been recorded yet).
  #
  # Override: set VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK=1 to skip this
  # check (e.g. for fixing a completed spec out of order, or when the
  # predecessor's phase file is stale). Per-session, same pattern as
  # VERIFIED_SCALA3_ACTIVE_SPEC.
  if [ -z "${VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK:-}" ]; then
    unverified_predecessor=""
    for s in $spec_list; do
      [ "$s" = "$active_spec" ] && break
      s_phase="oracle"
      s_phase_file="$STATE_DIR/phase-$active_change-$s"
      [ -f "$s_phase_file" ] && s_phase="$(cat "$s_phase_file" 2>/dev/null || echo oracle)"
      case "$s_phase" in
        oracle | implementation | verified) ;;
        *) s_phase="oracle" ;;
      esac
      if [ "$s_phase" != "verified" ]; then
        unverified_predecessor="$s"
        unverified_predecessor_phase="$s_phase"
        break
      fi
    done
    if [ -n "$unverified_predecessor" ]; then
      if ! : >"$tool_refusal_marker" 2>/dev/null; then
        trace "tool-call: failed to write refusal marker, failing open to avoid deadlock"
        exit 0
      fi
      reason="$active_spec blocked — predecessor spec $unverified_predecessor is $unverified_predecessor_phase (not verified). All prior specs must be verified before editing this spec's production code. Set VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK=1 to override."
      trace "tool-call: refuse (predecessor $unverified_predecessor not verified, $active_change/$active_spec)"
      if [ "$FORMAT" = "text" ]; then
        printf '%s\n' "$reason" >&2
        exit 2
      fi
      if command -v jq >/dev/null 2>&1; then
        jq -cn --arg r "$reason" '{decision:"block", reason:$r}'
      else
        printf '{"decision":"block","reason":"%s"}\n' "$reason"
      fi
      exit 0
    fi
  else
    trace "tool-call: predecessor check skipped (VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK set)"
  fi

  trace "tool-call: allow (phase $new_phase, $active_change/$active_spec)"
  exit 0
fi

# ── event: post-edit (Tier A', informational only — NEVER blocks) ────────
# spec: hook-tiers. Runs the relevant existing check immediately after a
# file edit and returns its findings; the file is already on disk by the
# time this runs, and nothing here touches, reverts, or rejects it.
if [ "$EVENT" = "post-edit" ]; then
  if [ -z "$FILE_PATH" ]; then
    FILE_PATH="$(payload_field '.tool_input.file_path // .tool_input.path')"
  fi

  # Ring 8 PARTIAL: both classification patterns below require a literal
  # leading `/` immediately before the matched segment (they match on
  # `*/openspec/changes/...` and `*/src/main/...`), so a RELATIVE path
  # (e.g. "openspec/changes/x/specs/y/spec.md", no leading slash) matched
  # neither and silently triggered nothing — reproduced directly with a
  # relative --file. Claude Code's own tools document absolute file_path,
  # but the field is adapter/harness-supplied, not gate.sh's own choice, so
  # this normalizes to absolute unconditionally rather than trusting it.
  case "$FILE_PATH" in
    /*) : ;;
    "") : ;;
    *) FILE_PATH="$REPO/$FILE_PATH" ;;
  esac

  findings=""

  # A spec file of an ACTIVE change: .../openspec/changes/<name>/specs/.../
  # spec.md, excluding anything under an archive/ path segment — the same
  # active/archived convention already used for change discovery below.
  is_spec_edit=0
  case "$FILE_PATH" in
    */openspec/changes/*/specs/*/spec.md)
      case "$FILE_PATH" in
        */archive/*) ;;
        *) is_spec_edit=1 ;;
      esac
      ;;
  esac
  if [ "$is_spec_edit" -eq 1 ]; then
    change_name="$(printf '%s' "$FILE_PATH" | sed -n 's#.*/openspec/changes/\([^/]*\)/specs/.*#\1#p')"
    chg_dir="$REPO/openspec/changes/$change_name"
    if [ -n "$change_name" ] && { [ -x "$SPEC_LINT" ] || [ -f "$SPEC_LINT" ]; }; then
      sl_out="$(cd "$REPO" && bash "$SPEC_LINT" --artifacts "$chg_dir" 2>&1 || true)"
      findings="$findings
spec-lint ($change_name):
$sl_out"
    else
      findings="$findings
spec-lint: check could not run (script not found or change name unresolved)"
    fi
  fi

  # Production source: mirrors danger-scan.sh's OWN file-selection rule
  # (*.scala under /src/main/) — never re-derives what counts as dangerous,
  # only which files are worth asking it about. Invoked bare (default
  # baseline=HEAD): the edit is uncommitted, so it already appears in
  # danger-scan's own `git diff HEAD` without needing `--also`.
  is_prod_edit=0
  case "$FILE_PATH" in
    *.scala)
      case "$FILE_PATH" in
        */src/main/*) is_prod_edit=1 ;;
      esac
      ;;
  esac
  if [ "$is_prod_edit" -eq 1 ]; then
    if [ -x "$DANGER_SCAN" ] || [ -f "$DANGER_SCAN" ]; then
      ds_out="$(cd "$REPO" && bash "$DANGER_SCAN" 2>&1 || true)"
      findings="$findings
danger-scan:
$ds_out"
    else
      findings="$findings
danger-scan: check could not run (script not found)"
    fi
  fi

  findings="$(printf '%s' "$findings" | sed '/^$/d')"
  trace "post-edit: file=$FILE_PATH findings=${#findings} chars"

  if [ -z "$findings" ]; then
    exit 0
  fi
  case "$FORMAT" in
    text)
      printf '%s\n' "$findings"
      ;;
    *)
      if command -v jq >/dev/null 2>&1; then
        jq -cn --arg ctx "$findings" \
          '{hookSpecificOutput:{hookEventName:"PostToolUse", additionalContext:$ctx}}'
      else
        esc="$(printf '%s' "$findings" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | sed ':a;N;$!ba;s/\n/\\n/g')"
        printf '{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"%s"}}\n' "$esc"
      fi
      ;;
  esac
  exit 0
fi

# ── event: post-bash (Tier A', observation only — NEVER blocks) ──────────
# spec: ambient-evidence-capture. After a bash tool call completes, match
# the command against an enumerated ring-shape table and append a ledger
# row with the harness-observed exit code. The row uses the existing
# ledger.sh append subcommand (no format change). Non-matching commands
# are simply not recorded. The gate ALWAYS exits 0 (post-hoc tier).
if [ "$EVENT" = "post-bash" ]; then
  # Always exit 0, regardless of what happens below
  _post_bash_exit0() { exit 0; }

  # ── recover the command and the OUTCOME from the harness payload ───────
  # A hook's `command` string can expand environment variables and
  # ${CLAUDE_PROJECT_DIR}-shaped placeholders ONLY — it cannot interpolate
  # tool_input.command or any other payload field. So the flags this handler
  # originally required could never be supplied by a real harness, and the
  # event was reachable only from a test. stdin is the only channel.
  #
  # THE OUTCOME PREDICATE. Established first-hand from this project's own
  # session transcripts, because the field is not documented:
  #
  #   tool_response is an OBJECT   → the command ran and succeeded. There is
  #                                  no exit field; exit 0 is what the shape
  #                                  itself means.
  #   tool_response is the STRING
  #     "Error: Exit code N"       → the command ran and returned N.
  #   any OTHER string             → NOT A COMMAND OUTCOME. "Error: This
  #                                  command requires approval" and "Error:
  #                                  Path does not exist: …" are the harness
  #                                  declining to run it. Reading "string ⇒
  #                                  failure" would file a permission denial
  #                                  as a red test run — a fabricated fact,
  #                                  and precisely the class of thing this
  #                                  ledger exists to make impossible.
  #   interrupted: true            → the run was cut short; its exit says
  #                                  nothing about the code under test.
  #
  # Anything not on that list records NOTHING. An unrecorded run leaves the
  # obligation visibly undischarged, which is a safe, self-correcting state;
  # a wrongly recorded one is evidence that has to be disproved later.
  if [ -z "$GATE_COMMAND" ]; then
    pb_tool="$(payload_field '.tool_name')"
    # Only the Bash tool carries a shell command to classify. Checked only on
    # the payload path — the flag path has no tool_name and is used by tests
    # that supply the command directly.
    if [ -n "$pb_tool" ] && [ "$pb_tool" != "Bash" ]; then
      trace "post-bash: tool is $pb_tool, not Bash — not recorded"
      _post_bash_exit0
    fi
    GATE_COMMAND="$(payload_field '.tool_input.command')"
    pb_verdict="$(payload_field '
      .tool_response as $r
      | if ($r | type) == "object" then
          (if $r.interrupted == true then "skip:interrupted" else "exit:0" end)
        elif ($r | type) == "string" then
          (if ($r | test("^Error: Exit code [0-9]+"))
           then "exit:" + ($r | capture("^Error: Exit code (?<n>[0-9]+)") | .n)
           else "skip:not-a-command-outcome" end)
        else "skip:unrecognised-response-shape" end')"
    case "$pb_verdict" in
      exit:*) GATE_EXIT="${pb_verdict#exit:}" ;;
      skip:*)
        trace "post-bash: ${pb_verdict#skip:} — not recorded"
        _post_bash_exit0
        ;;
      *)
        trace "post-bash: no outcome could be determined — not recorded"
        _post_bash_exit0
        ;;
    esac
  fi

  trace "post-bash: command='$GATE_COMMAND' exit=$GATE_EXIT"

  # Fail open: no command or exit → nothing to record
  [ -n "$GATE_COMMAND" ] || _post_bash_exit0
  [ -n "$GATE_EXIT" ] || _post_bash_exit0

  # De-duplication: if the command is an explicit ledger.sh run, skip
  # (the explicit run already recorded a row with sha256/digest/wallTime)
  case "$GATE_COMMAND" in
    *ledger.sh\ run*) trace "post-bash: de-duplicated (explicit ledger.sh run)"; _post_bash_exit0 ;;
  esac

  # ── the exit must BELONG to the ring command ──────────────────────────
  # A shell reports the status of the LAST element of a pipeline or chain, so
  # `bats tests/ | grep -E "^not ok"` exits 0 when grep matches — a suite with
  # failures recorded as a green run. Caught in live operation: this handler's
  # own first production row claimed exit 0 for a bats run that had eight
  # failing tests, because the command ended in `tail`.
  #
  # There is no way to recover the ring command's own status from a compound
  # one, so a compound command is NOT EVIDENCE and records nothing. The agent
  # can always run the ring bare, or drive it through `ledger.sh run --`,
  # which executes the command itself and reads its own $?.
  #
  # Redirections are fine — `sbt test >log 2>&1` still exits with sbt's
  # status. Only the operators that hand the exit to another command matter.
  # `*'&'*` is NOT among these: it would reject `sbt test 2>&1`, whose exit is
  # sbt's own. Backgrounding is caught by the trailing-`&` pattern instead.
  # The newline pattern is written with $'\n' — `$(printf '\n')` substitutes to
  # the EMPTY string (command substitution strips trailing newlines), which
  # turns the pattern into `**` and silently rejects every command.
  case "$GATE_COMMAND" in
    *'|'* | *';'* | *'&&'* | *'&' | *$'\n'*)
      trace "post-bash: compound command — its exit is not the ring's, not recorded"
      _post_bash_exit0
      ;;
  esac

  # Ring-shape match table (enumerated, conservative)
  # Patterns matched with bash case globs. Only commands the apply
  # instruction names as ring executions are in the table.
  #
  # Every obligation here is prefixed "ambient:" ON PURPOSE. chain-state.sh
  # binds a row to an obligation by EXACT string equality against the names
  # in a spec's Proof Obligations table, so a prefixed name can never match
  # one — an ambient row therefore discharges NOTHING, by construction. That
  # is the intended power: this table observes that a ring RAN, and only the
  # agent can say which named obligation a run discharges. A shape matcher
  # that guessed at obligation names would be inventing the one part of the
  # record it has no way to know.
  match_ring="" match_obligation="" match_artifact=""
  case "$GATE_COMMAND" in
    # Conservative: must START with "sbt" and contain "test" somewhere.
    # No leading wildcard — `echo "sbt test"` does not match (doesn't
    # start with sbt). The trailing * handles args after test (e.g.
    # `sbt adk4s-core/test -- --excluded-tags=Slow`).
    #
    # R3, not R0: the oracle-ordering lock advances a spec's phase only on
    # `.ring == "R3"` rows. Filed as R0, a captured test run was invisible to
    # the one gate it most needs to feed — capture would have recorded the
    # red run and still left the agent blocked from acting on it.
    sbt\ *test* | bats\ * | *"/bats "*)
      match_ring="R3"
      match_obligation="ambient: test execution"
      match_artifact="tests/"
      ;;
    # Script patterns: leading * handles path prefixes (the command may
    # be `openspec/.../danger-scan.sh <sha>`). Trailing * handles args.
    #
    # R1, not R8. danger-scan is a static scan; R8 is the adversarial review
    # a fresh-context agent performs. Filing the scan as R8 would enter a
    # judgment ring's evidence for a run that made no judgment — and R8 rows
    # additionally carry the fresh-context mandate, so the row would assert a
    # review that never happened.
    *danger-scan.sh*)
      match_ring="R1"
      match_obligation="ambient: danger scan"
      match_artifact="openspec/schemas/verified-scala3/scanner/danger-scan.sh"
      ;;
    *registry-check.sh*)
      match_ring="R1"
      match_obligation="ambient: registry check"
      match_artifact="openspec/schemas/verified-scala3/scanner/registry-check.sh"
      ;;
    *spec-lint.sh*)
      match_ring="R1"
      match_obligation="ambient: spec lint"
      match_artifact="openspec/schemas/verified-scala3/scanner/spec-lint.sh"
      ;;
    *checkpoint.sh\ report*)
      # Checkpoint is not a ring — it's the presentation, not evidence
      trace "post-bash: checkpoint.sh report is not a ring shape"
      _post_bash_exit0
      ;;
    *)
      # Non-matching command — not recorded
      trace "post-bash: no ring shape match, not recorded"
      _post_bash_exit0
      ;;
  esac

  # We have a match — append a ledger row
  # Fail open: if STATE_DIR unavailable, skip recording
  [ -n "$STATE_DIR" ] || { trace "post-bash: STATE_DIR unavailable, skipping record"; _post_bash_exit0; }

  # Discover the active change (same as tool-call handler)
  postbash_change=""
  for chg in "$REPO"/openspec/changes/*/; do
    [ -d "$chg" ] || continue
    case "$chg" in */archive/*) continue ;; esac
    postbash_change="$(basename "$chg")"
    break
  done

  # Derive spec: find the first spec with a presentation but no grant
  # (the spec currently being worked on), or "unknown" if not derivable
  postbash_spec="unknown"
  if [ -n "$postbash_change" ] && [ -d "$REPO/openspec/changes/$postbash_change/specs" ]; then
    for spec_d in "$REPO"/openspec/changes/$postbash_change/specs/*/; do
      [ -d "$spec_d" ] || continue
      s_name="$(basename "$spec_d")"
      pres_f="$STATE_DIR/presentation-$postbash_change-$s_name-$SESSION"
      grant_f="$STATE_DIR/grant-$postbash_change-$s_name-$SESSION"
      # If there's a presentation and a grant, this spec is done — skip
      # If there's a presentation but no grant, this is the current spec
      # If there's no presentation, this might be the current spec
      if [ -f "$pres_f" ] && [ ! -f "$grant_f" ]; then
        postbash_spec="$s_name"
        break
      fi
    done
    # If no spec with presentation-but-no-grant found, try the first spec
    if [ "$postbash_spec" = "unknown" ]; then
      for spec_d in "$REPO"/openspec/changes/$postbash_change/specs/*/; do
        [ -d "$spec_d" ] || continue
        postbash_spec="$(basename "$spec_d")"
        break
      done
    fi
  fi

  # Derive baseline: current HEAD SHA
  postbash_baseline="$(cd "$REPO" 2>/dev/null && git rev-parse --short HEAD 2>/dev/null || echo "0000000")"

  # Locate the ledger file
  postbash_ledger=""
  if [ -n "$postbash_change" ]; then
    postbash_ledger="$REPO/openspec/changes/$postbash_change/evidence-ledger.jsonl"
  fi

  # Locate ledger.sh
  postbash_ledger_sh="$SCANNER/ledger.sh"
  [ -f "$postbash_ledger_sh" ] || { trace "post-bash: ledger.sh not found, skipping"; _post_bash_exit0; }

  # Append the row using ledger.sh append (existing subcommand, no format change)
  # spec:judgment-ring-provenance — R8 rows require a session field; pass
  # $SESSION when the ring is R8 and session is available.
  if [ -n "$postbash_ledger" ] && [ -n "$postbash_change" ] && [ -n "$postbash_spec" ] && [ -n "$match_ring" ]; then
    postbash_session_args=()
    if [ "$match_ring" = "R8" ] && [ -n "$SESSION" ]; then
      postbash_session_args=(--session "$SESSION")
    fi
    "$postbash_ledger_sh" append \
      --file "$postbash_ledger" \
      --change "$postbash_change" \
      --spec "$postbash_spec" \
      --ring "$match_ring" \
      --obligation "$match_obligation" \
      --artifact "$match_artifact" \
      --command "$GATE_COMMAND" \
      --exit "$GATE_EXIT" \
      --baseline "$postbash_baseline" \
      --source ambient \
      "${postbash_session_args[@]}" 2>&1 | while IFS= read -r line; do trace "post-bash: ledger: $line"; done
    trace "post-bash: row appended (ring=$match_ring, exit=$GATE_EXIT)"
  else
    trace "post-bash: missing required fields, skipping record"
  fi

  _post_bash_exit0
fi

# ── event: completion (Tier A — the ONE genuine block in this schema) ────
# spec: hook-tiers. Refuses turn completion when the turn asserts a
# checkpoint/spec/ring result while chain state reports unresolved
# requirements, or is itself undetermined. Bounded to at most one refusal
# per turn: honours stop_hook_active directly when the harness provides it
# (Claude Code's own "I am already re-invoking after your prior block"
# signal — never re-block on it), and falls back to a session-scoped state
# file for harnesses that do not, cleared on the next prompt-submit (which
# already marks a new turn's start — see below).
if [ "$EVENT" = "completion" ]; then
  if [ "$STOP_HOOK_ACTIVE_GIVEN" -eq 0 ] && [ -z "$TURN_TEXT" ]; then
    STOP_HOOK_ACTIVE="$(payload_field '.stop_hook_active')"
  fi

  if [ "$STOP_HOOK_ACTIVE" = "true" ]; then
    trace "completion: stop_hook_active=true, allow"
    exit 0
  fi

  # ── trigger: checkpoint presentation marker (deterministic) ────────────
  # The original trigger was text matching on the turn transcript —
  # approximate by design, and it failed in practice: a case-sensitive regex
  # let "Spec 3/7 — COMPLETE" slip through the blocking gate entirely. The
  # deterministic replacement is the checkpoint presentation marker.
  #
  # checkpoint.sh report writes a checkpoint-output-* file to STATE_DIR. The
  # sweep at the top of every event (lines 278-302) consumes it and writes a
  # presentation-<change>-<spec>-<session> marker. If that marker exists for
  # this session, the agent has explicitly run the checkpoint command — that
  # is the deterministic signal that a completion claim is being made, not a
  # paraphrased string in a transcript of unknown format.
  #
  # If no marker exists, the agent has not run checkpoint.sh this session, so
  # there is no completion claim to validate. Mid-work stops pass silently.
  if [ -z "$STATE_DIR" ]; then
    trace "completion: no STATE_DIR, no marker check possible, allow"
    exit 0
  fi
  has_presentation=0
  for pres_file in "$STATE_DIR"/presentation-*-*-"$SESSION"; do
    [ -f "$pres_file" ] && { has_presentation=1; break; }
  done
  if [ "$has_presentation" -eq 0 ]; then
    trace "completion: no checkpoint presentation marker for this session, allow"
    exit 0
  fi
  trace "completion: checkpoint presentation marker found, running chain-state"

  # Ring 8 FAIL (critical): the bounded-refusal guarantee is implemented
  # ENTIRELY via this marker file. If STATE_DIR could not be established
  # (no .git yet, unwritable .git, disk full — all real, already-anticipated
  # conditions for the injection tier, where the consequence was cosmetic),
  # `refusal_marker` is empty, the "already refused" check can never be
  # true, and every completion attempt for the session refuses again with
  # NO BOUND AT ALL — reproduced directly: 3 consecutive refusals with no
  # .git present, exit 1/1/1, never 1/0/0. That is the exact "blocking hook
  # that misfires and cannot be escaped" failure this schema's own
  # documentation (docs/11-enforcement.html) warns is "genuinely difficult
  # to escape". A blocking decision may ONLY be made when the mechanism
  # that bounds it is actually available; otherwise this event fails OPEN
  # (allows, and says so in the trace) rather than blocking without a safety
  # net.
  refusal_marker=""
  [ -n "$STATE_DIR" ] && refusal_marker="$STATE_DIR/completion-refused-$SESSION"
  if [ -z "$refusal_marker" ]; then
    trace "completion: allow — bounded-refusal state unavailable (STATE_DIR unset), refusing without a bound is not safe"
    exit 0
  fi
  if [ -f "$refusal_marker" ]; then
    trace "completion: already refused once this turn, allow"
    exit 0
  fi

  gate_baseline="$(cd "$REPO" && git rev-parse HEAD 2>/dev/null || echo unknown)"
  completion_undetermined=0
  completion_unresolved=""
  completion_uncorroborated=""
  for chg in "$REPO"/openspec/changes/*/; do
    [ -d "$chg" ] || continue
    case "$chg" in */archive/*) continue ;; esac
    name="$(basename "$chg")"

    # ── does anything corroborate the rows the agent wrote? ─────────────
    # chain-state answers "is every requirement discharged"; it reads the
    # ledger and believes it. This asks the prior question — whether a row
    # asserting a green run has any support beyond the assertion itself.
    # Without it, backfilling a ledger at the end of the work produces
    # exactly the same clean chain state as recording each run as it
    # happened, which is what made the ledger testimony rather than evidence.
    #
    # ONLY exit 1 (a real finding) blocks. Exit 2 means reconcile could not
    # read the ledger at all, which is precisely the case chain-state already
    # reports as undetermined below — treating it as a second, separate
    # refusal would refuse twice for one condition and name the wrong cause.
    if [ -f "$chg/evidence-ledger.jsonl" ] && { [ -x "$RECONCILE" ] || [ -f "$RECONCILE" ]; }; then
      rc_out="$(cd "$REPO" && bash "$RECONCILE" --file "$chg/evidence-ledger.jsonl" \
        --change "$name" --format text 2>&1)"
      rc_exit=$? # captured immediately: any command in between overwrites $?
      if [ "$rc_exit" -eq 1 ]; then
        completion_uncorroborated="$completion_uncorroborated
  $rc_out"
      fi
    fi

    if [ -x "$CHAIN_STATE" ] || [ -f "$CHAIN_STATE" ]; then
      cs_out="$(cd "$REPO" && bash "$CHAIN_STATE" --change-dir "$chg" --change "$name" --baseline "$gate_baseline" 2>/dev/null)"
      cs_exit=$?
    else
      cs_out=""
      cs_exit=127
    fi
    if { [ "$cs_exit" -eq 0 ] || [ "$cs_exit" -eq 1 ]; } &&
      command -v jq >/dev/null 2>&1 &&
      printf '%s' "$cs_out" | jq -e '(.total | type) == "number"' >/dev/null 2>&1; then
      n_unresolved="$(printf '%s' "$cs_out" | jq -r '.unresolved | length')"
      if [ "$n_unresolved" -gt 0 ]; then
        names="$(printf '%s' "$cs_out" | jq -r '
          if (.unresolved | length) > 10
          then (.unresolved | .[0:10] | map("    " + .requirement + " (" + (.reasons | join(",")) + ")") | join("\n")) + "\n    +\((.unresolved | length) - 10) more"
          else (.unresolved | map("    " + .requirement + " (" + (.reasons | join(",")) + ")") | join("\n")) end')"
        completion_unresolved="$completion_unresolved
  $name:
$names"
      fi
    else
      completion_undetermined=1
    fi
  done

  # Ring 8 FAIL (critical): emitting the refusal purely via SCRIPT exit code
  # (1 for unresolved, 2 for undetermined) is not what either real harness
  # this schema targets actually honours. Verified against this project's
  # OWN already-approved reference doc (docs/11-enforcement.html) and
  # Claude Code's documented hook contract: Claude Code treats exit 1 as a
  # non-blocking error (the turn proceeds — the refusal never happens at
  # all), and reads JSON from stdout ONLY on exit 0; on exit 2 it reads the
  # reason from STDERR, not stdout. The `--format text` exit codes below are
  # kept as this script's own scriptable/testable contract (0/1/2, matching
  # every other tool in this project) — but for `hook-json`, the ONLY signal
  # a real harness actually reads is the `decision:block` JSON on an exit-0
  # response, so that is what is emitted, uniformly for both refusal
  # reasons, regardless of the script-level exit code chosen below.
  if [ "$completion_undetermined" -eq 1 ]; then
    : >"$refusal_marker" 2>/dev/null || true
    reason="completion refused: evidence could not be determined for at least one active change (chain state undetermined)"
    trace "completion: refuse (undetermined)"
    if [ "$FORMAT" = "text" ]; then
      printf '%s\n' "$reason"
      exit 2
    fi
    if command -v jq >/dev/null 2>&1; then
      jq -cn --arg r "$reason" '{decision:"block", reason:$r}'
    else
      printf '{"decision":"block","reason":"%s"}\n' "$reason"
    fi
    exit 0
  fi

  # Checked BEFORE "unresolved": if a claim has no witness, the chain state
  # computed from it is not a weaker result but a result over rows that may
  # not describe anything that ran. Naming "unresolved requirements" first
  # would send the agent off to discharge obligations using the same
  # unwitnessed mechanism that produced the problem.
  if [ -n "$completion_uncorroborated" ]; then
    : >"$refusal_marker" 2>/dev/null || true
    reason="completion refused: ledger rows are uncorroborated — a green row was written by hand with no witness that the command ran. Re-run the ring under 'ledger.sh run -- <command>' (which observes its own exit), or let the post-bash hook record it.$completion_uncorroborated"
    trace "completion: refuse (uncorroborated)"
    if [ "$FORMAT" = "text" ]; then
      printf '%s\n' "$reason"
      exit 1
    fi
    if command -v jq >/dev/null 2>&1; then
      jq -cn --arg r "$reason" '{decision:"block", reason:$r}'
    else
      printf '{"decision":"block","reason":"%s"}\n' "$reason"
    fi
    exit 0
  fi

  if [ -n "$completion_unresolved" ]; then
    : >"$refusal_marker" 2>/dev/null || true
    reason="completion refused: unresolved requirements remain —$completion_unresolved"
    trace "completion: refuse (unresolved)"
    if [ "$FORMAT" = "text" ]; then
      printf '%s\n' "$reason"
      exit 1
    fi
    if command -v jq >/dev/null 2>&1; then
      jq -cn --arg r "$reason" '{decision:"block", reason:$r}'
    else
      printf '{"decision":"block","reason":"%s"}\n' "$reason"
    fi
    exit 0
  fi

  trace "completion: allow (fully discharged)"
  exit 0
fi

# A new turn begins at prompt-submit — also the natural point to clear any
# completion-gate or tool-call-gate refusal recorded for the PREVIOUS turn,
# so a refusal never carries into the next one without needing a native
# turn-id from every harness (Scenario: "refusal state does not carry into
# the next turn").
#
# spec: human-grant-lock — prompt-submit is also the ONLY event that writes
# grant tokens. A grant for spec N is written when a presentation record for
# spec N exists (the agent ran the Step-13 checkpoint). The grant links to
# the presentation by copying its hash. No other event writes grants.
if [ "$EVENT" = "prompt-submit" ] && [ -n "$STATE_DIR" ]; then
  rm -f "$STATE_DIR/completion-refused-$SESSION" 2>/dev/null || true
  rm -f "$STATE_DIR/tool-call-refused-$SESSION" 2>/dev/null || true
  rm -f "$STATE_DIR/grant-refused-$SESSION" 2>/dev/null || true

  # Write grants for all specs that have a presentation record but no grant yet
  for pres_file in "$STATE_DIR"/presentation-*-*-"$SESSION"; do
    [ -f "$pres_file" ] || continue
    pres_base="$(basename "$pres_file")"
    pres_rest="${pres_base#presentation-}"
    pres_rest_no_session="${pres_rest%-*}"
    pres_spec="${pres_rest_no_session##*-}"
    pres_change="${pres_rest_no_session%-*}"
    grant_file="$STATE_DIR/grant-$pres_change-$pres_spec-$SESSION"
    # Write the grant only if it doesn't already exist (idempotent)
    if [ ! -f "$grant_file" ]; then
      pres_hash="$(cat "$pres_file" 2>/dev/null)"
      if [ -n "$pres_hash" ]; then
        printf '%s' "$pres_hash" >"$grant_file" 2>/dev/null || true
        trace "grant written for $pres_change/$pres_spec (session $SESSION)"
      fi
    fi
  done
fi

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
active_changes=""
for chg in "$REPO"/openspec/changes/*/; do
  [ -d "$chg" ] || continue
  case "$chg" in */archive/*) continue ;; esac
  name="$(basename "$chg")"
  active_changes="$active_changes$name"$'\n'
  present=""
  next=""
  while IFS="$(printf '\t')" read -r aid gen; do
    [ -z "${gen:-}" ] && continue
    case "$gen" in
      */*\**) exists=$([ -d "$chg/specs" ] && echo 1 || echo 0) ;; # specs/**/*.md
      *) exists=$([ -f "$chg/$gen" ] && echo 1 || echo 0) ;;
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

# ── section 3: THE INVARIANT + CHAIN STATE ───────────────────────────────
# The standing statement this spec adds: not workflow POSITION (where the
# agent is) but what must be true of anything it says, plus the LIVE count of
# requirements that do not yet satisfy that definition, named — not just
# counted — so a claim of completeness cannot sit three lines under an
# unresolved count without contradiction.
# Verbatim match to schema.yaml's canonical text (checked, not assumed —
# capitalisation matters for a substring-matching test, and matters more for
# a human reader's eye landing on the same phrase every time).
invariant_text="verified-scala3 — invariant (schema v${schema_ver:-?})
  NEVER LET A CLAIM OUTRUN ITS EVIDENCE.
  \"N/A\" / \"passes\" / \"already handled\" are CLAIMS, not verdicts."

chain_state_section=""
if [ -n "$active_changes" ]; then
  # The baseline chain-state.sh requires is not centrally recorded anywhere
  # else; the natural, already-defined value is the CURRENT tip of the
  # branch, which is what Step 0 records as a spec's baseline the moment it
  # begins. See design.md.
  gate_baseline="$(cd "$REPO" && git rev-parse HEAD 2>/dev/null || echo unknown)"
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    chg="$REPO/openspec/changes/$name"
    cs_out="" cs_exit=0
    if [ -x "$CHAIN_STATE" ] || [ -f "$CHAIN_STATE" ]; then
      cs_out="$(cd "$REPO" && bash "$CHAIN_STATE" --change-dir "$chg" --change "$name" --baseline "$gate_baseline" 2>/dev/null)"
      cs_exit=$?
    else
      cs_exit=127
    fi
    # A genuine chain-state.sh report is one of two disjoint shapes: the
    # undetermined shape (no "total" key at all) or the clean shape (numeric
    # "total"). `has("total")` alone was found at Ring 8 to also accept a
    # report claiming BOTH undetermined AND a present-but-null "total" — a
    # shape the real chain-state.sh contract never produces, but which a
    # test stub or a future misbehaving override could, and which would
    # then render as a clean "0 unresolved" instead of undetermined.
    # Requiring "total" to actually BE a number is the direct check for what
    # this code needs, with no assumption about chain-state.sh's internals.
    if { [ "$cs_exit" -eq 0 ] || [ "$cs_exit" -eq 1 ]; } &&
      command -v jq >/dev/null 2>&1 &&
      printf '%s' "$cs_out" | jq -e '(.total | type) == "number"' >/dev/null 2>&1; then
      # a genuine measurement — render it, never collapsed to a lone verdict
      # "unresolved N" is rendered EXPLICITLY, always — not inferred from
      # whether a requirement list follows. A clean change (Scenario: "a
      # clean change reports zero without a list") must still show the
      # count itself, per Requirement 1's Then clause; the list is what's
      # conditional, not the number.
      rendered="$(printf '%s' "$cs_out" | jq -r '
        "    total \(.total)  bound \(.bound)  resolved \(.resolved)  discharged \(.discharged)  unresolved \(.unresolved | length)"
        + (if (.unresolved | length) > 0
           then (if (.unresolved | length) > 10
                  then "\n" + (.unresolved | .[0:10] | map("    " + .requirement + " (" + (.reasons | join(",")) + ")") | join("\n")) + "\n    +\((.unresolved | length) - 10) more"
                  else "\n" + (.unresolved | map("    " + .requirement + " (" + (.reasons | join(",")) + ")") | join("\n")) end)
           else "" end)')"
      chain_state_section="$chain_state_section
  chain state           $name
$rendered"
    else
      # evidence could not be obtained — reported as such, NEVER as a clean
      # zero: the exact defect class this whole change exists to remove.
      chain_state_section="$chain_state_section
  chain state           $name
    undetermined — chain state could not be computed (chain-state.sh exit $cs_exit)"
    fi
  done <<<"$active_changes"
fi

if [ -z "$context" ] && [ -z "$position" ] && [ -z "$chain_state_section" ]; then
  trace "skip: nothing to report (no facts, no active change)"
  exit 0
fi

# ── assemble ─────────────────────────────────────────────────────────────
body="$invariant_text

verified-scala3 — session context (schema v${schema_ver:-?}, injected by hooks/gate.sh)
${context}${position}${chain_state_section}

  gate checks          scanner/spec-lint.sh · registry-check.sh · danger-scan.sh · scanner/chain-state.sh

The lines above were READ FROM DISK just now; they are facts, not
recollection. Where a conditional check is marked APPLIES, \"N/A\" is not a
valid verdict for it — see the spec-lint artifact instruction. An unresolved
requirement listed above is a finding, not a formality."

# ── suppression: re-inject only when the facts changed, THIS session ─────
# Fingerprinted on the ASSEMBLED FACTS (this variable), not on the rendered
# hook-json/text output — so switching --format between calls cannot cause
# spurious re-injection or spurious suppression on formatting alone.
# Direct text comparison, not a hash: simpler, no format assumptions, and the
# body is small enough that hashing buys nothing.
if [ -n "$STATE_DIR" ]; then
  fp_file="$STATE_DIR/fp-$SESSION"
  old_body=""
  [ -f "$fp_file" ] && old_body="$(cat "$fp_file" 2>/dev/null || true)"
  if [ "$body" = "$old_body" ]; then
    trace "skip: unchanged since last injection this session ($SESSION)"
    exit 0
  fi
  printf '%s' "$body" >"$fp_file" 2>/dev/null || true
fi

# ── emit ─────────────────────────────────────────────────────────────────
trace "emit: ${#body} chars"
case "$FORMAT" in
  text)
    printf '%s\n' "$body"
    ;;
  hook-json)
    case "$EVENT" in
      session-start) ev="SessionStart" ;;
      prompt-submit) ev="UserPromptSubmit" ;;
      *) ev="SessionStart" ;;
    esac
    if command -v jq >/dev/null 2>&1; then
      # jq is a declared prerequisite as of schema v12 (see ./README.md);
      # this replaces the hand-rolled sed/awk escaping that existed only to
      # honour the superseded zero-dependency rule. The hand-rolled path is
      # kept below as a defensive fallback — "never blocks" applies even to
      # jq going missing at runtime, however unlikely.
      jq -cn --arg event "$ev" --arg ctx "$body" \
        '{hookSpecificOutput:{hookEventName:$event, additionalContext:$ctx}}'
    else
      # Ring 8: the line-oriented `awk '{printf "%s\\n", $0}'` appended a
      # literal `\n` after EVERY line, including the last — an extra
      # trailing newline inside additionalContext that the jq path above
      # does not produce (it preserves $body's real content exactly). The
      # sed-slurp form below replaces embedded newlines in place without
      # adding one after the final line, matching jq's output exactly.
      esc="$(printf '%s' "$body" |
        sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' |
        sed ':a;N;$!ba;s/\n/\\n/g')"
      printf '{"hookSpecificOutput":{"hookEventName":"%s","additionalContext":"%s"}}\n' "$ev" "$esc"
    fi
    ;;
  *)
    printf '%s\n' "$body"
    ;;
esac
exit 0
