#!/usr/bin/env bash
# chain-state.sh — computes the correctness definition's three clauses.
#
# spec: chain-state (change: add-correctness-substratum).
#
# bound      = NOT in spec-lint --artifacts' "FAIL F7" set for that title
# resolved   = every Proof-Obligations row mapped to a bound title (via the
#              "Requirement: <title>" preferred Source form, split on " + ")
#              has no artifact token in spec-lint's "FAIL F9" set
# discharged = every row mapped to a resolved title has >=1 matching ledger
#              row (same change, same baseline, same obligation text)
#
# NEVER REIMPLEMENTED: whether an artifact resolves, and whether a title is
# reachable, are ALWAYS spec-lint.sh's own judgment (its F7/F9 output). This
# script only re-reads table STRUCTURE (which row says what) at lines
# spec-lint already flagged, to attribute its per-line verdicts back to
# requirements — spec-lint's own output has no such attribution.
#
# unmapped_obligations: a row with an F9 hit whose Source does not map to any
# known requirement title (an ordinal, a non-Requirement typed source, or a
# dangling reference) is NEVER dropped and NEVER misattributed — it is
# reported separately. A stated, deliberate scope limit (see design.md and
# the Step 1 gate), not a silently cut corner.
#
# UNATTRIBUTABLE (added at Ring 8, fresh-context pass): a title spec-lint's
# own F7 says IS bound, but for which THIS script's narrow "Requirement:
# <exact title>" matcher finds ZERO rows — because spec-lint's real F7
# binding is looser (ordinals, case-insensitive substring matches; see
# spec-lint.sh's named_exists()). Reporting such a title as fully discharged
# by DEFAULT (an empty loop never lowering an is_resolved=1/is_discharged=1
# flag) was the exact "claim outran evidence" defect this whole change exists
# to remove, discovered inside the tool built to prevent it. A bound title
# with no attributable row can never be counted resolved or discharged; it
# is reported with reason "unattributable" instead.
#
# EXIT CODES (three-way, matching ledger.sh's convention)
#   0  ran; every requirement is bound, resolved and discharged
#   1  ran; at least one requirement is not, or an obligation is unmapped
#   2  COULD NOT DETERMINE — unreadable ledger, spec-lint exited for a reason
#      other than lint findings, or spec-lint's own run did not complete
#      (no summary line, or a file-count mismatch against what this script
#      itself enumerated — see the F6 fix below)
#
# Usage:
#   chain-state.sh --change-dir DIR --change NAME --baseline SHA \
#                  [--ledger-file FILE]
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
LEDGER="$SELF_DIR/ledger.sh"
SPEC_LINT="${SPEC_LINT_OVERRIDE:-$SELF_DIR/spec-lint.sh}"
CONTRACT="$SELF_DIR/chain-state-report-contract.jq"

# Declared and initialised BEFORE anything that can call die_undetermined:
# the first version called it (via the jq/ledger.sh/contract existence
# checks) before these were declared, so under `set -u` the die_undetermined
# function itself crashed on an unbound variable — an undetermined condition
# surfacing as a raw shell crash (effectively exit 1) with no report at all,
# contradicting its own purpose.
CHANGE_DIR="" CHANGE="" BASELINE="" LEDGER_FILE=""

die_finding() {
  printf 'chain-state: %s\n' "$1" >&2
  exit 1
}
die_undetermined() { # $1=message; ALSO emits a minimal report so a caller
  # reading stdout unconditionally still sees why, per the requirement that
  # undetermined is a stated fact, never a silent nothing.
  jq -n --arg change "$CHANGE" --arg baseline "$BASELINE" --arg reason "$1" \
    '{change:$change, baseline:$baseline, undetermined:true, reason:$reason,
      total:null, bound:null, resolved:null, discharged:null,
      unresolved:[], unmapped_obligations:[]}'
  printf 'chain-state: UNDETERMINED — %s\n' "$1" >&2
  exit 2
}

command -v jq >/dev/null 2>&1 || {
  printf 'chain-state: jq is required (declared prerequisite)\n' >&2
  exit 2
}
[ -x "$LEDGER" ] || [ -f "$LEDGER" ] || die_undetermined "ledger.sh not found at $LEDGER"
[ -f "$CONTRACT" ] || die_undetermined "report contract not found at $CONTRACT"

