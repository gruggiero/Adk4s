#!/usr/bin/env bash
# ledger.sh — the evidence ledger: append-only record of DISCHARGED obligations.
#
# spec: evidence-ledger (change: add-correctness-substratum).
#
# WHY THIS EXISTS
# The workflow defines correctness as an unbroken chain: every requirement
# BOUND to a mechanism, each mechanism RESOLVED to an artifact that exists, and
# each artifact DISCHARGED — observed to run green in this session. spec-lint
# supplies the first two clauses (F7, F9). Nothing supplied the third. A
# checkpoint's per-ring result was prose the agent wrote about what it believed
# it had done, and nothing compared it against the runs that actually happened.
# This file is where a run becomes a fact.
#
# A row records the COMMAND and its EXIT STATUS, so it is re-checkable by
# re-running it. That does not make a row unforgeable — nothing in-process can
# — but it moves forgery from the default path to a deliberate act, which is
# the achievable goal.
#
# CONTRACT
# ./ledger-record-contract.jq is the single statement of the record format,
# shared with the oracle (tests/evidence-ledger.bats). Neither this script nor
# the tests restate the field rules; a second copy would be free to disagree.
#
# EXIT CODES (design Decision 2 — three-way, never collapsed)
#   0  ran, nothing wrong
#   1  ran, found something wrong (rejected a row, refused a modification)
#   2  COULD NOT DETERMINE (unreadable ledger, unknown format version)
# A caller receiving 1 knows the ledger is intact and the input was bad;
# receiving 2 knows nothing at all was established. Collapsing them would make
# a corrupt ledger indistinguishable from a clean one — the defect class this
# whole change exists to remove.
#
# Usage:
#   ledger.sh append --file F --change C --spec S --ring R --obligation O \
#                    --artifact A --command CMD --exit N --baseline SHA
#   ledger.sh read   --file F --change C [--spec S] [--baseline SHA]
#
# There is deliberately NO update or delete subcommand: append-only is enforced
# by there being nothing else to invoke.
set -uo pipefail # NOT -e: every failure path below reports its own status

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
CONTRACT="$SELF_DIR/ledger-record-contract.jq"

# The format version THIS script writes and knows how to read. A row carrying
# anything else makes a read UNDETERMINED — never skipped. Skipping unknown
# records would make a forward-incompatible ledger read as PARTIALLY
# discharged, which is silent degradation wearing the mask of a clean result.
SUPPORTED_V=1

die_finding() {
  printf 'ledger: %s\n' "$1" >&2
  exit 1
}
die_undetermined() {
  printf 'ledger: UNDETERMINED — %s\n' "$1" >&2
  exit 2
}

[ -f "$CONTRACT" ] || die_undetermined "record contract not found at $CONTRACT"
command -v jq >/dev/null 2>&1 ||
  die_undetermined "jq is required (declared prerequisite; see hooks/README.md)"

SUB="${1:-}"
[ $# -gt 0 ] && shift

# Checked BEFORE argument parsing: a modifying subcommand is refused for WHAT
# IT IS, whatever arguments follow it. Parsing first meant `update --row 1`
# was rejected for the unrecognised `--row`, so the caller never learned the
# actual reason — that the ledger is append-only.
case "$SUB" in
  update | delete | rewrite | edit)
    die_finding "the ledger is append-only; '$SUB' is not a subcommand. A recorded run is evidence, and evidence that can be edited is testimony."
    ;;
esac

FILE="" CHANGE="" SPEC="" RING="" OBLIGATION="" ARTIFACT="" COMMAND="" EXIT="" BASELINE=""

