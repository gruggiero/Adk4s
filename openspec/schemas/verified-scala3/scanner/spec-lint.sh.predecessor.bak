#!/usr/bin/env bash
# spec-lint.sh — mechanical pre-pass for the spec-lint artifact.
#
# Enforces the greppable subset of the schema's lint checks so that "lint
# clean" is objective and CI-enforceable (same philosophy as
# registry-check.sh). The judgment checks (observability, testability,
# type-feasibility, altitude COMPLIANCE) remain in the spec-lint artifact
# instruction — this script does not replace them.
#
# It DOES, however, decide every judgment check's APPLICABILITY. A conditional
# rule has two halves: "does this rule apply here?" (a repository fact) and
# "does this spec obey it?" (judgment). Only the second is a judgment call.
# The CONTEXT block below is printed unconditionally and states the first half
# as machine-attested fact, including the negative case — because "N/A"
# inferred from an assumption is how a real review once skipped the altitude
# rule entirely, having never looked for openspec/concepts/.
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
#   F6  every Proof-Obligations Source names a resolvable reference
#   F7  every requirement is named by >= 1 obligation (reachability)
#   F8  every TYPED source (Property:/Scenario:) names a heading that
#       EXISTS in the spec — a typo used to resolve to nothing
#   F9  (--artifacts only) every code-shaped Artifact token resolves to a
#       tracked file. OFF by default: specs are written BEFORE their tests
#       exist, so the artifact column is a commitment at planning time and a
#       fact only after implementation. Run with --artifacts at apply Step 12.
#   F10 when a behavioural registry EXISTS (openspec/concepts/), a spec with
#       requirements has a "## Concepts Used (behavioral)" section — the
#       structural half of the ALTITUDE rule. Applicability is a fact, so
#       this half needs no judgment; whether the cited concepts are the RIGHT
#       ones, and whether clause prose stays behavioural, still does.
#
# WARN (reported, exit unaffected):
#   W6  a spec declaring Ring 6 Formal Contracts with no obligation naming a
#       BRIDGE/mirror artifact — a proof about a model nobody runs says
#       nothing about the shipped system (see templates/verified-mirror.md)
#   W7  altitude candidates: a code identifier inside a Given/When/Then
#       clause. Four deterministic shapes only — build command, source file,
#       fully-qualified name, and a token that the project's own ledgers
#       classify as code (present in openspec/concept-inventory.md, absent
#       from the behavioural registry). Reported, never failed: a domain term
#       may share a type name, and that call is the reviewer's.
#   W1  vague words (valid/fast/reasonable/correct/appropriate) inside
#       requirement blocks — confirm a concrete definition sits next to them
#   W2  Proof Obligations data rows fewer than requirement count
#   W3  requirements matched by F2 — listed so the human can confirm the
#       scenario input is genuinely forbidden, not just present
#
# Usage: spec-lint.sh [--artifacts] [--context-only] [change-dir | repo-root]
#   change-dir:     lint that change's specs/**/spec.md
#   repo-root:      lint every active change (openspec/changes/*, archive excluded)
#   --artifacts:    also run F9 (post-implementation; see above)
#   --context-only: print the CONTEXT block and exit 0, linting nothing. This is
#                   the single source of the applicability facts — hooks/gate.sh
#                   consumes it rather than recomputing them, so a session-start
#                   injection and a lint run can never disagree.
set -euo pipefail

ARTIFACTS=0
CONTEXT_ONLY=0
FORMAT_JSON=0
TARGET="."
for arg in "$@"; do
  case "$arg" in
    --artifacts)    ARTIFACTS=1 ;;
    --context-only) CONTEXT_ONLY=1 ;;
    --format)       ;;  # consume value in next iteration
    json)           FORMAT_JSON=1 ;;  # --format json
    *)              TARGET="$arg" ;;
  esac
done

# ── CONTEXT: applicability of the conditional judgment checks ─────────────
# Printed ALWAYS, and stating the negative case as loudly as the positive.
# The reviewer copies this block into spec-lint.md verbatim; a check may be
# recorded N/A only when a line here says so.
repo_root="$(git rev-parse --show-toplevel 2>/dev/null || echo .)"
registry_dir="$repo_root/openspec/concepts"
inventory_md="$repo_root/openspec/concept-inventory.md"
profile_md="$repo_root/openspec/capability-profile.md"