while [ $# -gt 0 ]; do
  case "$1" in
    --change-dir)
      [ $# -ge 2 ] || die_finding "--change-dir requires a value"
      CHANGE_DIR="$2"
      shift 2
      ;;
    --change)
      [ $# -ge 2 ] || die_finding "--change requires a value"
      CHANGE="$2"
      shift 2
      ;;
    --baseline)
      [ $# -ge 2 ] || die_finding "--baseline requires a value"
      BASELINE="$2"
      shift 2
      ;;
    --ledger-file)
      [ $# -ge 2 ] || die_finding "--ledger-file requires a value"
      LEDGER_FILE="$2"
      shift 2
      ;;
    *) die_finding "unrecognised argument: $1" ;;
  esac
done

[ -n "$CHANGE_DIR" ] || die_finding "--change-dir is required"
[ -n "$CHANGE" ] || die_finding "--change is required"
# --baseline is REQUIRED (not optional, unlike ledger.sh's read): a chain
# state read without a baseline is precisely the shape that let a typo
# silently disable staleness filtering in evidence-ledger's Ring 8 pass.
# This is a new tool's contract; the lesson is applied here by construction.
[ -n "$BASELINE" ] || die_finding "--baseline is required"
LEDGER_FILE="${LEDGER_FILE:-$CHANGE_DIR/evidence-ledger.jsonl}"

# D2 FIX (fix-verified-scala3-substratum-review): use per-spec baseline from
# implementation-progress.md instead of the gate's --baseline (which is HEAD).
# After spec N commits, HEAD advances and every prior spec's ledger rows read
# stale — the gate reports earlier specs undischarged forever unless every
# ring is re-run after every commit. The per-spec baseline is the SHA recorded
# at spec-start in implementation-progress.md. If found, it replaces the
# gate's --baseline for the ledger read. If not found, fall back to --baseline
# (the gate's HEAD) and trace the fallback.
PROGRESS_FILE="$CHANGE_DIR/implementation-progress.md"
EFFECTIVE_BASELINE="$BASELINE"
if [ -f "$PROGRESS_FILE" ]; then
  # Extract the first BASELINE SHA from the progress file. The format is:
  #   - **BASELINE SHA**: `<sha>`
  # There may be multiple specs with different baselines; we use the first
  # one found. A more sophisticated version would map each spec to its own
  # baseline, but the common case is one baseline per change.
  PROGRESS_BASELINE="$(grep -oE '\*\*BASELINE SHA\*\*: `?[a-f0-9]{7,40}`?' "$PROGRESS_FILE" | head -1 | grep -oE '[a-f0-9]{7,40}')"
  if [ -n "$PROGRESS_BASELINE" ]; then
    EFFECTIVE_BASELINE="$PROGRESS_BASELINE"
    # Trace the fallback so the gate's output shows which baseline was used
    printf 'chain-state: using per-spec baseline %s from implementation-progress.md (gate baseline: %s)\n' \
      "$EFFECTIVE_BASELINE" "$BASELINE" >&2
  else
    printf 'chain-state: no per-spec baseline in implementation-progress.md, falling back to gate baseline %s\n' \
      "$BASELINE" >&2
  fi
fi

# D5 FIX (fix-verified-scala3-substratum-review): check python3 availability
# for graph-based fact extraction. When python3 is available, chain-state
# consumes openspec-graph.py export JSON + spec-lint --format json, retiring
# the three-parser divergence. When python3 is unavailable (or the graph
# export fails), it falls back to the degraded bash-only mode (awk table
# parsing + spec-lint human prose regex) and emits a trace line documenting
# the degradation. The gate MUST NOT silently produce wrong verdicts — the
# degraded mode is explicitly documented, not silent.
USE_GRAPH=0
if command -v python3 >/dev/null 2>&1; then
  USE_GRAPH=1
else
  printf 'chain-state: python3 unavailable; using degraded bash-only mode\n' >&2
fi

# ── discover every requirement FIRST: (spec_path, spec_name, title) ──────
# Purely structural — the same heading marker the schema's own template
# mandates and spec-lint's own internal extraction uses. Not reachability
# logic: it does not decide whether a requirement is BOUND, only that it
# EXISTS, which spec-lint's output never enumerates on its own. Computed
# before invoking spec-lint so its own reported file count can be
# cross-checked against this enumeration (see the F6 fix below).
spec_files="$(find "$CHANGE_DIR/specs" -name 'spec.md' 2>/dev/null | sort || true)"
n_spec_files=0
if [ -n "$spec_files" ]; then
  n_spec_files="$(printf '%s\n' "$spec_files" | grep -c .)"
fi

