#!/usr/bin/env bash
# impact-scan.sh — the apply-phase Step 0 PUBLIC-TYPE-CHANGE IMPACT SCAN as a
# code-intelligence recipe (option B: composed Metals MCP calls + a local
# syntactic pass, digested for the agent).
#
# When a public type's variant set grows (new enum case, alias to a richer
# type), every downstream pattern match with a catch-all arm silently changes
# behavior WITHOUT any file edit. This script:
#   1. SEMANTIC: asks Metals MCP (get-usages) for the exact reference set of
#      the type — no comment/string false positives, no missed re-exports,
#      dependency-aware. Falls back to git grep if the endpoint is down
#      (fallback is BROADER: text hits, whole tree).
#   2. SYNTACTIC: in referencing files only, finds catch-all match arms
#      (`case _ =>`, `case other =>`, `case _: T =>`) near a usage of the
#      type, and reports them as candidates for the Step 0 resolution
#      (exhaustive | explicit reject | justified catch-all, recorded in the
#      spec's Proof Obligations).
#
# Usage: impact-scan.sh <fully.qualified.TypeName> [window]
#   window = lines around a usage considered "near" (default 25)
# Env:   METALS_MCP_URL (see metals-call.sh)
set -euo pipefail

FQCN="${1:?usage: impact-scan.sh <fully.qualified.TypeName> [window]}"
WINDOW="${2:-25}"
SIMPLE="${FQCN##*.}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

mode="semantic (Metals get-usages)"
usages=""
if "$SCRIPT_DIR/metals-call.sh" probe >/dev/null 2>&1; then
  # RESOLVE first — get-usages on an unresolvable FQCN silently degrades to
  # PACKAGE usages (nested symbols are Outer.Inner; opaque types live in a
  # synthetic `package` object).
  candidates="$("$SCRIPT_DIR/metals-call.sh" resolve "$FQCN" 2>/dev/null || true)"
  if ! printf '%s\n' "$candidates" | grep -qx -F "$FQCN"; then
    n_cand="$(printf '%s\n' "$candidates" | grep -c . || true)"
    if [ "$n_cand" -eq 1 ]; then
      corrected="$(printf '%s\n' "$candidates")"
      echo "impact-scan: note — '$FQCN' resolved to canonical '$corrected'"
      FQCN="$corrected"
    elif [ "$n_cand" -gt 1 ]; then
      echo "impact-scan: '$FQCN' is ambiguous — re-run with one of:"
      printf '%s\n' "$candidates" | sed 's/^/  /'
      exit 1
    fi
    # n_cand=0: unknown to the index — fall through to grep below
  fi
  usages="$("$SCRIPT_DIR/metals-call.sh" get-usages "{\"fqcn\":\"$FQCN\"}" 2>/dev/null || true)"
  if printf '%s' "$usages" | grep -q '^ERROR:'; then usages=""; fi
  # package-misresolution guard: a flood of line-1 (package/import) refs
  # means the FQCN degraded to its package — distrust the result
  if [ -n "$usages" ]; then
    total="$(printf '%s\n' "$usages" | grep -c . || true)"
    line1="$(printf '%s\n' "$usages" | grep -c ':1$' || true)"
    if [ "$total" -gt 50 ] && [ $((line1 * 4)) -gt "$total" ]; then
      echo "impact-scan: WARNING — '$FQCN' appears to have resolved to its PACKAGE ($line1/$total refs at line 1); falling back to grep"
      usages=""
    fi
  fi
fi
if [ -z "$usages" ]; then
  mode="TEXTUAL FALLBACK (git grep — broader: includes comments/strings, misses re-exports)"
  usages="$(git grep -n -w -- "$SIMPLE" -- '*.scala' 2>/dev/null \
    | grep '/src/main/' | cut -d: -f1,2 || true)"
fi

if [ -z "$usages" ]; then
  echo "impact-scan: no usages found for $FQCN ($mode)"
  exit 0
fi

n_refs="$(printf '%s\n' "$usages" | grep -c . || true)"
files="$(printf '%s\n' "$usages" | cut -d: -f1 | sort -u)"
n_files="$(printf '%s\n' "$files" | grep -c . || true)"

echo "impact-scan: $FQCN"
echo "  reference set: $n_refs refs in $n_files files — $mode"
echo ""
echo "  catch-all match arms near a usage (resolve each: exhaustive |"
echo "  explicit reject | justified — record in Proof Obligations):"

found=0
while IFS= read -r file; do
  [ -f "$file" ] || continue
  # usage lines for this file
  ulines="$(printf '%s\n' "$usages" | awk -F: -v f="$file" '$1==f {print $2}' | sort -n -u)"
  [ -z "$ulines" ] && continue
  # catch-all arms in the file
  catches="$(grep -nE 'case[[:space:]]+(_|[a-z][A-Za-z0-9_]*)[[:space:]]*(:[^=]*)?=>' "$file" \
    | grep -vE 'case[[:space:]]+[a-z][A-Za-z0-9_]*[[:space:]]*@' || true)"
  [ -z "$catches" ] && continue
  while IFS= read -r hit; do
    cline="${hit%%:*}"
    ctext="${hit#*:}"
    # keep only arms that are plausibly catch-alls: `_`, or a bare lowercase
    # binder without a constructor pattern
    printf '%s' "$ctext" | grep -qE 'case[[:space:]]+(_([[:space:]]*:[^=]*)?|[a-z][A-Za-z0-9_]*)[[:space:]]*=>' || continue
    # near a usage of the type?
    near="$(printf '%s\n' "$ulines" | awk -v c="$cline" -v w="$WINDOW" \
      '{d=$1-c; if (d<0) d=-d; if (d<=w) {print $1; exit}}')"
    [ -z "$near" ] && continue
    echo "    $file:$cline  $(printf '%s' "$ctext" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')   (usage of $SIMPLE at line $near)"
    found=$((found + 1))
  done <<EOF2
$catches
EOF2
done <<EOF
$files
EOF

if [ "$found" -eq 0 ]; then
  echo "    (none — no catch-all arms near any usage)"
fi
echo ""
echo "  files in the reference set:"
printf '%s\n' "$files" | sed 's/^/    /'