# Every flag consumes a value, and a flag with no value is a FINDING — never a
# loop. The first version used `shift 2`, which does not shift at all when only
# one argument remains, so `ledger.sh append --file F --baseline` spun forever
# and returned no exit code whatsoever. A hook calling that strands the session:
# a hang is the one outcome a caller cannot interpret.
need_value() { # $1=flag $2=remaining count
  [ "$2" -ge 2 ] || die_finding "$1 requires a value"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --file)
      need_value --file "$#"
      FILE="$2"
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
    --ring)
      need_value --ring "$#"
      RING="$2"
      shift 2
      ;;
    --obligation)
      need_value --obligation "$#"
      OBLIGATION="$2"
      shift 2
      ;;
    --artifact)
      need_value --artifact "$#"
      ARTIFACT="$2"
      shift 2
      ;;
    --command)
      need_value --command "$#"
      COMMAND="$2"
      shift 2
      ;;
    --exit)
      need_value --exit "$#"
      EXIT="$2"
      shift 2
      ;;
    --baseline)
      need_value --baseline "$#"
      BASELINE="$2"
      shift 2
      ;;
    # NOT a catch-all that shifts past the unknown. Silently ignoring an
    # unrecognised token is the shell analogue of `case _ =>` returning a valid
    # domain value: a typo such as `--baselin 723f22f` would disable the
    # staleness filter and return superseded evidence with exit 0.
    *) die_finding "unrecognised argument: $1" ;;
  esac
done

[ -n "$FILE" ] || die_finding "--file is required"

# ── validate one record against the contract ─────────────────────────────
# Returns the contract's own message, so a rejection names the violated clause
# rather than saying only that something was wrong.
contract_error() { # $1=json record; echoes the message, empty when conformant
  printf '%s' "$1" | jq -e -f "$CONTRACT" >/dev/null 2>"$TMP_ERR" && return 0
  sed 's/.*ledger-record-contract: //' "$TMP_ERR" | head -1
}

TMP_ERR="$(mktemp)"
trap 'rm -f "$TMP_ERR"' EXIT