# D5 FIX: try the graph export BEFORE running spec-lint, so USE_GRAPH is
# finalized before we choose the spec-lint format (--format json for graph
# mode, human prose for degraded mode). If the graph export fails (e.g.
# OPENSPEC_ROOT not set, python3 crash), fall back to degraded mode NOW so
# the spec-lint call uses the correct format.
graph_out=""
if [ "$USE_GRAPH" -eq 1 ]; then
  graph_out="$(python3 "$SELF_DIR/openspec-graph.py" export \
    --change-dir "$CHANGE_DIR" --change "$CHANGE" 2>/dev/null)"
  graph_exit=$?
  if [ "$graph_exit" -ne 0 ] || [ -z "$graph_out" ]; then
    printf 'chain-state: openspec-graph.py export failed (exit %s); using degraded bash-only mode\n' \
      "$graph_exit" >&2
    USE_GRAPH=0
  elif ! printf '%s' "$graph_out" | jq -e '.obligations' >/dev/null 2>&1; then
    printf 'chain-state: openspec-graph.py export produced invalid JSON; using degraded bash-only mode\n' >&2
    USE_GRAPH=0
  fi
fi

# ── run the two evidence sources ─────────────────────────────────────────
# D5 FIX: in graph mode, use spec-lint --format json (machine interface);
# in degraded mode, use spec-lint human prose (current behavior).
if [ "$USE_GRAPH" -eq 1 ]; then
  lint_out="$("$SPEC_LINT" --artifacts "$CHANGE_DIR" --format json 2>/dev/null)"
  lint_exit=$?
  if [ "$lint_exit" -ne 0 ] && [ "$lint_exit" -ne 1 ]; then
    die_undetermined "spec-lint.sh exited $lint_exit (not a lint-finding exit); cannot determine bound/resolved"
  fi
  # Verify JSON output is a valid array — the completion check for JSON mode
  # (replaces the human-prose summary check in degraded mode).
  if ! printf '%s' "$lint_out" | jq -e 'type == "array"' >/dev/null 2>&1; then
    die_undetermined "spec-lint.sh --format json did not produce a JSON array (exit $lint_exit)"
  fi
else
  lint_out="$("$SPEC_LINT" --artifacts "$CHANGE_DIR" 2>&1)"
  lint_exit=$?
  # spec-lint.sh exits 0 (clean or no specs found) or 1 (FAIL findings) for a
  # GENUINE run; anything else means it did not run to completion (crash,
  # missing dependency, unreadable target) — that is UNDETERMINED, never
  # silently treated as "no findings".
  if [ "$lint_exit" -ne 0 ] && [ "$lint_exit" -ne 1 ]; then
    die_undetermined "spec-lint.sh exited $lint_exit (not a lint-finding exit); cannot determine bound/resolved"
  fi

  # F6 FIX (Ring 8): spec-lint.sh runs under `set -e`; if ITS OWN `find` trips
  # on an unreadable subdirectory, it exits 1 having printed NO summary line at
  # all — a crash this script previously accepted as "ran, has findings"
  # because 1 is also the ordinary FAIL exit. A completed run always prints
  # EITHER the numeric summary "spec-lint: N spec file(s), F FAIL, W WARN", OR
  # one of its two literal "nothing to lint" messages when specs/ is absent or
  # empty (verified directly: both exit 0 and print neither summary form) —
  # recognised explicitly rather than folded into "no summary means crash",
  # which would have wrongly flagged a legitimately empty change as
  # undetermined. Absence of ALL THREE recognised completion shapes is a crash.
  if [[ "$lint_out" =~ spec-lint:\ ([0-9]+)\ spec\ file\(s\),\ [0-9]+\ FAIL,\ [0-9]+\ WARN ]]; then
    lint_reported_n="${BASH_REMATCH[1]}"
    if [ "$lint_reported_n" -ne "$n_spec_files" ]; then
      die_undetermined "spec-lint.sh saw $lint_reported_n spec file(s) but this script enumerated $n_spec_files; they disagree, likely a permission or discovery fault"
    fi
  elif [[ "$lint_out" == *"no spec files to lint under"* ]]; then
    # "specs/ exists but is EMPTY" — spec-lint's own legitimate-zero signal
    # (verified directly: exit 0, this exact message, no numeric summary).
    # NOTE: "no specs found under ..." (specs/ ABSENT entirely) is a DIFFERENT
    # message that spec-lint pairs with exit 2 — its own undetermined signal —
    # which the earlier lint_exit check above already catches; that message
    # never reaches this branch, so no separate case is needed for it here.
    if [ "$n_spec_files" -ne 0 ]; then
      die_undetermined "spec-lint.sh reported no specs to lint, but this script enumerated $n_spec_files spec.md file(s); they disagree"
    fi
  else
    die_undetermined "spec-lint.sh produced no recognised completion message (exit $lint_exit); it did not finish running"
  fi
fi

