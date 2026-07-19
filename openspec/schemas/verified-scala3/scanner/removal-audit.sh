#!/usr/bin/env bash
# removal-audit.sh — the apply-phase Step 12 REMOVAL AUDIT as a
# code-intelligence recipe (composed Metals MCP calls, digested output).
#
# When a member/type is REMOVED from a refactored concept, its dependents
# (helper factories, opaque adapters, extension methods that existed only to
# serve it) survive compilation as dead public code — `RemoveUnused` does
# not flag public members. This recipe answers the audit's core question
# authoritatively: WHO STILL USES a suspected orphan?
#
# Modes:
#   removal-audit.sh <fqcn> [<fqcn>...]
#       For each suspect symbol: its remaining usage sites (Metals
#       get-usages). Verdict ORPHAN-CANDIDATE when every reference lies in
#       its own defining file (self-references only) — delete it in this
#       change or retain it with written rationale.
#   removal-audit.sh --suggest <baseline-SHA>
#       Lists public definitions DELETED since the baseline (from git diff)
#       — the removals whose serving helpers you should name as suspects —
#       and MODIFIED files' surviving top-level definitions as a suspect
#       shortlist.
#
# Falls back to git grep (broader, textual) when the Metals endpoint is
# down. Env: METALS_MCP_URL (see metals-call.sh).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEF_RE='(def|val|lazy val|var|class|case class|object|trait|enum|opaque type|type|extension)'

if [ "${1:-}" = "--suggest" ]; then
  BASE="${2:?usage: removal-audit.sh --suggest <baseline-SHA>}"
  echo "removal-audit --suggest: definitions REMOVED since $BASE"
  echo "  (name their serving helpers as suspects: removal-audit.sh <fqcn>...)"
  git diff "$BASE" -- '*.scala' \
    | grep -E "^-[[:space:]]*(private |protected |final |implicit |override )*${DEF_RE}[[:space:]]+[A-Za-z_]" \
    | grep -v '^---' \
    | sed -E "s/^-[[:space:]]*//" | sed 's/[({=].*//' | sort -u | sed 's/^/    - /'
  echo ""
  echo "  files modified since $BASE (their surviving public members are the suspect pool):"
  git diff --name-only "$BASE" -- '*.scala' | grep '/src/main/' | sed 's/^/    /' || true
  exit 0
fi

[ $# -ge 1 ] || { echo "usage: removal-audit.sh <fqcn> [<fqcn>...] | --suggest <baseline-SHA>" >&2; exit 1; }

semantic=1
"$SCRIPT_DIR/metals-call.sh" probe >/dev/null 2>&1 || semantic=0

echo "removal-audit: ${#} suspect(s) — $([ $semantic -eq 1 ] && echo 'semantic (Metals get-usages)' || echo 'TEXTUAL FALLBACK (git grep)')"
echo ""

orphans=0
for fqcn in "$@"; do
  simple="${fqcn##*.}"
  if [ $semantic -eq 1 ]; then
    # resolve to the canonical FQCN first (nested symbols are Outer.Inner;
    # opaque types live in a synthetic `package` object; get-usages on a
    # wrong FQCN silently returns PACKAGE usages)
    candidates="$("$SCRIPT_DIR/metals-call.sh" resolve "$fqcn" 2>/dev/null || true)"
    if ! printf '%s\n' "$candidates" | grep -qx -F "$fqcn"; then
      n_cand="$(printf '%s\n' "$candidates" | grep -c . || true)"
      if [ "$n_cand" -eq 1 ]; then
        fqcn="$(printf '%s\n' "$candidates")"
        echo "  (resolved to canonical: $fqcn)"
      elif [ "$n_cand" -gt 1 ]; then
        echo "  $fqcn: ambiguous — re-run with one of:"
        printf '%s\n' "$candidates" | sed 's/^/      /'
        echo ""
        continue
      fi
    fi
    usages="$("$SCRIPT_DIR/metals-call.sh" get-usages "{\"fqcn\":\"$fqcn\"}" 2>/dev/null || true)"
    if [ -z "$usages" ] || printf '%s' "$usages" | grep -q '^ERROR:'; then
      echo "  $fqcn: not resolvable via Metals (already deleted, or wrong FQCN) — textual check:"
      hits="$(git grep -nw -- "$simple" -- '*.scala' 2>/dev/null | sed -n '1,5p' || true)"
      if [ -n "$hits" ]; then printf '%s\n' "$hits" | sed 's/^/      /'; else echo "      (no textual hits either — likely fully removed)"; fi
      echo ""
      continue
    fi
  else
    usages="$(git grep -nw -- "$simple" -- '*.scala' 2>/dev/null | cut -d: -f1,2 || true)"
  fi

  files="$(printf '%s\n' "$usages" | cut -d: -f1 | sort -u | grep -c . || true)"
  refs="$(printf '%s\n' "$usages" | grep -c . || true)"

  # defining file: the referenced file that declares the symbol
  def_file="$(printf '%s\n' "$usages" | cut -d: -f1 | sort -u | while IFS= read -r f; do
    [ -f "$f" ] && grep -qE "${DEF_RE}[[:space:]]+$simple\b" "$f" && { echo "$f"; break; }
  done)"

  ext_files="$(printf '%s\n' "$usages" | cut -d: -f1 | sort -u | grep -v -x -F "${def_file:-∅}" | grep -c . || true)"

  if [ "$ext_files" -eq 0 ] && [ -n "$def_file" ]; then
    echo "  $fqcn: ORPHAN-CANDIDATE — all $refs reference(s) are in its own defining file ($def_file)."
    echo "      → delete it in this change, or retain with written rationale (Step 12)."
    orphans=$((orphans + 1))
  else
    echo "  $fqcn: IN USE — $refs reference(s) across $files file(s):"
    printf '%s\n' "$usages" | cut -d: -f1 | sort | uniq -c | sort -rn | head -6 | sed 's/^/      /'
  fi
  echo ""
done

echo "removal-audit: $orphans orphan-candidate(s) of $# suspect(s)."
[ "$orphans" -gt 0 ] && exit 1 || exit 0
