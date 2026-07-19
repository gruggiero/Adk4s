#!/usr/bin/env bash
# spec-lint.sh — mechanical pre-pass for the spec-lint artifact.
#
# Enforces the greppable subset of the schema's lint checks so that "lint
# clean" is objective and CI-enforceable (same philosophy as
# registry-check.sh). The judgment checks (observability, testability,
# type-feasibility, altitude) remain in the spec-lint artifact instruction —
# this script does not replace them.
#
# FAIL (exit 1) checks:
#   F1  every "### Requirement:" block contains SHALL or MUST before its
#       first **Given** clause (openspec validate --strict also rejects this)
#   F2  every requirement containing "only", "never", or "must not" has at
#       least one "#### Scenario:" (the ADVERSARIAL rule's mechanical half —
#       whether a scenario's input is actually forbidden stays human-checked)
#   F3  every "### Property:" block declares a "**Generator strategy**" line
#   F4  a spec with requirements has a "## Proof Obligations" section
#   F5  every "### Temporal:" block has "**Trigger event**" and
#       "**Response event**" lines
#
# WARN (reported, exit unaffected):
#   W1  vague words (valid/fast/reasonable/correct/appropriate) inside
#       requirement blocks — confirm a concrete definition sits next to them
#   W2  Proof Obligations data rows fewer than requirement count
#   W3  requirements matched by F2 — listed so the human can confirm the
#       scenario input is genuinely forbidden, not just present
#
# Usage: spec-lint.sh [change-dir | repo-root]   (default: current directory)
#   change-dir: lint that change's specs/**/spec.md
#   repo-root:  lint every active change (openspec/changes/*, archive excluded)
set -euo pipefail

TARGET="${1:-.}"

specs=""
if [ -d "$TARGET/specs" ]; then
  specs="$(find "$TARGET/specs" -name 'spec.md' | sort)"
elif [ -d "$TARGET/openspec/changes" ]; then
  specs="$(find "$TARGET/openspec/changes" -path '*/specs/*' -name 'spec.md' \
    ! -path '*/archive/*' | sort)"
else
  echo "spec-lint: no specs found under $TARGET (expected <change>/specs/ or openspec/changes/)" >&2
  exit 2
fi

if [ -z "$specs" ]; then
  echo "spec-lint: no spec files to lint under $TARGET"
  exit 0
fi

fails=0
warns=0
files=0

while IFS= read -r spec; do
  [ -z "$spec" ] && continue
  files=$((files + 1))
  findings="$(awk '
    function flush_req() {
      if (req_name == "") return
      if (!req_has_norm)
        printf "FAIL F1 line %d: requirement \"%s\" has no SHALL/MUST before its first **Given**\n", req_line, req_name
      if (req_negative && req_scenarios == 0)
        printf "FAIL F2 line %d: negative requirement \"%s\" (only/never/must not) has no scenario at all\n", req_line, req_name
      else if (req_negative)
        printf "WARN W3 line %d: requirement \"%s\" is negative — confirm at least one scenario input is forbidden by it\n", req_line, req_name
      req_name = ""
    }
    function flush_prop() {
      if (prop_name == "") return
      if (!prop_has_gen)
        printf "FAIL F3 line %d: property \"%s\" has no **Generator strategy** line\n", prop_line, prop_name
      prop_name = ""
    }
    function flush_temp() {
      if (temp_name == "") return
      if (!temp_has_trig)
        printf "FAIL F5 line %d: temporal \"%s\" has no **Trigger event** line\n", temp_line, temp_name
      if (!temp_has_resp)
        printf "FAIL F5 line %d: temporal \"%s\" has no **Response event** line\n", temp_line, temp_name
      temp_name = ""
    }
    /^### Requirement:/ {
      flush_req(); flush_prop(); flush_temp()
      n_reqs++
      req_name = substr($0, 18); gsub(/^[ \t]+|[ \t]+$/, "", req_name)
      req_line = NR; req_has_norm = 0; req_seen_given = 0
      req_negative = 0; req_scenarios = 0; in_po = 0
      next
    }
    /^### Property:/ {
      flush_req(); flush_prop(); flush_temp()
      prop_name = substr($0, 15); gsub(/^[ \t]+|[ \t]+$/, "", prop_name)
      prop_line = NR; prop_has_gen = 0; in_po = 0
      next
    }
    /^### Temporal:/ {
      flush_req(); flush_prop(); flush_temp()
      temp_name = substr($0, 15); gsub(/^[ \t]+|[ \t]+$/, "", temp_name)
      temp_line = NR; temp_has_trig = 0; temp_has_resp = 0; in_po = 0
      next
    }
    /^## / {
      flush_req(); flush_prop(); flush_temp()
      in_po = ($0 ~ /^## Proof Obligations/)
      if (in_po) has_po = 1
      next
    }
    {
      if (req_name != "") {
        if (!req_seen_given && $0 ~ /\*\*Given\*\*/) req_seen_given = 1
        if (!req_seen_given && $0 ~ /(^|[^A-Za-z])(SHALL|MUST)([^A-Za-z]|$)/) req_has_norm = 1
        low = tolower($0)
        if (low ~ /(^|[^[:alnum:]])only([^[:alnum:]]|$)/ ||
            low ~ /(^|[^[:alnum:]])never([^[:alnum:]]|$)/ ||
            low ~ /must not/) req_negative = 1
        if ($0 ~ /^#### Scenario:/) req_scenarios++
        if (low ~ /(^|[^[:alnum:]])(valid|fast|reasonable|correct|appropriate)([^[:alnum:]]|$)/)
          printf "WARN W1 line %d: vague word in requirement \"%s\": %s\n", NR, req_name, $0
      }
      if (prop_name != "" && $0 ~ /\*\*Generator strategy\*\*/) prop_has_gen = 1
      if (temp_name != "") {
        if ($0 ~ /\*\*Trigger event\*\*/)  temp_has_trig = 1
        if ($0 ~ /\*\*Response event\*\*/) temp_has_resp = 1
      }
      if (in_po && $0 ~ /^\|/ && $0 !~ /^\|[ \t:]*-/ && $0 !~ /^\| *Obligation/) po_rows++
    }
    END {
      flush_req(); flush_prop(); flush_temp()
      if (n_reqs > 0 && !has_po)
        printf "FAIL F4: spec has %d requirement(s) but no ## Proof Obligations section\n", n_reqs
      if (has_po && po_rows < n_reqs)
        printf "WARN W2: Proof Obligations has %d data row(s) for %d requirement(s)\n", po_rows, n_reqs
    }
  ' "$spec")"

  if [ -n "$findings" ]; then
    echo "spec-lint: $spec"
    printf '%s\n' "$findings" | sed 's/^/  /'
    f="$(printf '%s\n' "$findings" | grep -c '^FAIL' || true)"
    w="$(printf '%s\n' "$findings" | grep -c '^WARN' || true)"
    fails=$((fails + f))
    warns=$((warns + w))
  fi
done <<EOF
$specs
EOF

echo "spec-lint: $files spec file(s), $fails FAIL, $warns WARN"
if [ "$fails" -gt 0 ]; then
  echo "spec-lint: FAILED — F-checks are lint failures; fix the specs and re-run."
  exit 1
fi
exit 0
