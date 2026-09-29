#!/usr/bin/env bash
# reconcile.sh — does anything CORROBORATE the rows the agent wrote?
#
# WHY THIS EXISTS
# The ledger's only writer used to be the agent. `append` takes whatever exit
# code it is handed, so a row asserting a green run and a row recording one
# were byte-for-byte identical — and a ledger backfilled after the fact was
# indistinguishable from one written as the runs happened. The obvious fix,
# forcing the agent to write, does not touch that: a forced write is still a
# write whose CONTENT the writer chooses. What closes it is a second observer,
# and a check that notices when only one of them spoke.
#
# THREE KINDS OF ROW, distinguished by who observed the exit:
#   `run` rows      carry a `digest` — ledger.sh executed the command and read
#                   its own $?. Self-observed; nothing to corroborate.
#   ambient rows    carry `source: "ambient"` — hooks/gate.sh wrote them from
#                   the harness's own post-tool payload. These ARE the witness.
#   bare `append`   carries neither. The agent typed the exit code. Testimony,
#                   and the only kind of row that needs a witness.
#
# A green bare-append row on a ring that HAS a deterministic command must have
# an ambient row for the same change, spec, ring and baseline. No such row is
# `testimony` (a claim nothing saw). An ambient row at that key whose exit
# disagrees is `contradicted` (a claim something saw differently) — reported
# separately, because "nobody watched" and "the watcher says otherwise" call
# for different responses from a reader.
#
# WHAT THIS DELIBERATELY DOES NOT DO
# It does not decide whether an obligation is discharged — chain-state.sh owns
# that. It answers the narrower question of whether a row's exit code has any
# support beyond the writer's own assertion.
#
# EXIT CODES (three-way, never collapsed — same discipline as ledger.sh)
#   0  ran, every claim that needs a witness has one
#   1  ran, found uncorroborated or contradicted claims
#   2  COULD NOT DETERMINE (absent, unreadable or malformed ledger)
#
# Usage:
#   reconcile.sh --file F --change C [--spec S] [--baseline SHA] [--format json|text]
set -uo pipefail # NOT -e: every failure path below reports its own status

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
CONTRACT="$SELF_DIR/ledger-record-contract.jq"

die_finding() {
  printf 'reconcile: %s\n' "$1" >&2
  exit 1
}
die_undetermined() {
  printf 'reconcile: UNDETERMINED — %s\n' "$1" >&2
  exit 2
}

command -v jq >/dev/null 2>&1 ||
  die_undetermined "jq is required (declared prerequisite; see hooks/README.md)"
[ -f "$CONTRACT" ] || die_undetermined "record contract not found at $CONTRACT"

FILE="" CHANGE="" SPEC="" BASELINE="" FORMAT="text"

need_value() { # $1=flag $2=remaining count
  [ "$2" -ge 2 ] || die_finding "$1 requires a value"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --file)     need_value --file "$#";     FILE="$2";     shift 2 ;;
    --change)   need_value --change "$#";   CHANGE="$2";   shift 2 ;;
    --spec)     need_value --spec "$#";     SPEC="$2";     shift 2 ;;
    --baseline) need_value --baseline "$#"; BASELINE="$2"; shift 2 ;;
    --format)   need_value --format "$#";   FORMAT="$2";   shift 2 ;;
    # Never a catch-all that shifts past the unknown: a typo in --baseline
    # would silently widen the scope and return a clean result over rows the
    # caller never meant to include.
    *) die_finding "unrecognised argument: $1" ;;
  esac
done

[ -n "$FILE" ] || die_finding "--file is required"
[ -n "$CHANGE" ] || die_finding "--change is required"
case "$FORMAT" in json | text) ;; *) die_finding "--format must be json or text" ;; esac

# Absent and empty are DIFFERENT answers, exactly as in ledger.sh read. "There
# is no ledger" is not a measurement that every claim is corroborated;
# reporting it as a pass would be the defect this whole chain exists to remove.
if [ ! -e "$FILE" ]; then
  die_undetermined "no ledger at $FILE"
fi
[ -f "$FILE" ] || die_undetermined "$FILE is not a regular file"
[ -r "$FILE" ] || die_undetermined "$FILE is not readable"

# ── validate every row before drawing any conclusion ─────────────────────
# A malformed row makes the whole file undetermined rather than skipped: a
# reconciliation computed over the rows that happened to parse would report a
# clean result for a ledger it could not fully read.
n=0
while IFS= read -r line || [ -n "$line" ]; do
  n=$((n + 1))
  [ -n "$line" ] || die_undetermined "line $n is empty; a ledger holds one record per line"
  printf '%s' "$line" | jq -e . >/dev/null 2>&1 ||
    die_undetermined "line $n does not parse as JSON"
  err="$(printf '%s' "$line" | jq -e -f "$CONTRACT" 2>&1 >/dev/null |
    sed 's/.*ledger-record-contract: //' | head -1)"
  [ -z "$err" ] || die_undetermined "line $n violates the record contract: $err"
