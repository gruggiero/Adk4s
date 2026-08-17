#!/usr/bin/env bash
# checkpoint.sh — generates the per-spec checkpoint from recorded evidence.
#
# spec: checkpoint-from-ledger (change: add-correctness-substratum).
#
# WHY THIS EXISTS
# The apply workflow's Step 13 used to ask the agent to WRITE a per-ring
# result list from memory, at the single moment a human decides whether to
# approve the next spec. Nothing compared that self-report against what
# actually ran. This script replaces authoring with generation: every ring
# outcome it reports traces to a ledger.sh row at the CURRENT baseline, or is
# shown as having no recorded evidence — never inferred, never accepted as
# free text.
#
# NEVER REIMPLEMENTED: row filtering by change/spec/baseline is ALWAYS
# ledger.sh's own `read` (spec 2, already approved) — this script's `report`
# subcommand never re-parses raw ledger lines itself. Chain-state counts are
# ALWAYS chain-state.sh's own report (spec 3, already approved), passed in
# whole via --chain-state-json — never recomputed here.
#
# --baseline IS ALWAYS PASSED to ledger.sh read, by this script's own
# discipline — the same layered pattern chain-state.sh already uses.
# ledger.sh's OWN `read` still permits omitting --baseline (several of its
# already-approved evidence-ledger.bats scenarios rely on exactly that, to
# read across baselines for other purposes); making it mandatory THERE would
# have silently broken already-shipped, already-approved tests for no safety
# gain to THIS caller, which always supplies it regardless. The guarantee
# this script needs — a stale row can never surface as current — is enforced
# here, by construction, not by narrowing a shared tool's contract.
#
# EXIT CODES (three-way, matching ledger.sh's and chain-state.sh's convention)
#   report:
#     0  every requested ring is green AND the chain state is fully discharged
#     1  at least one ring has no recorded evidence, or requirements remain
#        unresolved — a genuine measurement, just not a ready one
#     2  COULD NOT DETERMINE — the ledger or chain-state input could not be
#        read or parsed
#   regenerate-tasks:
#     0  regenerated successfully (with --write); or, without --write, the
#        existing tasks.md already matches what would be generated
#     1  without --write: tasks.md would change (a staleness signal, e.g. for
#        CI) — never applies together with --write, which always writes
#     2  the progress tracker could not be read or parsed
#
# Usage:
#   checkpoint.sh report --ledger FILE --change C --spec S --baseline SHA \
#                         --rings R0,R1,R8 --chain-state-json FILE [--format json|text] \
#                         [--change-dir DIR]
#   checkpoint.sh regenerate-tasks --progress FILE --tasks FILE [--write]
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
LEDGER="$SELF_DIR/ledger.sh"

# The ledger's own closed ring domain (ledger-record-contract.jq), restated
# here only to VALIDATE --rings at this script's own boundary — never to
# re-decide what a ring means. A --rings value outside it can never have a
# matching row, so refusing it early names the actual mistake (a typo, a
# stale ring name) instead of silently reporting every such ring
# unevidenced, indistinguishable from a ring that genuinely never ran.
KNOWN_RINGS="R0 R1 R2 R3 R4 R5 R6 R7 R8 R9 manual"

die_finding() {
  printf 'checkpoint: %s\n' "$1" >&2
  exit 1
}
die_undetermined() {
  printf 'checkpoint: UNDETERMINED — %s\n' "$1" >&2
  exit 2
}

command -v jq >/dev/null 2>&1 || {
  printf 'checkpoint: jq is required (declared prerequisite)\n' >&2
  exit 2
}
[ -x "$LEDGER" ] || [ -f "$LEDGER" ] || die_undetermined "ledger.sh not found at $LEDGER"