# D2 FIX: pass --forgive-unchanged to ledger.sh so that rows whose artifacts
# haven't changed since their baseline are forgiven even when HEAD has
# advanced. This is the discipline the gate and checkpoint share.
ledger_out="$("$LEDGER" read --file "$LEDGER_FILE" --change "$CHANGE" --baseline "$EFFECTIVE_BASELINE" --forgive-unchanged 2>&1)"
ledger_exit=$?
if [ "$ledger_exit" -ne 0 ]; then
  die_undetermined "ledger read exited $ledger_exit; cannot determine discharged ($ledger_out)"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
REQ_STATES="$WORK/req-states.jsonl"
UNMAPPED="$WORK/unmapped.jsonl"
: >"$REQ_STATES"
: >"$UNMAPPED"

# ═══════════════════════════════════════════════════════════════════════════
# D5 FIX: Graph-based fact extraction (primary path) or degraded fallback.
# When python3 is available, chain-state consumes openspec-graph.py export
# JSON for the obligation→requirement→artifact topology, and spec-lint
# --format json for F7/F9 verdicts. The awk table-parsing and human-prose
# regex are used ONLY in degraded mode. The `unattributable` reason-code is
# deleted in graph mode — the graph resolves sources uniformly (ordinal,
# title, or inferred), so a bound title with no mapped obligations is a
# graph bug, not a silent default-to-discharged.
# ═══════════════════════════════════════════════════════════════════════════

