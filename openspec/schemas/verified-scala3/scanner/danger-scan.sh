#!/usr/bin/env bash
# danger-scan.sh — report dangerous correctness patterns in a spec's diff.
#
# Ring 1's dangerous-pattern scan, made mechanical: "clean" is this script
# exiting 0, not memory. Scans the PRODUCTION files changed since the given
# baseline (git diff <baseline> --name-only, src/main only) plus any files
# passed via --also (e.g. importers of a widened public type, per the apply
# phase's Step 0 PUBLIC-TYPE-CHANGE IMPACT SCAN).
#
# Every reported line must be REMOVED or JUSTIFIED. A justified line carries
# a same-line comment:   // danger-scan:allow <reason>
# and is then excluded from the report.
#
# Patterns (candidates — some hits are legitimate; that is what the allow
# comment is for):
#   unsafe-get        .get on Option/Either/Try (not .get(...) map lookups)
#   unsafe-head       .head / .tail / .last on collections
#   catch-all         case _ => / case other => / case _: T =>
#   cast              asInstanceOf / @unchecked
#   blocking          Await. / Thread.sleep
#   swallowed         NonFatal (verify the error is not discarded)
#   unreachable-claim "unreachable" / "cannot happen" / "should never"
#   lint-off          scalafix:off
#
# Usage: danger-scan.sh [baseline-ref] [--also file ...]
#   baseline-ref defaults to HEAD (uncommitted work in progress).
# Exit: 0 = no unjustified hits; 1 = hits reported.
set -euo pipefail

BASELINE="HEAD"
also_files=()
parsing_also=0
for arg in "$@"; do
  if [ "$arg" = "--also" ]; then
    parsing_also=1
  elif [ "$parsing_also" -eq 1 ]; then
    also_files+=("$arg")
  else
    BASELINE="$arg"
  fi
done

diff_files="$(git diff --name-only "$BASELINE" -- '*.scala' 2>/dev/null \
  | grep '/src/main/' || true)"

all_files=""
for f in $diff_files "${also_files[@]+"${also_files[@]}"}"; do
  [ -f "$f" ] && all_files="$all_files$f
"
done
all_files="$(printf '%s' "$all_files" | sort -u)"

if [ -z "$all_files" ]; then
  echo "danger-scan: no production .scala files changed since $BASELINE (and no --also files)."
  exit 0
fi

hits=0
scan() { # $1=label $2=ERE $3=file [$4=-i]
  local label="$1" pattern="$2" file="$3" ci="${4:-}"
  local found
  found="$(grep -nE $ci -- "$pattern" "$file" | grep -v 'danger-scan:allow' || true)"
  if [ -n "$found" ]; then
    printf '%s\n' "$found" | while IFS= read -r line; do
      echo "  [$label] $line"
    done
    hits=$((hits + $(printf '%s\n' "$found" | wc -l)))
  fi
  return 0
}

total=0
while IFS= read -r file; do
  [ -z "$file" ] && continue
  report="$(
    hits=0
    scan unsafe-get        '\.get([^A-Za-z0-9_(]|$)'                    "$file"
    scan unsafe-head       '\.(head|tail|last)([^A-Za-z0-9_]|$)'        "$file"
    scan catch-all         'case[[:space:]]+(_|other)[[:space:]]*(:[^=]*)?=>' "$file"
    scan cast              'asInstanceOf|@unchecked'                    "$file"
    scan blocking          'Await\.|Thread\.sleep'                      "$file"
    scan swallowed         'NonFatal'                                   "$file"
    scan unreachable-claim 'unreachable|cannot happen|should never'     "$file" -i
    scan lint-off          'scalafix:off'                               "$file"
  )"
  if [ -n "$report" ]; then
    echo "danger-scan: $file"
    printf '%s\n' "$report"
    total=$((total + $(printf '%s\n' "$report" | wc -l)))
  fi
done <<EOF
$all_files
EOF

if [ "$total" -eq 0 ]; then
  echo "danger-scan: OK — no unjustified dangerous patterns since $BASELINE."
  exit 0
fi
echo ""
echo "danger-scan: $total candidate line(s). Remove each, or justify it with a"
echo "same-line '// danger-scan:allow <reason>' comment. A catch-all that maps"
echo "an unrecognized variant to a VALID domain value is the bug class this"
echo "scan exists for — that one is never justifiable."
exit 1
