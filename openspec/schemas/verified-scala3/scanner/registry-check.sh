#!/usr/bin/env bash
# registry-check.sh — verify that every symbol referenced in the concept
# registry's Implementation maps still exists in the source tree.
#
# The Implementation map is the registry's single anti-drift mechanism: specs
# reference behavior (Concept/action) and the map binds behavior to code. A
# stale row silently breaks that binding, so this check belongs in CI next to
# format/lint checks.
#
# Usage: registry-check.sh [repo-root]     (default: current directory)
# Exit:  0 = all rows verified; 1 = stale rows found (listed on stdout).
#
# Three passes:
#
# 1. SYMBOLS — per backtick-quoted token in an "## Implementation map" or
#    "## Synchronizations" impl: line (first table cell — the Element label —
#    is skipped: it holds concept vocabulary, not code):
#   - Concept/action references (leading name matches a concept file) -> skip
#   - contains an ellipsis                            -> skip (runtime path)
#   - starts with specs/ or openspec/                 -> must be an openspec dir/file
#   - looks like a file path (contains "/" and ".")   -> must match a tracked file
#   - kebab-case (contains "-")                       -> full-token fixed-string grep
#   - otherwise: every identifier of length >= 6 in the token must appear
#     somewhere in the tracked, non-markdown source tree
#
# 2. FOLD FIELDS — an Implementation-map cell may declare the exact fields a
#    state fold populates, using the convention "maps field1, field2, ...".
#    Every named field must appear in the file referenced (backticked path)
#    in the same cell. This catches the declared-but-never-folded class of
#    bug (a field present on the model shape that no fold writes): listing a
#    field here asserts the fold actually maps it.
#
# 3. SPEC REFERENCES — every concept cited in a change spec's
#    "## Concepts Used (behavioral)" table (openspec/changes/*/specs/**, the
#    archive excluded) must be declared by a registry file, and a cited
#    `Concept/action` must name an action that appears in that concept file.
set -euo pipefail

ROOT="${1:-.}"
CONCEPTS_DIR="$ROOT/openspec/concepts"

if [ ! -d "$CONCEPTS_DIR" ]; then
  echo "registry-check: no $CONCEPTS_DIR directory — nothing to verify."
  exit 0
fi

fail=0
checked=0

# Concept names — from file names AND from concept declarations inside the
# files ("# Concept: X" / "## Concept: X" headings and "concept X" spec
# lines), so multi-concept files (e.g. bootstrap.md) are covered. Two forms
# are kept: lowercased-dashless (for symbol-pass skipping) and exact names
# mapped to their file (for the spec-reference pass).
concept_names=""
concept_index=""   # lines of "ExactName<TAB>file.md"
for cf in "$CONCEPTS_DIR"/*.md; do
  [ -e "$cf" ] || continue
  cbase="$(basename "$cf")"
  [ "$cbase" = "README.md" ] && continue
  concept_names="$concept_names $(basename "$cf" .md | tr -d '-' | tr '[:upper:]' '[:lower:]')"
  declared="$(grep -ohE '^#+ Concept: [A-Za-z]+|^concept [A-Za-z]+' "$cf" \
    | grep -oE '[A-Za-z]+$' | sort -u || true)"
  while IFS= read -r cname; do
    [ -z "$cname" ] && continue
    concept_names="$concept_names $(printf '%s' "$cname" | tr '[:upper:]' '[:lower:]')"
    concept_index="$concept_index$cname	$cbase
"
  done <<EOF
$declared
EOF
done

is_concept_ref() {
  # matches "Name" or "Name/action" where Name is a known concept
  local head
  head="$(printf '%s' "$1" | cut -d/ -f1 | tr -d '-' | tr '[:upper:]' '[:lower:]')"
  case " $concept_names " in
    *" $head "*) return 0 ;;
    *)           return 1 ;;
  esac
}

# grep the tracked source tree (everything except openspec/ and markdown)
in_sources() {
  git -C "$ROOT" grep -q -F -- "$1" -- ':!openspec' ':!*.md' 2>/dev/null
}

file_exists() {
  [ -n "$(git -C "$ROOT" ls-files -- "*$1" 2>/dev/null)" ]
}

for f in "$CONCEPTS_DIR"/*.md; do
  [ -e "$f" ] || continue
  base="$(basename "$f")"
  [ "$base" = "README.md" ] && continue

  # Rows of Implementation map tables (Element cell stripped — it holds
  # concept vocabulary, not code) + "impl:" lines of sync sections.
  lines="$(awk '
    /^## Implementation map/ {m=1; next}
    /^## /                   {m=0}
    m && /^\|/               {sub(/^\|[^|]*\|/, "|"); print}
    /^impl:/                 {print}
  ' "$f")"

  # Backtick-quoted tokens, one per line.
  tokens="$(printf '%s\n' "$lines" | grep -o '`[^`]*`' | tr -d '`' | sort -u || true)"

  while IFS= read -r token; do
    [ -z "$token" ] && continue

    # registry vocabulary and runtime paths are not code symbols
    if is_concept_ref "$token"; then continue; fi
    case "$token" in
      *…*|*"..."*) continue ;;
      specs/*|openspec/*)
        checked=$((checked + 1))
        if [ ! -e "$ROOT/openspec/${token#openspec/}" ] && [ ! -e "$ROOT/$token" ] \
           && [ ! -e "$ROOT/openspec/$token" ]; then
          echo "STALE  $base: openspec reference not found: $token"
          fail=1
        fi
        continue
        ;;
    esac

    checked=$((checked + 1))

    case "$token" in
      */*.*)
        if ! file_exists "$token"; then
          echo "STALE  $base: file not found: $token"
          fail=1
        fi
        continue
        ;;
    esac

    if printf '%s' "$token" | grep -q -- '-'; then
      if ! in_sources "$token"; then
        echo "STALE  $base: token not found: $token"
        fail=1
      fi
      continue
    fi

    # every identifier of length >= 6 must exist somewhere
    idents="$(printf '%s\n' "$token" | grep -oE '[A-Za-z_][A-Za-z0-9_]{5,}' | sort -u || true)"
    while IFS= read -r ident; do
      [ -z "$ident" ] && continue
      if ! in_sources "$ident"; then
        echo "STALE  $base: identifier not found: $ident   (from: $token)"
        fail=1
      fi
    done <<EOF2
