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
      req_titles[n_reqs] = req_name
      req_lines[n_reqs] = NR
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
      if (in_po && $0 ~ /^\|/ && $0 !~ /^\|[ \t:]*-/ && $0 !~ /^\| *Obligation/) {
        po_rows++
        check_source($0, NR)
      }
    }
    # ── F6/F7: Proof-Obligations Source must NAME what it comes from ──────
    # Resolvable forms (schema v8 mandate):
    #   Requirement: <exact title>   (preferred — survives reordering)
    #   Requirement N | RN           (ordinal — positional, W4)
    #   Property:|Scenario:|Invariant:|Compile-Negative:|Temporal:|
    #   Criterion:|Type-Constraint:|MUST-CONFIRM  (non-requirement sources)
    # "Requirement" with no identifier names nothing -> FAIL F6.
    function check_source(row, lineno,   nf, cells, src, i, toks, nt, idx, hit, low, j, key) {
      nf = split(row, cells, "|")
      if (nf < 4) return                      # not a 4-column obligations row
      src = cells[3]; gsub(/^[ \t]+|[ \t]+$/, "", src)
      if (src == "" || src ~ /^<!--/) return
      hit = 0
      # ordinal references: "Requirement 3", "R3"
      nt = split(src, toks, /[^A-Za-z0-9]+/)
      for (i = 1; i <= nt; i++) {
        idx = 0
        if (toks[i] ~ /^R[0-9]+$/)                                idx = substr(toks[i], 2) + 0
        else if (toks[i] == "Requirement" && toks[i+1] ~ /^[0-9]+$/) idx = toks[i+1] + 0
        if (idx >= 1 && idx <= n_reqs) {
          covered[idx] = 1; hit = 1; ordinal_refs++
        } else if (idx > n_reqs && idx > 0) {
          printf "FAIL F6 line %d: Source cites Requirement %d but the spec has %d\n", lineno, idx, n_reqs
          hit = 1
        }
      }
      # title reference: the Source quotes an actual requirement title
      low = tolower(src)
      for (j = 1; j <= n_reqs; j++) {
        key = tolower(substr(req_titles[j], 1, 40))
        if (key != "" && index(low, key) > 0) { covered[j] = 1; hit = 1; title_refs++ }
      }
      if (hit) return
      # non-requirement source kinds are legitimate — but must be TYPED,
      # i.e. the kind must be followed by ": <name>" or " <ordinal>"
      if (src ~ /(^|[^A-Za-z])(Property|Properties|Scenario|Scenarios|Invariant|Compile-Negative|Temporal|Criterion|Type-Constraint|MUST-CONFIRM|Design|Non-goal)[ ]*(:|[0-9])/ ||
          src ~ /^(MUST-CONFIRM|Compile-Negative)([^A-Za-z]|$)/)
        return
      printf "FAIL F6 line %d: Source names no resolvable reference: %s\n", lineno, src
      printf "         (use \"Requirement: <exact title>\", \"Requirement N\", or a typed source like \"Property: <name>\")\n"
    }
    END {
      flush_req(); flush_prop(); flush_temp()
      if (n_reqs > 0 && !has_po)
        printf "FAIL F4: spec has %d requirement(s) but no ## Proof Obligations section\n", n_reqs
      if (has_po && po_rows < n_reqs)
        printf "WARN W2: Proof Obligations has %d data row(s) for %d requirement(s)\n", po_rows, n_reqs
      if (has_po)
        for (i = 1; i <= n_reqs; i++)
          if (!covered[i])
            printf "FAIL F7 line %d: requirement \"%s\" is named by NO proof obligation (unenforced)\n", req_lines[i], req_titles[i]
      if (ordinal_refs > 0 && title_refs == 0)
        printf "WARN W4: %d obligation Source(s) reference requirements BY ORDINAL only — reordering requirements silently re-points them; prefer \"Requirement: <exact title>\"\n", ordinal_refs
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