done <"$FILE"

[ "$n" -gt 0 ] || die_undetermined "$FILE holds no records"

# ── the reconciliation ───────────────────────────────────────────────────
# Judgment rings (R2 adversarial reasoning, R8 adversarial review, manual)
# have no deterministic command to witness — `ledger.sh verify` already holds
# them to naming a report artifact that resolves on disk, which is the
# corroboration appropriate to their kind. Requiring an ambient row for them
# would demand a witness for something no harness ever observes, and the only
# way to satisfy it would be to fabricate one.
report="$(jq -s -c \
  --arg change "$CHANGE" --arg spec "$SPEC" --arg baseline "$BASELINE" '
  def judgment_rings: ["R2", "R8", "manual"];

  [ .[] | select(.change == $change)
        | select($spec == "" or .spec == $spec)
        | select($baseline == "" or .baseline == $baseline) ] as $rows

  | ($rows | map(select(.source == "ambient"))) as $witnesses

  # A claim needing corroboration: a GREEN row, on a ring with a
  # deterministic command, that neither observed its own exit (no digest)
  # nor is itself a witness.
  # `.ring as $r` FIRST: piping into judgment_rings rebinds `.` to the array,
  # so a bare `judgment_rings | index(.ring)` indexes the ARRAY by a string
  # and errors. It stayed hidden because jq short-circuits `and` — only rows
  # that got past `.exit == 0` ever reached it, so green rows made the whole
  # filter fail while red ones passed cleanly. Same trap the record contract
  # documents for its own `rings` lookup.
  | ($rows | map(select(
        .exit == 0
        and (.ring as $r | judgment_rings | index($r)) == null
        and (has("digest") | not)
        and (.source // "") != "ambient"))) as $claims

  # A witness must have watched THE SAME COMMAND, not merely some command of
  # the same ring. Ambient rows are attributed to whichever change and spec
  # the gate infers from the working tree, and that inference is a guess — a
  # test run for one spec is filed against another, and under a (spec, ring,
  # baseline) key it would then corroborate a claim it never observed.
  # Matching the command asks the question that actually matters: this row
  # says THIS command exited 0 — did anything else see THIS command exit 0?
  | ($claims | map(
      . as $c
      | ($witnesses | map(select(
            .spec == $c.spec and .ring == $c.ring
            and .baseline == $c.baseline and .command == $c.command))) as $w
      | { spec: $c.spec, ring: $c.ring, obligation: $c.obligation,
          command: $c.command, baseline: $c.baseline,
          verdict: (if ($w | length) == 0 then "testimony"
                    elif ($w | map(select(.exit == 0)) | length) > 0 then "witnessed"
                    else "contradicted" end),
          observed: ($w | map(.exit)) })) as $verdicts

  | { change: $change,
      rows: ($rows | length),
      witnesses: ($witnesses | length),
      claims: ($claims | length),
      witnessed:    ($verdicts | map(select(.verdict == "witnessed"))    | length),
      testimony:    ($verdicts | map(select(.verdict == "testimony"))),
      contradicted: ($verdicts | map(select(.verdict == "contradicted"))) }
' "$FILE" 2>/dev/null)"

[ -n "$report" ] || die_undetermined "could not compute a reconciliation over $FILE"

n_testimony="$(printf '%s' "$report" | jq -r '.testimony | length')"
n_contradicted="$(printf '%s' "$report" | jq -r '.contradicted | length')"

if [ "$FORMAT" = "json" ]; then
  printf '%s\n' "$report"
else
  printf '%s' "$report" | jq -r '
    "reconcile: \(.change) — \(.rows) row(s), \(.witnesses) ambient witness(es), " +
    "\(.claims) claim(s) needing corroboration, \(.witnessed) witnessed"
    + (if (.contradicted | length) > 0 then "\n  contradicted (a witness recorded a different exit):"
        + (.contradicted | map("\n    \(.spec)/\(.ring) \(.obligation) — claimed 0, observed \(.observed | join(","))") | join(""))
       else "" end)
    + (if (.testimony | length) > 0 then "\n  testimony (green claim, no witness at this baseline):"
        + (.testimony | map("\n    \(.spec)/\(.ring) \(.obligation) — \(.command)") | join(""))
       else "" end)'
fi

if [ "$n_contradicted" -gt 0 ] || [ "$n_testimony" -gt 0 ]; then
  exit 1
fi
exit 0