if [ "$USE_GRAPH" -eq 1 ]; then
  # ── graph-based path (graph_out already captured above) ────────────────
  # ── parse spec-lint JSON findings ──────────────────────────────────────
  # F7 unbound titles: requirements named by NO proof obligation
  F7_TITLES="$WORK/f7-titles.txt"
  : >"$F7_TITLES"
  printf '%s' "$lint_out" | jq -r '.[] | select(.check == "F7" and .verdict == "FAIL") | .requirement' \
    >"$F7_TITLES" 2>/dev/null || true

  # F9 unresolved artifacts: artifact tokens that don't resolve to a tracked file
  F9_ARTIFACTS="$WORK/f9-artifacts.txt"
  : >"$F9_ARTIFACTS"
  printf '%s' "$lint_out" | jq -r '.[] | select(.check == "F9" and .verdict == "FAIL" and .artifact != "") | .artifact' \
    >"$F9_ARTIFACTS" 2>/dev/null || true

  # ── walk each spec file using graph obligations ────────────────────────
  while IFS= read -r spec_path; do
    [ -n "$spec_path" ] || continue
    spec_name="$(basename "$(dirname "$spec_path")")"

    # Requirement titles (grep, NOT awk table-parsing — the heading marker
    # is the same one the schema template mandates)
    titles="$(grep -n '^### Requirement: ' "$spec_path" | sed -E 's/^[0-9]+:### Requirement: //')"

    while IFS= read -r title; do
      [ -n "$title" ] || continue

      # Check unbound: is this title in the F7 findings?
      if grep -qxF "$title" "$F7_TITLES"; then
        jq -n --arg s "$spec_name" --arg r "$title" --arg reason unbound \
          '{spec:$s, requirement:$r, reason:$reason}' >>"$REQ_STATES"
        continue
      fi

      # Get mapped obligations from the graph: obligations in this spec
      # whose sources include this requirement title
      mapped_obls="$(printf '%s' "$graph_out" | jq -r \
        --arg sp "$spec_name" --arg title "$title" \
        '.obligations[] | select(.spec == $sp) | select(any(.sources[]; .requirement == $title)) | .obligation')"

      mapped_count="$(printf '%s' "$mapped_obls" | grep -c . || true)"
      if [ "$mapped_count" -eq 0 ]; then
        # D5 FIX: the `unattributable` reason-code is DELETED. The graph
        # resolves sources uniformly (ordinal, title, or inferred). A bound
        # title with no mapped obligations in the graph is reported as
        # unresolved — never silently defaulted to discharged, and never
        # given the old "unattributable" label.
        jq -n --arg s "$spec_name" --arg r "$title" --arg reason unresolved \
          '{spec:$s, requirement:$r, reason:$reason}' >>"$REQ_STATES"
        continue
      fi

      # Check resolved: none of the mapped obligations' artifacts are in
      # the F9 unresolved set. The graph provides each obligation's
      # artifacts directly — no line-number matching needed.
      is_resolved=1
      while IFS= read -r mobl; do
        [ -n "$mobl" ] || continue
        obl_artifacts="$(printf '%s' "$graph_out" | jq -r \
          --arg sp "$spec_name" --arg ob "$mobl" \
          '.obligations[] | select(.spec == $sp and .obligation == $ob) | .artifacts[]?')"
        while IFS= read -r art; do
          [ -n "$art" ] || continue
          if grep -qxF "$art" "$F9_ARTIFACTS"; then
            is_resolved=0
          fi
        done <<<"$obl_artifacts"
      done <<<"$mapped_obls"

      if [ "$is_resolved" -eq 0 ]; then
        jq -n --arg s "$spec_name" --arg r "$title" --arg reason unresolved \
          '{spec:$s, requirement:$r, reason:$reason}' >>"$REQ_STATES"
        continue
      fi

      # Check discharged: every mapped obligation has >=1 green ledger row
      # (same logic as degraded mode — D1 fix: .exit == 0 filter)
      is_discharged=1
      all_have_rows=1
      while IFS= read -r mobl; do
        [ -n "${mobl:-}" ] || continue
        any_found="$(printf '%s' "$ledger_out" | jq -r --arg sp "$spec_name" --arg ob "$mobl" \
          'select(.spec == $sp and .obligation == $ob) | .obligation' | head -1)"
        [ -n "$any_found" ] || all_have_rows=0
        green_found="$(printf '%s' "$ledger_out" | jq -r --arg sp "$spec_name" --arg ob "$mobl" \
          'select(.spec == $sp and .obligation == $ob and .exit == 0) | .obligation' | head -1)"
        [ -n "$green_found" ] || is_discharged=0
      done <<<"$mapped_obls"

      if [ "$is_discharged" -eq 0 ]; then
        if [ "$all_have_rows" -eq 1 ]; then
          jq -n --arg s "$spec_name" --arg r "$title" --arg reason failed \
            '{spec:$s, requirement:$r, reason:$reason}' >>"$REQ_STATES"
        else
          jq -n --arg s "$spec_name" --arg r "$title" --arg reason undischarged \
            '{spec:$s, requirement:$r, reason:$reason}' >>"$REQ_STATES"
        fi
      else
        jq -n --arg s "$spec_name" --arg r "$title" --arg reason ok \
          '{spec:$s, requirement:$r, reason:$reason}' >>"$REQ_STATES"
      fi
    done <<<"$titles"

    # Unmapped obligations: graph obligations with no sources (no
    # requirement mapping). These are reported separately, never dropped.
    # The contract requires line >= 1 and non-empty artifact, so we search
    # the spec file for the obligation text to get the real line number,
    # and only report obligations with a non-empty artifact.
    unmapped_obls="$(printf '%s' "$graph_out" | jq -r \
      --arg sp "$spec_name" \
      '.obligations[] | select(.spec == $sp and (.sources | length == 0)) | .obligation')"
    while IFS= read -r uobl; do
      [ -n "$uobl" ] || continue
      uobl_art="$(printf '%s' "$graph_out" | jq -r \
        --arg sp "$spec_name" --arg ob "$uobl" \
        '.obligations[] | select(.spec == $sp and .obligation == $ob) | .artifact')"
      [ -n "$uobl_art" ] || continue  # skip if no artifact (contract requires non-empty)
      # Find the line number by searching the spec file for the obligation text
      uobl_line="$(grep -nF "$uobl" "$spec_path" | head -1 | cut -d: -f1)"
      [ -n "$uobl_line" ] || uobl_line=1  # fallback if not found (shouldn't happen)
      jq -n --arg s "$spec_name" --argjson l "$uobl_line" --arg a "$uobl_art" \
        '{spec:$s, line:$l, artifact:$a}' >>"$UNMAPPED"
    done <<<"$unmapped_obls"
  done <<<"$spec_files"

else
  # ── degraded mode: awk table-parsing + spec-lint human-prose regex ─────
  # This is the fallback when python3 is unavailable. The trace line was
  # already emitted above. This code path retains the awk parser and the
  # `unattributable` reason-code (the graph is not available to resolve
  # sources uniformly).