has_registry=0
# D5 FIX: in JSON mode, suppress the human-readable CONTEXT block
if [ "$FORMAT_JSON" -eq 1 ]; then
  exec 3>&1 1>/dev/null
fi
echo "spec-lint: CONTEXT — repository facts. These decide each conditional check's"
echo "           APPLICABILITY. Compliance remains yours; applicability does not."

# ── instruction drift ────────────────────────────────────────────────────
# The skills carry "generatedBy: verified-scala3-schema/<N>". That field sat
# frozen at 7.0.0 while the schema reached 11, and nothing read it — so an
# agent could follow instructions predating the very checks it was meant to
# apply, silently. Recorded-but-never-checked is the failure mode this
# workflow exists to remove; the field is now read.
schema_yaml="$(dirname "$0")/../schema.yaml"
schema_ver="$(awk -F': *' '/^version:/ {print $2; exit}' "$schema_yaml" 2>/dev/null || true)"
[ -n "$schema_ver" ] && echo "  schema                openspec/schemas/verified-scala3  v$schema_ver"
skill_found=0
for root in "$repo_root/.agents/skills" "$repo_root/.claude/skills" "$repo_root/.pi/skills" \
            "$HOME/.agents/skills" "$HOME/.claude/skills" "$HOME/.zcode/skills"; do
  sk="$root/openspec-spec-lint/SKILL.md"
  [ -f "$sk" ] || continue
  skill_found=1
  sv="$(awk -F'verified-scala3-schema/' '/generatedBy:/ {split($2, a, "."); print a[1]; exit}' "$sk" 2>/dev/null || true)"
  if [ -z "$sv" ]; then
    echo "  !! skill $root/openspec-spec-lint declares no schema version — pre-v7 install"
  elif [ -n "$schema_ver" ] && [ "$sv" != "$schema_ver" ]; then
    echo "  !! INSTRUCTION DRIFT: skill at $root is schema v$sv, this schema is v$schema_ver."
    echo "     Checks added after v$sv are NOT in the instructions you are following."
    echo "     Re-install (scanner/install-skills.sh) before trusting this report."
  fi
done
[ "$skill_found" -eq 0 ] && echo "  (no openspec-spec-lint skill installed in the searched roots)"
if [ -d "$registry_dir" ]; then
  has_registry=1
  n_concepts="$(find "$registry_dir" -name '*.md' ! -name 'README.md' | wc -l | tr -d ' ')"
  echo "  behavioural registry  openspec/concepts/             PRESENT ($n_concepts concepts)"
  echo "    -> check 17 ALTITUDE **APPLIES**. \"N/A\" is not a valid verdict for it."
  echo "       F10 checks the structural half; W7 lists code-identifier candidates;"
  echo "       reading the clause prose for behavioural altitude is still your job."
else
  echo "  behavioural registry  openspec/concepts/             ABSENT"
  echo "    -> check 17 ALTITUDE is N/A (attested by this script, not assumed)."