SUB="${1:-}"
[ $# -gt 0 ] && shift

need_value() { # $1=flag $2=remaining count
  [ "$2" -ge 2 ] || die_finding "$1 requires a value"
}

case "$SUB" in
  report)
    LEDGER_FILE="" CHANGE="" SPEC="" BASELINE="" RINGS="" CS_JSON="" FORMAT="json" CHANGE_DIR="" SESSION=""
    while [ $# -gt 0 ]; do
      case "$1" in
        --ledger)
          need_value --ledger "$#"
          LEDGER_FILE="$2"
          shift 2
          ;;
        --change)
          need_value --change "$#"
          CHANGE="$2"
          shift 2
          ;;
        --spec)
          need_value --spec "$#"
          SPEC="$2"
          shift 2
          ;;
        --baseline)
          need_value --baseline "$#"
          BASELINE="$2"
          shift 2
          ;;
        --rings)
          need_value --rings "$#"
          RINGS="$2"
          shift 2
          ;;
        --chain-state-json)
          need_value --chain-state-json "$#"
          CS_JSON="$2"
          shift 2
          ;;
        --format)
          need_value --format "$#"
          FORMAT="$2"
          shift 2
          ;;
        --change-dir)
          need_value --change-dir "$#"
          CHANGE_DIR="$2"
          shift 2
          ;;
        --session)
          need_value --session "$#"
          SESSION="$2"
          shift 2
          ;;
        *) die_finding "unrecognised argument: $1" ;;
      esac
    done

    [ -n "$LEDGER_FILE" ] || die_finding "--ledger is required"
    [ -n "$CHANGE" ] || die_finding "--change is required"
    [ -n "$SPEC" ] || die_finding "--spec is required"
    [ -n "$BASELINE" ] || die_finding "--baseline is required"
    [ -n "$RINGS" ] || die_finding "--rings is required"
    [ -n "$CS_JSON" ] || die_finding "--chain-state-json is required"
    case "$FORMAT" in json | text) ;; *) die_finding "--format must be json or text" ;; esac

    IFS=',' read -ra ring_list_raw <<<"$RINGS"
    [ "${#ring_list_raw[@]}" -gt 0 ] || die_finding "--rings must name at least one ring"
    for r in "${ring_list_raw[@]}"; do
      case " $KNOWN_RINGS " in
        *" $r "*) ;;
        *) die_finding "unrecognised ring in --rings: $r (known: $KNOWN_RINGS)" ;;
      esac
    done
    # Ring 8 FAIL: a duplicate ring name (e.g. "R0,R0,R1") passed validation
    # and produced TWO entries for R0 in the output, both citing the same
    # row — a consumer selecting "the R0 status" got back two answers, and
    # the self-check below could never catch it (it only compared COUNTS,
    # which still matched: 3 requested, 3 assembled). De-duplicated here,
    # once, so every later stage can assume --rings names a SET.
    ring_list=()
    for r in "${ring_list_raw[@]}"; do
      seen=0
      for existing in "${ring_list[@]:-}"; do [ "$existing" = "$r" ] && seen=1 && break; done
      [ "$seen" -eq 1 ] || ring_list+=("$r")
    done

    # `-r` (readable), not `-f` (regular file): --chain-state-json is commonly
    # a process-substitution FIFO in tests (and could be a named pipe from a
    # real caller too), which `-f` rejects even though it is perfectly
    # readable — found by hand-running this exact form.
    [ -r "$CS_JSON" ] || die_undetermined "chain-state JSON not found or not readable at $CS_JSON"
    cs_content="$(cat "$CS_JSON" 2>/dev/null)"
    # Ring 8 FAIL: `has("total")` only checks the KEY exists — chain-state.sh's
    # own genuine undetermined shape (die_undetermined) carries `"total":null`
    # and `"undetermined":true` alongside an EMPTY `unresolved` array, so this
    # check let a fully-undetermined chain state through, reading as
    # "unresolved 0" and letting the whole checkpoint exit 0 with the
    # correctness definition completely unknown — the exact defect class
    # this project exists to remove. Both signals are checked explicitly now;
    # chain-state.sh's normal, successful reports never carry `undetermined`
    # at all and always carry a numeric `total`, so this rejects nothing
    # legitimate.
    printf '%s' "$cs_content" | jq -e '(.total | type) == "number" and (.undetermined // false) == false' \
      >/dev/null 2>&1 ||
      die_undetermined "chain-state JSON at $CS_JSON does not parse, has no numeric total, or is itself undetermined"

    # D2 FIX: pass --forgive-unchanged so the checkpoint shares the gate's
    # forgiveness discipline — a row whose artifact hasn't changed since its
    # baseline is forgiven even when HEAD has advanced.
    #
    # D2 FIX (fix-verified-scala3-substratum-review): use per-spec baseline
    # from implementation-progress.md instead of the gate's --baseline (which
    # is HEAD). After spec N commits, HEAD advances and every prior spec's
    # ledger rows read stale — the checkpoint reports earlier specs unevidenced
    # forever unless every ring is re-run after every commit. The per-spec
    # baseline is the SHA recorded at spec-start in implementation-progress.md.
    # If found, it replaces the gate's --baseline for the ledger read. If not
    # found, fall back to --baseline (the gate's HEAD) and trace the fallback.
    # This is the SAME discipline chain-state.sh already uses (D2 fix there).
    EFFECTIVE_BASELINE="$BASELINE"
    if [ -n "$CHANGE_DIR" ]; then
      PROGRESS_FILE="$CHANGE_DIR/implementation-progress.md"
      if [ -f "$PROGRESS_FILE" ]; then
        # Extract the per-spec baseline for THIS spec (not the first one in the file).
        # The file has sections: "## Spec N/7: <spec-name>" followed by "- **BASELINE SHA**: `<sha>`".
        # awk finds the section matching $SPEC, then prints the first SHA on the next BASELINE line.
        PROGRESS_BASELINE="$(awk -v spec="$SPEC" '
          /^## Spec [0-9]+\/[0-9]+: / {
            section=$0; sub(/^## Spec [0-9]+\/[0-9]+: /, "", section)
            in_spec=(index(section, spec) > 0)
            next
          }
          in_spec && /\*\*BASELINE SHA\*\*/ {
            match($0, /[a-f0-9]{7,40}/)
            if (RSTART > 0) { print substr($0, RSTART, RLENGTH); exit }
          }
        ' "$PROGRESS_FILE")"
        if [ -n "$PROGRESS_BASELINE" ]; then
          EFFECTIVE_BASELINE="$PROGRESS_BASELINE"
          printf 'checkpoint: using per-spec baseline %s for spec %s from implementation-progress.md (gate baseline: %s)\n' \
            "$EFFECTIVE_BASELINE" "$SPEC" "$BASELINE" >&2
        else
          printf 'checkpoint: no per-spec baseline for spec %s in implementation-progress.md, falling back to gate baseline %s\n' \
            "$SPEC" "$BASELINE" >&2
        fi
      fi
    fi
    ledger_out="$("$LEDGER" read --file "$LEDGER_FILE" --change "$CHANGE" --spec "$SPEC" --baseline "$EFFECTIVE_BASELINE" --forgive-unchanged 2>&1)"
    ledger_exit=$?
    if [ "$ledger_exit" -ne 0 ]; then
      die_undetermined "ledger read exited $ledger_exit; cannot determine ring outcomes ($ledger_out)"
    fi

    WORK="$(mktemp -d)"
    trap 'rm -rf "$WORK"' EXIT
    RING_ROWS="$WORK/rings.jsonl"
    : >"$RING_ROWS"

    for r in "${ring_list[@]}"; do
      # The LAST matching row wins: the ledger is append-only, so the most
      # recently appended row for this ring at this baseline is the current
      # one. `ledger_out` already excludes every row outside this
      # change/spec/baseline — ledger.sh's own filtering, not re-derived here.
      last_row="$(printf '%s\n' "$ledger_out" | jq -c --arg ring "$r" 'select(.ring == $ring)' | tail -1)"
      if [ -n "$last_row" ]; then
        # Ring 8 FAIL: this branch used to emit status:"green" for ANY
        # matching row, never inspecting `.exit` — a row honestly recording a
        # FAILED command (e.g. `--exit 1`) was reported green, citing that
        # same failing command, and could make the whole checkpoint exit 0.
        # A row is evidence of what actually happened, not evidence of
        # success; only an exit-0 row discharges the obligation.
        #
        # spec:judgment-ring-provenance — R8 rows carry a `session` field.
        # The checkpoint compares it to the implementing session ($SESSION):
        # - same session → "same-session" (no fresh-context evidence)
        # - different session → "green" (fresh-context discharged)
        # - PPID fallback (starts with "ppid-") → "unverified-session" (limitation)
        # - no --session arg → "unverified-session" (limitation — cannot verify)
        # The fail-open-on-empty-SESSION is intentional: the checkpoint can't
        # verify fresh-context without the implementing session, so it reports
        # a limitation requiring explicit human attestation, NOT "green".
        if [ "$r" = "R8" ]; then
          r8_session="$(printf '%s' "$last_row" | jq -r '.session // empty')"
          if [ -z "$SESSION" ]; then
            # No implementing session provided — cannot verify fresh-context.
            # Report as a limitation, NOT as green (spec Requirement 3).
            printf '%s' "$last_row" | jq -c --arg ring "$r" \
              '{ring:$ring, status:(if .exit == 0 then "unverified-session" else "failed" end), obligation:.obligation, artifact:.artifact, command:.command, exit:.exit,
                note:"No implementing session provided to checkpoint — cannot verify fresh-context, requires explicit human attestation of freshness"}' >>"$RING_ROWS"
          elif [ -z "$r8_session" ]; then
            # No session field — the contract should have rejected this row,
            # but if it slipped through (legacy row), flag it.
            printf '%s' "$last_row" | jq -c --arg ring "$r" \
              '{ring:$ring, status:"same-session", obligation:.obligation, artifact:.artifact, command:.command, exit:.exit,
                note:"R8 row has no session field — cannot verify fresh-context"}' >>"$RING_ROWS"
          elif [ "$r8_session" = "$SESSION" ]; then
            printf '%s' "$last_row" | jq -c --arg ring "$r" --arg sess "$SESSION" \
              '{ring:$ring, status:"same-session", obligation:.obligation, artifact:.artifact, command:.command, exit:.exit,
                note:"R8 review recorded in same session as implementation — no fresh-context evidence (session: \($sess))"}' >>"$RING_ROWS"
          else
            # Check if the session is a PPID fallback (unverified)
            case "$r8_session" in
              ppid-*)
                printf '%s' "$last_row" | jq -c --arg ring "$r" --arg sess "$r8_session" \
                  '{ring:$ring, status:(if .exit == 0 then "unverified-session" else "failed" end), obligation:.obligation, artifact:.artifact, command:.command, exit:.exit,
                    note:"R8 session is the PPID fallback (\($sess)) — unverified session source, requires explicit human attestation of freshness"}' >>"$RING_ROWS"
                ;;
              *)
                printf '%s' "$last_row" | jq -c --arg ring "$r" \
                  '{ring:$ring, status:(if .exit == 0 then "green" else "failed" end), obligation:.obligation, artifact:.artifact, command:.command, exit:.exit}' >>"$RING_ROWS"
                ;;
            esac
          fi
        else
          printf '%s' "$last_row" | jq -c --arg ring "$r" \
            '{ring:$ring, status:(if .exit == 0 then "green" else "failed" end), obligation:.obligation, artifact:.artifact, command:.command, exit:.exit}' >>"$RING_ROWS"
        fi
      else
        jq -cn --arg ring "$r" '{ring:$ring, status:"unevidenced"}' >>"$RING_ROWS"
      fi
    done

    report="$(jq -sc --arg change "$CHANGE" --arg spec "$SPEC" --arg baseline "$EFFECTIVE_BASELINE" \
      --argjson cs "$cs_content" \
      '{change:$change, spec:$spec, baseline:$baseline, rings:., chain_state:$cs}' "$RING_ROWS")"
    [ -n "$report" ] || die_undetermined "internal error — report assembly failed"

    # Self-check: every requested ring must appear exactly once. Ring 8 NOTE:
    # a bare COUNT comparison is structurally vacuous here — the assembly
    # loop above always appends exactly one row per element of ring_list, so
    # the counts can never actually disagree; it protected against a bug
    # that isn't reachable, while the reachable one (the SAME ring appearing
    # twice, another genuinely missing) sailed past a count-only check with
    # counts that still matched. Compares the SET of reported ring names
    # against the SET of requested ones instead.
    requested_sorted="$(printf '%s\n' "${ring_list[@]}" | sort | tr '\n' ',')"
    reported_sorted="$(printf '%s' "$report" | jq -r '[.rings[].ring] | sort | join(",")')"
    if [ "${requested_sorted%,}" != "$reported_sorted" ]; then
      die_undetermined "internal error — requested rings [${requested_sorted%,}], assembled [$reported_sorted]"
    fi

    # spec: human-grant-lock — tee the report output to git-dir state so the
    # gate can record a checkpoint-presentation. The gate consumes this file
    # on its next event, hashes it, writes a presentation record, and deletes
    # it. The report's stdout is unchanged. If STATE_DIR or SESSION is
    # unavailable, this side effect is silently skipped (the gate fails open).
    ckpt_state_dir=""
    if [ -n "$SESSION" ]; then
      ckpt_repo=""
      if [ -n "$CHANGE_DIR" ]; then
        ckpt_repo="$(cd "$CHANGE_DIR" 2>/dev/null && git rev-parse --show-toplevel 2>/dev/null || true)"
      fi
      if [ -n "$ckpt_repo" ]; then
        ckpt_git_dir="$(cd "$ckpt_repo" && git rev-parse --absolute-git-dir 2>/dev/null || true)"
        if [ -n "$ckpt_git_dir" ]; then
          ckpt_state_dir="$ckpt_git_dir/verified-scala3-gate"
          mkdir -p "$ckpt_state_dir" 2>/dev/null || true
          [ -d "$ckpt_state_dir" ] || ckpt_state_dir=""
        fi
      fi
    fi
    ckpt_output_file=""
    if [ -n "$ckpt_state_dir" ]; then
      ckpt_output_file="$ckpt_state_dir/checkpoint-output-$CHANGE-$SPEC-$SESSION"
    fi

    if [ "$FORMAT" = "text" ]; then
      # Pipe through tee to write to state dir while also printing to stdout.
      # The original code used printf '%s' | jq -r '...' — we preserve that
      # exact output, adding only the tee side effect.
      if [ -n "$ckpt_output_file" ]; then
        printf '%s' "$report" | jq -r '
          "checkpoint: \(.change)/\(.spec) @ \(.baseline)"
          + (.rings | map("\n  \(.ring): " +
              (if .status == "green" then "green (\(.command))"
               elif .status == "failed" then "FAILED (\(.command), exit \(.exit))"
               elif .status == "same-session" then "SAME-SESSION (\(.command)) — \(.note // "no fresh-context evidence")"
               elif .status == "unverified-session" then "UNVERIFIED-SESSION (\(.command)) — \(.note // "requires human attestation")"
               else "no recorded evidence" end)) | join(""))
          + "\n  chain state: total \(.chain_state.total)  bound \(.chain_state.bound)  resolved \(.chain_state.resolved)  discharged \(.chain_state.discharged)  unresolved \(.chain_state.unresolved | length)"
          + (if (.chain_state.unresolved | length) > 0
             then "\n" + (.chain_state.unresolved | map("    " + .requirement + " (" + (.reasons | join(",")) + ")") | join("\n"))
             else "" end)' | tee "$ckpt_output_file" 2>/dev/null
      else
        printf '%s' "$report" | jq -r '
          "checkpoint: \(.change)/\(.spec) @ \(.baseline)"
          + (.rings | map("\n  \(.ring): " +
              (if .status == "green" then "green (\(.command))"
               elif .status == "failed" then "FAILED (\(.command), exit \(.exit))"
               elif .status == "same-session" then "SAME-SESSION (\(.command)) — \(.note // "no fresh-context evidence")"
               elif .status == "unverified-session" then "UNVERIFIED-SESSION (\(.command)) — \(.note // "requires human attestation")"
               else "no recorded evidence" end)) | join(""))
          + "\n  chain state: total \(.chain_state.total)  bound \(.chain_state.bound)  resolved \(.chain_state.resolved)  discharged \(.chain_state.discharged)  unresolved \(.chain_state.unresolved | length)"
          + (if (.chain_state.unresolved | length) > 0
             then "\n" + (.chain_state.unresolved | map("    " + .requirement + " (" + (.reasons | join(",")) + ")") | join("\n"))
             else "" end)'
      fi
    else
      if [ -n "$ckpt_output_file" ]; then
        printf '%s\n' "$report" | tee "$ckpt_output_file" 2>/dev/null
      else
        printf '%s\n' "$report"
      fi
    fi

    all_green="$(printf '%s' "$report" | jq -r '[.rings[] | select(.status != "green")] | length == 0')"
    chain_clean="$(printf '%s' "$report" | jq -r '(.chain_state.unresolved | length) == 0')"
    if [ "$all_green" = "true" ] && [ "$chain_clean" = "true" ]; then
      exit 0
    fi
    exit 1
    ;;

  regenerate-tasks)
    PROGRESS="" TASKS="" WRITE=0
    while [ $# -gt 0 ]; do
      case "$1" in
        --progress)
          need_value --progress "$#"
          PROGRESS="$2"
          shift 2
          ;;
        --tasks)
          need_value --tasks "$#"
          TASKS="$2"
          shift 2
          ;;
        --write)
          WRITE=1
          shift
          ;;
        *) die_finding "unrecognised argument: $1" ;;
      esac
    done
    [ -n "$PROGRESS" ] || die_finding "--progress is required"
    [ -n "$TASKS" ] || die_finding "--tasks is required"
    [ -f "$PROGRESS" ] || die_undetermined "progress tracker not found at $PROGRESS"
    [ -r "$PROGRESS" ] || die_undetermined "progress tracker not readable at $PROGRESS"
    [ -f "$TASKS" ] || die_undetermined "tasks file not found at $TASKS"
    [ -r "$TASKS" ] || die_undetermined "tasks file not readable at $TASKS"

    WORK="$(mktemp -d)"
    trap 'rm -rf "$WORK"' EXIT
    COMPLETE_TSV="$WORK/complete.tsv" # spec_number<TAB>0|1
    : >"$COMPLETE_TSV"

    # ── pass 1: which specs are complete, per implementation-progress.md ────
    # A spec counts as complete iff its own "| Commit | ... |" row holds a
    # value that LOOKS LIKE a real revision (matches the same hex-hash shape
    # the ledger contract already uses for baselines) — a placeholder such as
    # "_(pending)_" or an empty cell does not.
    cur_spec="" cur_commit=""
    flush_spec() {
      [ -n "$cur_spec" ] || return 0
      local clean complete=0
      clean="$(printf '%s' "$cur_commit" | tr -d '`' | sed -E 's/^[ \t]+|[ \t]+$//g')"
      case "$clean" in
        [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]*)
          [[ "$clean" =~ ^[0-9a-f]{7,40}$ ]] && complete=1
          ;;
      esac
      printf '%s\t%s\n' "$cur_spec" "$complete" >>"$COMPLETE_TSV"
    }
    while IFS= read -r line || [ -n "$line" ]; do
      if [[ "$line" =~ ^###\ ([0-9]+)\. ]]; then
        # Captured BEFORE calling flush_spec: flush_spec runs its own
        # `[[ =~ ]]` test internally, which overwrites BASH_REMATCH as a
        # side effect regardless of whether IT references the match — reading
        # BASH_REMATCH[1] AFTER that call crashed under `set -u`, since a
        # non-matching inner test clears the array entirely. Found by running
        # this exact fixture.
        next_spec="${BASH_REMATCH[1]}"
        flush_spec
        cur_spec="$next_spec"
        cur_commit=""
        continue
      fi
      if [[ "$line" =~ ^\|[[:space:]]*Commit[[:space:]]*\|(.*)\|[[:space:]]*$ ]]; then
        cur_commit="${BASH_REMATCH[1]}"
      fi
    done <"$PROGRESS"
    flush_spec

    # ── pass 2: rewrite tasks.md's checkbox state, section by section ───────
    # Every line's TEXT is preserved verbatim except the checkbox marker
    # itself; a spec present in tasks.md but absent from the tracker (not yet
    # started) defaults to incomplete, never silently checked.
    NEW_TASKS="$WORK/tasks.md.new"
    : >"$NEW_TASKS"
    cur_spec=""
    while IFS= read -r line || [ -n "$line" ]; do
      if [[ "$line" =~ ^##\ ([0-9]+)\. ]]; then
        cur_spec="${BASH_REMATCH[1]}"
        printf '%s\n' "$line" >>"$NEW_TASKS"
        continue
      fi
      if [ -n "$cur_spec" ] && [[ "$line" =~ ^-\ \[[\ x]\]\ (.*)$ ]]; then
        rest="${BASH_REMATCH[1]}"
        # Ring 8 FAIL: `grep -F "N<TAB>"` is a SUBSTRING match — a spec
        # numbered "1" also matches the trailing "...1" of a row for spec
        # "21", and the ambiguous match let tracker-ordering decide the
        # answer instead of the actual spec number. Same defect class
        # chain-state.sh already fixed with `-x` anchoring for the identical
        # reason; not carried over here. `awk` matches field 1 EXACTLY.
        complete="$(awk -F'\t' -v s="$cur_spec" '$1==s{print $2; exit}' "$COMPLETE_TSV")"
        if [ "${complete:-0}" = "1" ]; then
          printf -- '- [x] %s\n' "$rest" >>"$NEW_TASKS"
        else
          printf -- '- [ ] %s\n' "$rest" >>"$NEW_TASKS"
        fi
        continue
      fi
      printf '%s\n' "$line" >>"$NEW_TASKS"
    done <"$TASKS"

    if [ "$WRITE" -eq 1 ]; then
      cp "$NEW_TASKS" "$TASKS" || die_undetermined "could not write $TASKS"
      exit 0
    fi

    if diff -q "$TASKS" "$NEW_TASKS" >/dev/null 2>&1; then
      cat "$NEW_TASKS"
      exit 0
    else
      cat "$NEW_TASKS"
      exit 1
    fi
    ;;

  *)
    die_finding "unknown subcommand: '${SUB:-<none>}'. Expected: report, regenerate-tasks."
    ;;
esac