# ── parse spec-lint's F7 (unbound) and F9 (unresolved-artifact-line) hits ─
# One line of state per file, tracked by walking spec-lint's OWN output
# top-to-bottom: a file section starts at a line matching
# "spec-lint: <path>/spec.md" (the CONTEXT header and the two summary lines
# never end in "/spec.md", so this pattern cannot mis-track them).
UNBOUND="$WORK/unbound.tsv" # spec_path<TAB>title
F9LINES="$WORK/f9lines.tsv" # spec_path<TAB>line
: >"$UNBOUND"
: >"$F9LINES"
current_spec=""
while IFS= read -r line; do
  if [[ "$line" =~ ^spec-lint:\ (.*/spec\.md)$ ]]; then
    current_spec="${BASH_REMATCH[1]}"
    continue
  fi
  [ -n "$current_spec" ] || continue
  if [[ "$line" =~ ^\ \ FAIL\ F7\ line\ [0-9]+:\ requirement\ \"(.*)\"\ is\ named\ by\ NO\ proof\ obligation ]]; then
    printf '%s\t%s\n' "$current_spec" "${BASH_REMATCH[1]}" >>"$UNBOUND"
  elif [[ "$line" =~ ^\ \ FAIL\ F9\ line\ ([0-9]+): ]]; then
    printf '%s\t%s\n' "$current_spec" "${BASH_REMATCH[1]}" >>"$F9LINES"
  fi
done <<<"$lint_out"

# ── walk each spec file: enumerate requirements + Proof Obligations rows ─
while IFS= read -r spec_path; do
  [ -n "$spec_path" ] || continue
  spec_name="$(basename "$(dirname "$spec_path")")"

  # requirement titles for THIS file, in declaration order
  titles="$(grep -n '^### Requirement: ' "$spec_path" | sed -E 's/^[0-9]+:### Requirement: //')"

  # every Proof-Obligations table row: line<TAB>obligation<TAB>source.
  # F4 FIX (Ring 8): the header-row exclusion was a CONTENT test
  # ($0 !~ /^\| *Obligation/), so a genuine data row whose Obligation-cell
  # TEXT happens to start with the word "Obligation" was silently discarded
  # from THIS SCRIPT'S OWN row parsing — demonstrated with a real fixture.
  # The header is now excluded STRUCTURALLY: it is the row immediately
  # preceding the "|---|" separator, tracked with a one-row lookahead rather
  # than matched by its content.
  #
  # NOTE: spec-lint.sh's OWN F7/F9 detection is gated by the identical
  # content-based exclusion and is NOT changed by this fix (out of scope —
  # this script must never reimplement or diverge from spec-lint's own
  # bound/resolved judgment). A requirement whose ONLY obligation row starts
  # with "Obligation" is therefore still reported unbound, correctly agreeing
  # with spec-lint. This fix's observable value is for a title with MULTIPLE
  # mapped rows where only some are affected: before it, such a row could be
  # silently missing from discharge-checking even though spec-lint itself saw
  # the title as bound via a different row — see tests/chain-state.bats.
  rows="$(awk -F'|' '
    /^## Proof Obligations/ { m = 1; next }
    /^## /                  { m = 0; next }
    m && /^\|[ \t:-]*\|[ \t:-]*\|[ \t:-]*\|[ \t:-]*\|[ \t]*$/ { sep = 1; next }
    m && /^\|/ {
      if (!sep) next            # header row precedes the separator; skip it
      ob = $2; src = $3
      gsub(/^[ \t]+|[ \t]+$/, "", ob)
      gsub(/^[ \t]+|[ \t]+$/, "", src)
      if (ob != "") printf "%d\t%s\t%s\n", NR, ob, src
    }' "$spec_path")"

  while IFS= read -r title; do
    [ -n "$title" ] || continue

    # F2 FIX (Ring 8): grep -qF (no -x) is a SUBSTRING match, so a title that
    # is a prefix of another title (e.g. "Alpha" inside "Alpha Extended")
    # falsely matched the longer row. -x anchors the whole line.
    if grep -qxF "$(printf '%s\t%s' "$spec_path" "$title")" "$UNBOUND"; then
      jq -n --arg s "$spec_name" --arg r "$title" --arg reason unbound \
        '{spec:$s, requirement:$r, reason:$reason}' >>"$REQ_STATES"
      continue
    fi

    # rows mapped to THIS title via the preferred "Requirement: <title>"
    # form, splitting combined Sources on " + " (the norm in this project's
    # real specs, verified against correctness-invariant and evidence-ledger
    # at the Step 1 gate).
    mapped_lines=""
    mapped_obligations=""
    while IFS=$'\t' read -r rline robl rsrc; do
      [ -n "$rline" ] || continue
      IFS='+' read -ra segs <<<"$rsrc"
      for seg in "${segs[@]}"; do
        seg_trimmed="$(printf '%s' "$seg" | sed -E 's/^[ \t]+|[ \t]+$//g')"
        if [[ "$seg_trimmed" == "Requirement: $title" ]]; then
          mapped_lines="$mapped_lines$rline"$'\n'
          mapped_obligations="$mapped_obligations$rline"$'\t'"$robl"$'\n'
          break
        fi
      done
    done <<<"$rows"

    # F1 FIX (Ring 8, the critical finding): spec-lint's OWN F7 binding is
    # LOOSER than this script's exact-title matcher (it also accepts
    # ordinals and case-insensitive substring Source references — see
    # spec-lint.sh's named_exists()). A title spec-lint calls bound, for
    # which this script's narrow matcher finds ZERO rows, is a title this
    # script CANNOT determine resolved/discharged for. The prior version left
    # is_resolved=1/is_discharged=1 at their DEFAULT (never lowered by an
    # empty loop), which reported such a title as fully DISCHARGED with no
    # evidence whatsoever examined — the exact defect class this project
    # exists to remove, found inside its own tooling. It is reported here
    # with its own reason, never silently folded into "ok".
    mapped_line_count="$(printf '%s' "$mapped_lines" | grep -c . || true)"
    if [ "$mapped_line_count" -eq 0 ]; then
      jq -n --arg s "$spec_name" --arg r "$title" --arg reason unattributable \
        '{spec:$s, requirement:$r, reason:$reason}' >>"$REQ_STATES"
      continue
    fi

    # resolved: none of this title's mapped lines carry an F9 hit.
    # F3 FIX (Ring 8): grep -qF (no -x) on "path<TAB>lineno" is a substring
    # match on the LINE NUMBER TEXT, so line 29 falsely matched line 290.
    # -x anchors the whole line, matching the integer exactly.
    is_resolved=1
    while IFS= read -r mline; do
      [ -n "$mline" ] || continue
      grep -qxF "$(printf '%s\t%s' "$spec_path" "$mline")" "$F9LINES" && is_resolved=0
    done <<<"$mapped_lines"

    if [ "$is_resolved" -eq 0 ]; then
      jq -n --arg s "$spec_name" --arg r "$title" --arg reason unresolved \
        '{spec:$s, requirement:$r, reason:$reason}' >>"$REQ_STATES"
      continue
    fi

    # discharged: every mapped obligation has >=1 matching ledger row with
    # .exit == 0 (a green run) — checked against the ALREADY-FETCHED ledger
    # read, never a fresh per-obligation query. Exact equality
    # (.obligation == $ob), not contains(): verified in both directions at
    # Ring 8.
    #
    # D1 FIX (fix-verified-scala3-substratum-review): the previous version
    # matched any row regardless of .exit, so a FAILED run (exit 1)
    # incorrectly discharged the obligation. Now only .exit == 0 rows
    # discharge. A row with .exit != 0 is negative evidence (something was
    # tried and failed), reported as reason "failed" — distinct from
    # "undischarged" (no rows at all, absence of evidence).
    #
    # The reason is the WORST case across all mapped obligations:
    #   - If ANY obligation has no rows at all → "undischarged" (absence of
    #     evidence for at least one obligation)
    #   - If ALL obligations have rows but at least one has no green row →
    #     "failed" (negative evidence — something was tried and failed)
    #   - If ALL obligations have green rows → "ok" (discharged)
    is_discharged=1
    all_have_rows=1
    while IFS=$'\t' read -r _mline mobl; do
      [ -n "${mobl:-}" ] || continue
      # Check for any matching row (regardless of exit)
      any_found="$(printf '%s' "$ledger_out" | jq -r --arg sp "$spec_name" --arg ob "$mobl" \
        'select(.spec == $sp and .obligation == $ob) | .obligation' | head -1)"
      [ -n "$any_found" ] || all_have_rows=0
      # Check for a GREEN row (.exit == 0)
      green_found="$(printf '%s' "$ledger_out" | jq -r --arg sp "$spec_name" --arg ob "$mobl" \
        'select(.spec == $sp and .obligation == $ob and .exit == 0) | .obligation' | head -1)"
      [ -n "$green_found" ] || is_discharged=0
    done <<<"$mapped_obligations"

    if [ "$is_discharged" -eq 0 ]; then
      # Distinguish "failed" (all obligations have rows but none green) from
      # "undischarged" (at least one obligation has no rows at all). A
      # recorded red run is negative evidence; no rows is absence of
      # evidence. The two MUST NOT be collapsed.
      if [ "$all_have_rows" -eq 1 ]; then
        jq -n --arg s "$spec_name" --arg r "$title" --arg reason failed \
          '{spec:$s, requirement:$r, reason:$reason}' >>"$REQ_STATES"
      else
        jq -n --arg s "$spec_name" --arg r "$title" --arg reason undischarged \
          '{spec:$s, requirement:$r, reason:$reason}' >>"$REQ_STATES"
      fi
    else
      jq -n --arg s "$spec_name" --arg r "$title" --arg reason ok \
        '{spec:$s, requirement:$r, reason:$reason}' >>"$REQ_STATES"
    fi
  done <<<"$titles"

  # unmapped_obligations: F9-hit lines in THIS file whose row's Source does
  # not map to ANY known title of this file — never dropped, never
  # misattributed to a requirement that may be entirely unrelated.
  while IFS=$'\t' read -r fline; do
    [ -n "$fline" ] || continue
    row="$(printf '%s\n' "$rows" | awk -F'\t' -v ln="$fline" '$1==ln{print; exit}')"
    [ -n "$row" ] || continue
    rsrc="$(printf '%s' "$row" | cut -f3)"
    mapped=0
    IFS='+' read -ra segs <<<"$rsrc"
    for seg in "${segs[@]}"; do
      seg_trimmed="$(printf '%s' "$seg" | sed -E 's/^[ \t]+|[ \t]+$//g')"
      case "$seg_trimmed" in
        "Requirement: "*)
          t="${seg_trimmed#Requirement: }"
          printf '%s\n' "$titles" | grep -qxF "$t" && mapped=1
          ;;
      esac
    done
    if [ "$mapped" -eq 0 ]; then
      # recover the offending artifact TOKEN from spec-lint's own F9 line, so
      # the reported token matches spec-lint's judgment exactly. If recovery
      # ever fails to match (it should always match, since the pattern comes
      # from spec-lint's own fixed message shape), fall back to a value that
      # names the failure rather than silently leaking spec-lint's raw log
      # line as if it were the artifact.
      tok_line="$(printf '%s\n' "$lint_out" | grep -F "FAIL F9 line $fline:" | head -1)"
      tok="$(printf '%s' "$tok_line" | sed -E "s/.*artifact '(.*)' does not resolve.*/\1/")"
      if [ "$tok" = "$tok_line" ] || [ -z "$tok" ]; then
        tok="<unrecoverable artifact token, spec-lint line: $fline>"
      fi
      jq -n --arg s "$spec_name" --argjson l "$fline" --arg a "$tok" \
        '{spec:$s, line:$l, artifact:$a}' >>"$UNMAPPED"
    fi
  done < <(grep -F "$(printf '%s\t' "$spec_path")" "$F9LINES" | cut -f2)
done <<<"$spec_files"

fi  # end degraded mode (USE_GRAPH == 0)

# ── assemble the report — jq owns all counting and shape ────────────────
report="$(jq -sc \
  --arg change "$CHANGE" --arg baseline "$EFFECTIVE_BASELINE" \
  --slurpfile unmapped "$UNMAPPED" '
  {
    change: $change, baseline: $baseline,
    total: length,
    bound: (map(select(.reason != "unbound")) | length),
    resolved: (map(select(.reason == "ok" or .reason == "undischarged" or .reason == "failed")) | length),
    discharged: (map(select(.reason == "ok")) | length),
    unresolved: (map(select(.reason != "ok")) |
      map({spec, requirement, reasons: [.reason]})),
    # --slurpfile ALREADY yields the array of every object in the file: $unmapped
    # IS that array, not $unmapped[0]. The earlier form wrongly gave null for an
    # empty file, and wrongly gave a bare object (not a one-element array) for
    # a file holding one row.
    unmapped_obligations: $unmapped
  }' "$REQ_STATES")"
report_status=$?
if [ "$report_status" -ne 0 ] || [ -z "$report" ]; then
  die_undetermined "internal error — the report assembly itself failed (jq exit $report_status)"
fi

# Self-check: a report that violates its OWN contract is a bug in this
# script, not a fact about the change. Exit 2 rather than ship it — the
# same "never let a claim outrun its evidence" rule applied to chain-state's
# own output. This can now genuinely fail on MISCLASSIFICATION, not only on
# shape violations: the contract cross-checks the counts against the SET of
# reasons actually carried in `unresolved` (Ring 8 F8/F9 fix), so a bound
# title silently miscounted as "ok" is caught here too.
if ! printf '%s' "$report" | jq -e -f "$CONTRACT" >/dev/null 2>&1; then
  die_undetermined "internal error — the assembled report does not satisfy its own contract"
fi

printf '%s\n' "$report"

# Read back from a validated report; do not fall through to success on an
# empty or malformed extraction. `${x:-}` failing the -gt test with a
# non-numeric message rather than silently taking the "clean" branch is the
# failure DIRECTION the contract-validated report above already forecloses,
# but the check is kept explicit rather than assumed.
unresolved_n="$(printf '%s' "$report" | jq '.unresolved | length')"
unmapped_n="$(printf '%s' "$report" | jq '.unmapped_obligations | length')"
case "$unresolved_n" in '' | *[!0-9]*) die_undetermined "could not read back unresolved count from own report" ;; esac
case "$unmapped_n" in '' | *[!0-9]*) die_undetermined "could not read back unmapped count from own report" ;; esac
if [ "$unresolved_n" -gt 0 ] || [ "$unmapped_n" -gt 0 ]; then
  exit 1
fi
exit 0