fi
if [ -f "$inventory_md" ]; then
  # First data cell of every inventory table row. Backticks are optional:
  # adk4s writes `Foo`, graphStore writes Foo, and a parser that assumed one
  # of them read the other as an empty inventory without saying so.
  inv_types="$(awk -F'|' '/^\|/ {
      cell = $2
      gsub(/`/, "", cell)
      gsub(/^[ \t]+|[ \t]+$/, "", cell)
      sub(/\[.*/, "", cell)                       # Foo[A] -> Foo
      if (cell ~ /^[A-Z][A-Za-z0-9_]*$/ && cell != "Type") print cell
    }' "$inventory_md" | sort -u)"
  n_inv="$(printf '%s' "$inv_types" | grep -c . || true)"
  echo "  type inventory        openspec/concept-inventory.md  PRESENT ($n_inv typed rows)"
  echo "    -> check 6 (reused concepts exist) **APPLIES**."
  if [ "$n_inv" -eq 0 ]; then
    echo "    !! parsed 0 type rows — the file exists but this script read nothing"
    echo "       from it. Fix the table shape before trusting W7 silence."
  fi
else
  inv_types=""
  echo "  type inventory        openspec/concept-inventory.md  ABSENT"
  echo "    -> check 6 is N/A; run the concept scanner before trusting reuse claims."
fi
if [ -f "$profile_md" ]; then
  det_kit="$(grep -o -i 'TestControl\|TestKit\|VirtualTime\|TestScheduler' "$profile_md" 2>/dev/null | sort -u | tr '\n' ' ' | sed 's/ $//')"
  echo "  capability profile    openspec/capability-profile.md PRESENT"
  if [ -n "$det_kit" ]; then
    echo "    -> checks 3 (testable with detected stack) and 18 (CONCURRENCY) **APPLY**"
    echo "       deterministic test kit detected: $det_kit"
  else
    echo "    -> check 3 **APPLIES**. Check 18: no deterministic test kit detected —"
    echo "       a concurrency requirement here is a capability gap, not an N/A."
  fi
else
  echo "  capability profile    openspec/capability-profile.md ABSENT"
  echo "    -> run detect-capabilities first; checks 3 and 18 cannot be judged."
fi

# Tokens the project's own ledgers classify as CODE: present in the type
# inventory, absent from the behavioural registry. A name in BOTH is domain
# vocabulary that happens to have a type, and is legitimate in a clause.
code_ids=""
if [ "$has_registry" -eq 1 ] && [ -n "$inv_types" ]; then
  code_ids="$(
    comm -23 \
      <(printf '%s\n' "$inv_types") \
      <(grep -h '^# Concept: ' "$registry_dir"/*.md 2>/dev/null \
          | sed 's/^# Concept: *//; s/[ \t]*$//' | sort -u) \
    | tr '\n' ' ' | sed 's/[ \t]*$//'
  )"
fi

# --context-only stops here: the facts above are the whole product. Callers
# that need them WITHOUT a lint run (hooks/gate.sh at session start) take this
# path, so there is exactly one implementation of the applicability facts.
if [ "$CONTEXT_ONLY" -eq 1 ]; then
  exit 0
fi
echo

# D5 FIX: restore stdout for the lint loop (saved to fd 3 during CONTEXT).
# The CONTEXT block was redirected to /dev/null in JSON mode; the lint loop
# must use real stdout so command substitutions (awk, grep) capture output.
if [ "$FORMAT_JSON" -eq 1 ]; then
  exec 1>&3 3>&-
fi

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
JSON_FINDINGS=""

while IFS= read -r spec; do
  [ -z "$spec" ] && continue
  files=$((files + 1))
  findings="$(awk -v has_registry="$has_registry" -v code_ids="$code_ids" '
    BEGIN { BQ = sprintf("%c", 96) }             # 96 = backtick
    # ── W7: altitude candidates inside a Given/When/Then clause ──────────
    # Only shapes a machine can be sure about. Each distinct token is
    # reported once, at its first occurrence.
    function alt_scan(line, lineno,   k, parts, i, t, base) {
      if (line !~ /\*\*(Given|When|Then|And)\*\*/) return
      k = split(line, parts, BQ)
      for (i = 2; i <= k; i += 2) {              # odd pieces sit inside backticks
        t = parts[i]
        if (t == "" || (t in alt_seen)) continue
        if (t ~ /^sbt / || t ~ /[A-Za-z0-9]\/(compile|test|Test)/) {
          alt_seen[t] = 1
          printf "WARN W7 line %d: build command %c%s%c inside a clause — belongs in ## Implementation Anchors (ALTITUDE)\n", lineno, 39, t, 39
        } else if (t ~ /\.(scala|sbt|smithy|java)$/) {
          alt_seen[t] = 1
          printf "WARN W7 line %d: source file %c%s%c inside a clause — belongs in ## Implementation Anchors (ALTITUDE)\n", lineno, 39, t, 39
        } else if (t ~ /^[a-z][a-z0-9]*(\.[a-z][A-Za-z0-9_]*)+\.[A-Z]/) {
          alt_seen[t] = 1
          printf "WARN W7 line %d: fully-qualified name %c%s%c inside a clause — belongs in ## Implementation Anchors (ALTITUDE)\n", lineno, 39, t, 39
        } else if (code_ids != "") {
          base = t; gsub(/\[.*/, "", base)     # Foo[A] -> Foo
          # a ledger lookup is only meaningful for an identifier-shaped
          # token; without this guard, expression snippets like [m1, m2]
          # reduce to the empty string and match everything
          if (base ~ /^[A-Za-z_][A-Za-z0-9_.]*$/ &&
              index(" " code_ids " ", " " base " ") > 0) {
            alt_seen[t] = 1
            printf "WARN W7 line %d: %c%s%c is in the type inventory but is not a registry concept — confirm it is domain vocabulary here, not a code identifier (ALTITUDE)\n", lineno, 39, t, 39
          }
        }
      }
    }
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
      # negativity may live in the TITLE ("... are never returned ..."), not
      # only in the body — scan both, or F2/W3 misses the commonest phrasing
      tlow = tolower(req_name)
      if (tlow ~ /(^|[^[:alnum:]])only([^[:alnum:]]|$)/ ||
          tlow ~ /(^|[^[:alnum:]])never([^[:alnum:]]|$)/ ||
          tlow ~ /(^|[^[:alnum:]])cannot([^[:alnum:]]|$)/ ||
          tlow ~ /must not/) req_negative = 1
      req_negative_at[n_reqs] = req_negative
      next
    }
    /^### Property:/ {
      flush_req(); flush_prop(); flush_temp()
      prop_name = substr($0, 15); gsub(/^[ \t]+|[ \t]+$/, "", prop_name)
      n_props++; prop_titles[n_props] = prop_name
      prop_line = NR; prop_has_gen = 0; in_po = 0
      next
    }
    /^#### Scenario:/ {
      n_scen++
      s = substr($0, 16); gsub(/^[ \t]+|[ \t]+$/, "", s)
      scen_titles[n_scen] = s
      # falls through: the body block below also counts it for the owning requirement
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
      in_fc = ($0 ~ /^## Formal Contracts/)
      if ($0 ~ /^## Concepts Used/ && tolower($0) ~ /behaviou?ral/) has_concepts = 1
      next
    }
    {
      if (has_registry) alt_scan($0, NR)
      if (req_name != "") {
        if (!req_seen_given && $0 ~ /\*\*Given\*\*/) req_seen_given = 1
        if (!req_seen_given && $0 ~ /(^|[^A-Za-z])(SHALL|MUST)([^A-Za-z]|$)/) req_has_norm = 1
        low = tolower($0)
        if (low ~ /(^|[^[:alnum:]])only([^[:alnum:]]|$)/ ||
            low ~ /(^|[^[:alnum:]])never([^[:alnum:]]|$)/ ||
            low ~ /must not/) { req_negative = 1; req_negative_at[n_reqs] = 1 }
        # W5 targets IMPOSSIBILITY claims specifically — states a type could
        # make unrepresentable — not every requirement whose prose contains
        # "only". A loose net here would produce warnings nobody reads.
        # The normative statement is ACCUMULATED across lines: the phrase
        # routinely wraps ("… an opaque type that cannot be\nconstructed …").
        if (!req_seen_given) req_norm_text[n_reqs] = req_norm_text[n_reqs] " " low
        if ($0 ~ /^#### Scenario:/) req_scenarios++
        if (low ~ /(^|[^[:alnum:]])(valid|fast|reasonable|correct|appropriate)([^[:alnum:]]|$)/)
          printf "WARN W1 line %d: vague word in requirement \"%s\": %s\n", NR, req_name, $0
      }
      if (prop_name != "" && $0 ~ /\*\*Generator strategy\*\*/) prop_has_gen = 1
      if (temp_name != "") {
        if ($0 ~ /\*\*Trigger event\*\*/)  temp_has_trig = 1
        if ($0 ~ /\*\*Response event\*\*/) temp_has_resp = 1
      }
      if (in_fc && $0 !~ /^[ \t]*$/ && $0 !~ /^<!--/ && $0 !~ /^[ \t]*-->/) fc_content++
      if (in_po && $0 ~ /^\|/ && $0 !~ /^\|[ \t:]*-/ && $0 !~ /^\| *Obligation/) {
        po_rows++
        # only a BRIDGE/parity artifact counts: citing the model itself
        # ("OracleKernel") is the formal-contract obligation, not the thing
        # that binds the shipped code to it
        if (tolower($0) ~ /bridge|parity/) bridge_rows++
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
    # F8 (v9): a TYPED source must name something that EXISTS in this spec.
    # "Property: recall-orderng" (typo) resolved to nothing before v9.
    function named_exists(kind, name,   i, low, k) {
      low = tolower(name); gsub(/^[ \t]+|[ \t]+$/, "", low)
      if (length(low) < 4) return 1              # too short to match reliably
      if (kind ~ /^Propert/) {
        for (i = 1; i <= n_props; i++) {
          k = tolower(prop_titles[i])
          if (index(k, low) > 0 || index(low, k) > 0) return 1
        }
        return 0
      }
      if (kind ~ /^Scenario/) {
        for (i = 1; i <= n_scen; i++) {
          k = tolower(scen_titles[i])
          if (index(k, low) > 0 || index(low, k) > 0) return 1
        }
        return 0
      }
      return 1                                   # other kinds have no in-spec index
    }

    # does the (line-joined) normative statement claim a state is impossible?
    function claims_impossible(t,   s) {
      s = t; gsub(/[ \t]+/, " ", s)
      return (s ~ /cannot be (constructed|created|built|expressed|represented)/ ||
              s ~ /unrepresentable/ ||
              s ~ /(must|shall) not be constructible/ ||
              s ~ /impossible to (express|construct|represent)/ ||
              s ~ /never be constructed/)
    }

    # tier 1/2 of the mechanism ladder: the claim is defended by the type
    # system or a smart constructor rather than only by tests (W5, v9)
    function is_strong(enf,   low) {
      low = tolower(enf)
      return (low ~ /type system|type-level|opaque|smart constructor|unrepresentable|compile-negative|assertdoesnotcompile|compileerrors|exhaustiv|sealed/)
    }

    function check_source(row, lineno,   nf, cells, src, enf, i, toks, nt, idx, hit,
                          low, j, key, np, parts, part, kind, nm, p, hitreqs, nhit, strong) {
      nf = split(row, cells, "|")
      if (nf < 4) return                      # not a 4-column obligations row
      src = cells[3]; gsub(/^[ \t]+|[ \t]+$/, "", src)
      enf = (nf >= 5) ? cells[4] : ""
      if (src == "" || src ~ /^<!--/) return
      hit = 0; nhit = 0
      # ordinal references: "Requirement 3", "R3"
      nt = split(src, toks, /[^A-Za-z0-9]+/)
      for (i = 1; i <= nt; i++) {
        idx = 0
        if (toks[i] ~ /^R[0-9]+$/)                                idx = substr(toks[i], 2) + 0
        else if (toks[i] == "Requirement" && toks[i+1] ~ /^[0-9]+$/) idx = toks[i+1] + 0
        if (idx >= 1 && idx <= n_reqs) {
          covered[idx] = 1; hit = 1; ordinal_refs++
          hitreqs[++nhit] = idx
        } else if (idx > n_reqs && idx > 0) {
          printf "FAIL F6 line %d: Source cites Requirement %d but the spec has %d\n", lineno, idx, n_reqs
          hit = 1
        }
      }
      # title reference: the Source quotes an actual requirement title
      low = tolower(src)
      for (j = 1; j <= n_reqs; j++) {
        key = tolower(substr(req_titles[j], 1, 40))
        if (key != "" && index(low, key) > 0) {
          covered[j] = 1; hit = 1; title_refs++
          hitreqs[++nhit] = j
        }
      }
      # ── F8: every typed reference must name something that exists ──────
      np = split(src, parts, / \+ /)
      for (p = 1; p <= np; p++) {
        part = parts[p]; gsub(/^[ \t]+|[ \t]+$/, "", part)
        if (match(part, /^(Property|Properties|Scenario|Scenarios)[ ]*:[ ]*/)) {
          kind = substr(part, 1, RLENGTH); gsub(/[ :]+$/, "", kind)
          nm = substr(part, RLENGTH + 1)
          if (!named_exists(kind, nm)) {
            printf "FAIL F8 line %d: Source cites %s \"%s\" but no such heading exists in this spec\n",
                   lineno, kind, substr(nm, 1, 52)
          }
        }
      }
      # ── W5 data: does this row defend its requirement(s) at tier 1/2? ──
      strong = is_strong(enf)
      if (enf ~ /tier-justified/) strong = 2
      for (i = 1; i <= nhit; i++)
        if (strong > req_strength[hitreqs[i]]) req_strength[hitreqs[i]] = strong
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
      # F10: the structural half of the ALTITUDE rule. Applicability came
      # from the filesystem, so nothing here rests on an assumption about
      # whether a behavioural registry exists.
      if (has_registry && n_reqs > 0 && !has_concepts)
        printf "FAIL F10: a behavioural registry exists (openspec/concepts/) but this spec has no \"## Concepts Used (behavioral)\" section — cite the concepts the requirements touch, or state that they introduce new ones\n"
      if (has_po && po_rows < n_reqs)
        printf "WARN W2: Proof Obligations has %d data row(s) for %d requirement(s)\n", po_rows, n_reqs
      if (has_po)
        for (i = 1; i <= n_reqs; i++)
          if (!covered[i])
            printf "FAIL F7 line %d: requirement \"%s\" is named by NO proof obligation (unenforced)\n", req_lines[i], req_titles[i]
      # W4 (tightened in v9): ANY ordinal reference is positional and fragile,
      # even in a spec that mostly uses titles — a mixed table is not safer.
      # W6 (v10): Ring 6 declared, but nothing binds the model to the code.
      if (fc_content > 2 && has_po && bridge_rows == 0)
        printf "WARN W6: spec declares Formal Contracts (Ring 6) but no obligation names a bridge/mirror artifact — a proof about a model nobody runs says nothing about the shipped code (templates/verified-mirror.md)\n"
      if (ordinal_refs > 0)
        printf "WARN W4: %d obligation Source(s) reference requirements BY ORDINAL — reordering requirements silently re-points them; prefer \"Requirement: <exact title>\"\n", ordinal_refs
      # W5 (v9): a requirement claiming a state is IMPOSSIBLE, but defended
      # only by tests — the top ladder tiers exist for exactly this claim
      # shape. Silenced by "tier-justified: <why>" in the Enforcement cell.
      if (has_po)
        for (i = 1; i <= n_reqs; i++)
          if (claims_impossible(req_norm_text[i]) && covered[i] && req_strength[i] == 0)
            printf "WARN W5 line %d: requirement \"%s\" claims a state is impossible but is enforced only by tests — a type or smart constructor (ladder tier 1–2) can make it unrepresentable; otherwise write \"tier-justified: <why not>\" in the Enforcement cell\n", req_lines[i], req_titles[i]
    }
  ' "$spec")"

  # ── F9: artifact existence (opt-in, post-implementation) ───────────────
  # A code-shaped artifact token — CamelCase ending Spec/Test/Suite/
  # Properties/TypeContract, or a path with an extension — must resolve to a
  # tracked file. Prose artifacts (README, "adversarial review", build
  # commands, spec sections) are legitimate and skipped.
  if [ "$ARTIFACTS" -eq 1 ]; then
    # awk emits "line<TAB>token" per backtick-quoted artifact token
    art_tokens="$(awk '
      BEGIN { q = sprintf("%c", 96) }          # 96 = backtick
      /^## Proof Obligations/ {m=1; next}
      /^## /                  {m=0}
      m && /^\|/ && $0 !~ /^\|[ \t:]*-/ && $0 !~ /^\| *Obligation/ {
        n = split($0, c, "|")
        if (n < 5) next
        cell = c[5]
        k = split(cell, parts, q)
        for (i = 2; i <= k; i += 2)            # odd-indexed pieces are inside backticks
          if (parts[i] != "") printf "%d\t%s\n", NR, parts[i]
      }' "$spec")"
    repo_root="$(git rev-parse --show-toplevel 2>/dev/null || echo .)"
    while IFS="$(printf '\t')" read -r aline tok; do
      [ -z "${tok:-}" ] && continue
      # a token containing whitespace is prose or a build command
      # (`sbt module/compile`), never a file path — skip it
      case "$tok" in *[[:space:]]*) continue ;; esac
      # strip ONE trailing extension so `FooSpec.scala` is recognised as
      # code-shaped (and paths keep their directories intact)
      base="${tok%.*}"
      case "$base" in
        *[A-Z]*Spec|*[A-Z]*Test|*[A-Z]*Suite|*[A-Z]*Properties|*[A-Z]*TypeContract|*/*)
          if [ -z "$(git -C "$repo_root" ls-files -- "*${base}*" 2>/dev/null | head -1)" ]; then
            findings="$findings
FAIL F9 line $aline: artifact '$tok' does not resolve to any tracked file"
          fi
          ;;
      esac
    done <<EOF9
$art_tokens
EOF9
    findings="$(printf '%s\n' "$findings" | grep -v '^$' || true)"
  fi

  if [ -n "$findings" ]; then
    if [ "$FORMAT_JSON" -eq 1 ]; then
      # D5 FIX: collect findings as JSON objects for machine consumption.
      # Stored in JSON_FINDINGS; emitted as a single JSON array at the end.
      while IFS= read -r fline; do
        [ -n "$fline" ] || continue
        # Parse "FAIL F7 line N: ..." or "FAIL F10: ..." or "WARN W2: ..." format
        if [[ "$fline" =~ ^(FAIL|WARN)\ ([FW][0-9]+)([[:space:]]+line\ [0-9]+:[[:space:]]+|[[:space:]]*:[[:space:]]+)(.*)$ ]]; then
          verdict="${BASH_REMATCH[1]}"
          check="${BASH_REMATCH[2]}"
          # Save group 3 before inner regex overwrites BASH_REMATCH
          line_prefix="${BASH_REMATCH[3]}"
          reason="${BASH_REMATCH[4]}"
          # Extract requirement name from reason if present
          req=""
          if [[ "$reason" =~ requirement\ \"([^\"]+)\" ]]; then
            req="${BASH_REMATCH[1]}"
          fi
          # Extract line number if present (for F7/F9 findings with "line N:")
          line_num=""
          if [[ "$line_prefix" =~ line\ ([0-9]+) ]]; then
            line_num="${BASH_REMATCH[1]}"
          fi
          # Extract artifact token from F9 reason if present
          art_token=""
          if [[ "$reason" =~ artifact\ \'([^\']+)\' ]]; then
            art_token="${BASH_REMATCH[1]}"
          fi
          json_obj="$(jq -nc --arg check "$check" --arg verdict "$verdict" --arg req "$req" --arg reason "$reason" \
            --argjson line "${line_num:-0}" --arg artifact "$art_token" \
            '{check: $check, verdict: $verdict, requirement: $req, reason: $reason, line: $line, artifact: $artifact}')"
          JSON_FINDINGS="${JSON_FINDINGS}${json_obj}"$'\n'
        fi
      done <<<"$findings"
    else
      echo "spec-lint: $spec"
      printf '%s\n' "$findings" | sed 's/^/  /'
    fi
    f="$(printf '%s\n' "$findings" | grep -c '^FAIL' || true)"
    w="$(printf '%s\n' "$findings" | grep -c '^WARN' || true)"
    fails=$((fails + f))
    warns=$((warns + w))
  fi
done <<EOF
$specs
EOF

if [ "$FORMAT_JSON" -eq 1 ]; then
  # D5 FIX: emit all collected findings as a single JSON array.
  if [ -n "$JSON_FINDINGS" ]; then
    printf '%s\n' "$JSON_FINDINGS" | jq -cs '.'
  else
    printf '[]\n'
  fi
  if [ "$fails" -gt 0 ]; then
    exit 1
  fi
  exit 0
fi

echo "spec-lint: $files spec file(s), $fails FAIL, $warns WARN"
if [ "$fails" -gt 0 ]; then
  echo "spec-lint: FAILED — F-checks are lint failures; fix the specs and re-run."
  exit 1
fi
exit 0