$idents
EOF2
  done <<EOF
$tokens
EOF

  # ── Pass 2: fold fields — "maps f1, f2, ..." convention ──────────────────
  fold_rows="$(printf '%s\n' "$lines" | grep -- 'maps [A-Za-z_]' || true)"
  while IFS= read -r row; do
    [ -z "$row" ] && continue
    fold_file="$(printf '%s' "$row" | grep -o '`[^`]*/[^`]*\.[a-z]*`' | head -1 | tr -d '\`')"
    if [ -z "$fold_file" ] || ! file_exists "$fold_file"; then
      echo "STALE  $base: fold row declares mapped fields but names no resolvable file: $row"
      fail=1
      continue
    fi
    tracked="$(git -C "$ROOT" ls-files -- "*$fold_file" | head -1)"
    fields="$(printf '%s' "$row" | sed 's/.*maps //' | tr '|' ' ' \
      | grep -oE '[A-Za-z_][A-Za-z0-9_]*' || true)"
    while IFS= read -r field; do
      [ -z "$field" ] && continue
      checked=$((checked + 1))
      if ! grep -q -w -- "$field" "$ROOT/$tracked"; then
        echo "STALE  $base: fold field '$field' not found in $fold_file (declared mapped, but the fold does not mention it)"
        fail=1
      fi
    done <<EOF4
$fields
EOF4
  done <<EOF3
$fold_rows
EOF3
done

# ── Pass 3: spec references — Concepts Used (behavioral) tables ────────────
spec_refs=0
for spec in "$ROOT"/openspec/changes/*/specs/*/spec.md; do
  [ -e "$spec" ] || continue
  case "$spec" in */archive/*) continue ;; esac
  sbase="${spec#"$ROOT"/openspec/changes/}"

  # first table cell of each row in the behavioral concepts table
  cells="$(awk -F'|' '
    /^## Concepts Used \(behavioral\)/ {m=1; next}
    /^## /                             {m=0}
    m && /^\|/                         {print $2}
  ' "$spec")"
  refs="$(printf '%s\n' "$cells" | grep -o '`[^`]*`' | tr -d '\`' | sort -u || true)"

  while IFS= read -r ref; do
    [ -z "$ref" ] && continue
    case "$ref" in [A-Z]*) ;; *) continue ;; esac
    spec_refs=$((spec_refs + 1))
    cname="${ref%%/*}"
    action=""
    case "$ref" in */*) action="${ref#*/}" ;; esac
    cfile="$(printf '%s' "$concept_index" | awk -F'\t' -v n="$cname" '$1==n{print $2; exit}')"
    if [ -z "$cfile" ]; then
      echo "SPEC   $sbase: cites unknown concept '$cname' — no registry file declares it"
      fail=1
      continue
    fi
    if [ -n "$action" ] && ! grep -q -w -- "$action" "$CONCEPTS_DIR/$cfile"; then
      echo "SPEC   $sbase: cites action '$ref' but '$action' does not appear in $cfile"
      fail=1
    fi
  done <<EOF5
$refs
EOF5
done

if [ "$fail" -eq 0 ]; then
  echo "registry-check: OK ($checked implementation-map tokens verified, $spec_refs spec concept references checked)"
else
  echo ""
  echo "registry-check: FAILED — STALE rows mean an Implementation map (or a"
  echo "declared fold field) no longer matches the code; SPEC rows mean a"
  echo "change spec cites a concept or action the registry does not declare."
  echo "Fix the concept file (or the spec) as part of the change."
fi
exit "$fail"