case "$SUB" in

  # ── append ─────────────────────────────────────────────────────────────
  append)
    # `v` and `ts` are stamped HERE, not accepted from the caller: a writer
    # that let its caller choose the format version would let a caller write
    # rows this reader cannot interpret.
    ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

    # Missing REQUIRED inputs are named individually before the record is
    # built, so the message says which field — `jq --arg` would silently
    # substitute an empty string and the contract would then report the
    # emptiness rather than the absence.
    missing=""
    [ -n "$CHANGE" ] || missing="$missing change"
    [ -n "$SPEC" ] || missing="$missing spec"
    [ -n "$RING" ] || missing="$missing ring"
    [ -n "$OBLIGATION" ] || missing="$missing obligation"
    [ -n "$ARTIFACT" ] || missing="$missing artifact"
    [ -n "$COMMAND" ] || missing="$missing command"
    [ -n "$EXIT" ] || missing="$missing exit"
    [ -n "$BASELINE" ] || missing="$missing baseline"
    [ -z "$missing" ] || die_finding "missing required field(s):$missing"

    # Strict, because --argjson reinterprets: the loose [0-9-] test stored
    # `007` as 7 and `99999999999999999999` as 1e+20. The field that makes a
    # row falsifiable must hold the value the caller supplied, not a
    # normalisation of it.
    case "$EXIT" in
      0 | -[1-9] | [1-9] | -[1-9][0-9] | [1-9][0-9] | -[1-9][0-9][0-9] | [1-9][0-9][0-9]) ;;
      *) die_finding "exit must be an integer in -999..999 as written, got: $EXIT" ;;
    esac

    # Built with jq, never by string concatenation: the command field
    # routinely contains quotes and can contain newlines and backslashes, and
    # hand-rolled escaping is exactly where this project's shell defects live.
    record="$(jq -c -n \
      --argjson v "$SUPPORTED_V" \
      --arg ts "$ts" \
      --arg change "$CHANGE" \
      --arg spec "$SPEC" \
      --arg ring "$RING" \
      --arg obligation "$OBLIGATION" \
      --arg artifact "$ARTIFACT" \
      --arg command "$COMMAND" \
      --argjson exit "$EXIT" \
      --arg baseline "$BASELINE" \
      '{v:$v, ts:$ts, change:$change, spec:$spec, ring:$ring,
        obligation:$obligation, artifact:$artifact, command:$command,
        exit:$exit, baseline:$baseline}' 2>/dev/null)"
    [ -n "$record" ] || die_finding "could not build a record from the given values"

    err="$(contract_error "$record")"
    # Rejected BEFORE the file is touched: a partially written row would be a
    # corruption this script created.
    [ -z "$err" ] || die_finding "$err"

    # A ledger whose last byte is not a newline is a SUPPORTED shape — the
    # reader accepts it (`|| [ -n "$line" ]`). Appending blindly concatenated
    # the new record onto the previous row: the prior row was altered, the row
    # count did not grow, and the file became permanently unreadable. The
    # writer must own the record separator rather than assume the file's tail.
    if [ -s "$FILE" ] && [ "$(tail -c1 "$FILE" | wc -l)" -eq 0 ]; then
      printf '\n' >>"$FILE" || die_undetermined "could not append to $FILE"
    fi
    printf '%s\n' "$record" >>"$FILE" ||
      die_undetermined "could not append to $FILE"
    exit 0
    ;;

  # ── read ───────────────────────────────────────────────────────────────
  read)
    [ -n "$CHANGE" ] || die_finding "--change is required for read"

    # Absent and empty are DIFFERENT answers. "No ledger exists" is not a
    # measurement of zero; reporting it as zero rows would be absence of
    # evidence presented as evidence of absence.
    if [ ! -e "$FILE" ]; then
      printf 'ledger: no ledger at %s\n' "$FILE"
      exit 2
    fi
    # An input that cannot be read is UNDETERMINED, never a measurement of
    # zero. The loop's redirect failure used to be unchecked, so a chmod-000
    # ledger produced exit 0 and no rows — absence of evidence presented as
    # evidence of absence, which is the sentence this requirement forbids.
    # A directory crashed the read loop and surfaced as exit 1, confusing an
    # unreadable input with a finding.
    [ -f "$FILE" ] || die_undetermined "$FILE is not a regular file"
    [ -r "$FILE" ] || die_undetermined "$FILE is not readable"

    n=0
    matched=""
    while IFS= read -r line || [ -n "$line" ]; do
      n=$((n + 1))
      # A blank line is NOT skipped. Skipping it meant a newline-only file and
      # a blank line left mid-ledger by an interrupted write both read as a
      # clean, complete result. Every line in a ledger is a record or the
      # ledger is unreadable; there is no third category.
      [ -n "$line" ] || die_undetermined "line $n is empty; a ledger holds one record per line"

      if ! printf '%s' "$line" | jq -e . >/dev/null 2>&1; then
        die_undetermined "line $n does not parse as JSON"
      fi
      err="$(contract_error "$line")"
      [ -z "$err" ] || die_undetermined "line $n violates the record contract: $err"

      v="$(printf '%s' "$line" | jq -r '.v')"
      if [ "$v" != "$SUPPORTED_V" ]; then
        die_undetermined "line $n has format version $v; this reader knows $SUPPORTED_V. Refusing to report a partial result."
      fi

      keep="$(printf '%s' "$line" | jq -r \
        --arg change "$CHANGE" --arg spec "$SPEC" --arg baseline "$BASELINE" \
        'if .change != $change then "out-of-scope"
         elif ($spec != "" and .spec != $spec) then "out-of-scope"
         elif ($baseline != "" and .baseline != $baseline) then "stale"
         else "yes" end')"
      # NOTE (Ring 8, deferred to the gate): the spec's Then is "the row is
      # reported as stale AND the obligation is reported as not discharged".
      # Only the second half is implemented — a stale row is excluded, but not
      # NAMED. Reporting it would change what the approved oracle asserts
      # (bats merges stderr into $output, and the approved test asserts the
      # row is absent from output), so it is a gate item, not a silent change.
      [ "$keep" = "yes" ] || continue
      matched="$matched$line"$'\n'
    done <"$FILE"

    # A file that exists and holds no matching rows is a SUCCESSFUL
    # measurement of zero, reported as 0 — distinct from the absent case above.
    printf '%s' "$matched"
    exit 0
    ;;

  update | delete | rewrite | edit)
    die_finding "the ledger is append-only; '$SUB' is not a subcommand. A recorded run is evidence, and evidence that can be edited is testimony."
    ;;

  *)
    die_finding "unknown subcommand: '${SUB:-<none>}'. Expected: append, read."
    ;;
esac
