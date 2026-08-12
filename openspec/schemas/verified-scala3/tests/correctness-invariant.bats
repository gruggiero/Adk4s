#!/usr/bin/env bats
#
# Oracle for spec:correctness-invariant (change: add-correctness-substratum).
#
# Written from the spec and the approved Step 1 text ONLY, before any
# implementation exists. Tests written after an implementation tend to mirror
# it; these are an independent oracle derived from the specification.
#
# SCOPE NOTE — this spec ships TEXT. Its requirements about what the definition
# MEANS (a change failing a clause is not correct) are enforced here as
# assertions that the definition SAYS SO. The behavioural counterpart — a tool
# that computes the three clauses and refuses to report satisfaction — is
# spec:chain-state, three specs later. Recorded so the coverage table is not
# read as claiming more than these tests prove.

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  ROOT="$(repo_root)"
  SCHEMA_YAML="$SCHEMA/schema.yaml"
  SPEC_TEMPLATE="$SCHEMA/templates/spec.md"
  HOOKS_README="$SCHEMA/hooks/README.md"
  GATE_SH="$SCHEMA/hooks/gate.sh"
}

# ─────────────────────────────────────────────────────────────────────────
# Requirement: Correctness is defined by an unbroken evidence chain
# ─────────────────────────────────────────────────────────────────────────

# spec: correctness-invariant — Scenario: all three conditions hold
@test "the definition names all three clauses and requires their conjunction" {
  run cat "$SCHEMA_YAML"
  assert_status 0 "$status" "reading schema.yaml"
  assert_contains "$output" "BOUND" "schema.yaml"
  assert_contains "$output" "RESOLVED" "schema.yaml"
  assert_contains "$output" "DISCHARGED" "schema.yaml"
  # The conjunction is what makes it a chain: any one clause failing breaks it.
  assert_contains "$output" "iff" "schema.yaml"
}

# NOTE — the three clause tests below match the CLAUSE-TABLE LINE, not loose
# substrings. The first polarity run exposed why: a version asserting
# `"F7"` and `"named by"` anywhere in the file PASSED before implementation,
# because both already occur in the v8/v9 changelog entries. It was proving
# nothing. Anchoring on `^<clause> ... <mechanism>` ties each assertion to the
# new definition block and to nothing else.

# spec: correctness-invariant — Scenario: an obligation names an artifact that does not exist
@test "the definition binds the resolved clause to artifact existence" {
  run grep -E '^[[:space:]]*resolved[[:space:]]+.*F9' "$SCHEMA_YAML"
  assert_status 0 "$status" "the resolved clause line must name its mechanism (F9)"
  assert_contains "$output" "tracked file" "resolved clause line"
}

# spec: correctness-invariant — Scenario: an artifact exists but was never run in this session
@test "the definition binds the discharged clause to an observed run in this session" {
  run grep -E '^[[:space:]]*discharged[[:space:]]+.*ledger' "$SCHEMA_YAML"
  assert_status 0 "$status" "the discharged clause line must name the evidence ledger"
  run cat "$SCHEMA_YAML"
  assert_contains "$output" "observed to run green in the current session" "schema.yaml"
  assert_contains "$output" "Obtained, not recalled" "schema.yaml"
}

@test "the definition binds the bound clause to obligation reachability" {
  run grep -E '^[[:space:]]*bound[[:space:]]+.*F7' "$SCHEMA_YAML"
  assert_status 0 "$status" "the bound clause line must name its mechanism (F7)"
  assert_contains "$output" "named by" "bound clause line"
}

@test "the definition states that rings are how evidence is obtained, not the definition itself" {
  run cat "$SCHEMA_YAML"
  assert_contains "$output" "not themselves the definition" "schema.yaml"
}

# ─────────────────────────────────────────────────────────────────────────
# Requirement: A verdict not obtained in the session is not a verdict
# ─────────────────────────────────────────────────────────────────────────

@test "the operative rule is stated" {
  run cat "$SCHEMA_YAML"
  assert_contains "$output" "NEVER LET A CLAIM OUTRUN ITS EVIDENCE" "schema.yaml"
  assert_contains "$output" "is not a verdict" "schema.yaml"
}

# spec: correctness-invariant — Scenario: applicability asserted without looking
@test "each of the four disguising phrasings is named explicitly" {
  run cat "$SCHEMA_YAML"
  # Named individually: a general instruction to "verify" did not prevent the
  # v11 N/A defect, which is why the spec requires the phrasings by name.
  assert_contains "$output" '"N/A"' "schema.yaml"
  assert_contains "$output" '"passes"' "schema.yaml"
  assert_contains "$output" '"not applicable"' "schema.yaml"
  assert_contains "$output" '"already handled"' "schema.yaml"
}

# spec: correctness-invariant — Scenario: applicability asserted without looking
@test "the applicability corollary states that applicability is read, never inferred" {
  run cat "$SCHEMA_YAML"
  assert_contains "$output" "APPLICABILITY IS A FACT" "schema.yaml"
  assert_contains "$output" "never inferred" "schema.yaml"
}

# spec: correctness-invariant — Scenario: outcome carried over from a previous session
@test "the operative rule is carried into the spec template" {
  run cat "$SPEC_TEMPLATE"
  assert_status 0 "$status" "reading templates/spec.md"
  assert_contains "$output" "claim outrun its evidence" "templates/spec.md"
  assert_contains "$output" "bound" "templates/spec.md"
  assert_contains "$output" "resolved" "templates/spec.md"
  assert_contains "$output" "discharged" "templates/spec.md"
}

# ─────────────────────────────────────────────────────────────────────────
# Requirement: The prerequisite set replaces the zero-dependency rule
# ─────────────────────────────────────────────────────────────────────────

# spec: correctness-invariant — Scenario: no document asserts the superseded rule
# spec: correctness-invariant — Property: No workflow document asserts the superseded dependency rule
@test "PROPERTY no tracked workflow document asserts the superseded dependency rule" {
  # Generator strategy (enumerated): every tracked *.md, *.sh, *.yaml/*.yml and
  # docs/*.html under the schema directory, discovered at test time so a new
  # file is covered automatically.
  #
  # WIDENED at Ring 8 on two axes, because the original was unable to catch its
  # own motivating case:
  #  - DOMAIN: *.md and *.sh only, which excluded schema.yaml and docs/*.html.
  #    The worst offender was docs/09-tooling.html, stating the rule AS A RULE.
  #  - MATCHER: three literal jq spellings, unbackticked. The baseline text read
  #    "no `jq`" WITH backticks and never matched. Now keyed on assertion SHAPE
  #    ("dependency-free", "bash and git only", "no JSON processor",
  #    backtick-tolerant jq) — the spec Scenario's own wording included.
  #
  # Edge case: a document may QUOTE the old rule if it marks it superseded
  # within two lines. Mentioning jq in a permitted context (the prerequisite
  # table) is not a prohibition and is not matched.
  local offenders="" f n=0
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    n=$((n + 1))
    local hits
    hits="$(superseded_rule_hits "$ROOT/$f")"
    [ -z "$hits" ] || offenders="$offenders$hits"$'\n'
  done < <(schema_docs_and_scripts)

  # A domain that parsed to nothing FAILS rather than passing vacuously.
  [ "$n" -ge 20 ] || {
    printf 'only %s files enumerated; the domain did not resolve\n' "$n" >&2
    return 1
  }
  assert_empty_list "$offenders" "these documents still assert the superseded dependency rule:"
}

# spec: correctness-invariant — Scenario: no document asserts the superseded rule
@test "PROPERTY the superseded-rule detector can actually fail" {
  # Polarity guard for the detector itself. The original matcher passed on text
  # it was written to catch; nothing proved it could fire. This runs it against
  # the exact baseline phrasing that escaped it.
  local tmp="$BATS_TEST_TMPDIR/old-portability.md"
  printf '## Portability\n\n`gate.sh` is bash + git only — no JVM, no network, no `jq` — the same rule as\nthe gate checks.\n' >"$tmp"
  local hits
  hits="$(superseded_rule_hits "$tmp")"
  [ -n "$hits" ] || {
    printf 'the detector did not fire on the baseline phrasing it exists to catch\n' >&2
    return 1
  }
}

# spec: correctness-invariant — Scenario: the prerequisite set is discoverable
@test "the hook documentation lists the declared prerequisite set" {
  run cat "$HOOKS_README"
  assert_status 0 "$status" "reading hooks/README.md"
  for tool in bash git jq shellcheck bats shfmt; do
    assert_contains "$output" "$tool" "hooks/README.md prerequisite set"
  done
}

# spec: correctness-invariant — Property: Every declared prerequisite is justified by a named consumer
@test "PROPERTY every declared prerequisite names the ring or function requiring it" {
  # Generator strategy (enumerated): rows are read from the table at test time
  # and selected STRUCTURALLY — every data row between the header separator and
  # the next non-table line.
  #
  # FIXED at Ring 8: the first version listed the six tool names in its awk
  # selector, contradicting this very strategy line. A seventh prerequisite
  # selected ZERO rows and the property passed, so the declared failure mode
  # ("adding a prerequisite without a justification fails") was unreachable.
  # Structural selection puts any new row in the domain by construction.
  local unjustified="" rows tool why words n=0
  rows="$(prereq_rows "$HOOKS_README")"
  while IFS="$(printf '\t')" read -r tool why; do
    [ -n "$tool" ] || continue
    n=$((n + 1))
    words="$(printf '%s' "$why" | wc -w)"
    # Non-empty is not enough: the requirement demands the ring or function
    # that requires it. Word count is a mechanical proxy for "names something"
    # versus "yes" — a stated limitation, not a semantic check.
    if [ -z "$why" ] || [ "$words" -lt 3 ]; then
      unjustified="$unjustified$tool (cell: '"'"'${why:-<empty>}'"'"')"$'"'"'\n'"'"'
    fi
  done <<EOF
$rows
EOF

  # A table that parsed to nothing FAILS rather than passing vacuously — the
  # silent-empty-scan shape this workflow has shipped twice.
  [ "$n" -ge 7 ] || {
    printf '"'"'only %s prerequisite rows parsed — empty or partial scan, not a pass\n'"'"' "$n" >&2
    return 1
  }
  assert_empty_list "$unjustified" "these prerequisites have no named consumer:"
}

# spec: correctness-invariant — Scenario: the prerequisite set is discoverable
@test "PROPERTY the prerequisite check can actually fail" {
  # Polarity guard against a COMMITTED fixture carrying the exact defects the
  # spec'"'"'s Generator strategy names: a seventh tool (outside the original
  # hard-coded domain), an empty cell, and a one-word cell.
  local fixture="$SCHEMA/tests/fixtures/prereq-table-bad.md"
  [ -f "$fixture" ] || {
    printf '"'"'fixture missing: %s\n'"'"' "$fixture" >&2
    return 1
  }
  local rows tool why bad="" n=0
  rows="$(prereq_rows "$fixture")"
  while IFS="$(printf '\t')" read -r tool why; do
    [ -n "$tool" ] || continue
    n=$((n + 1))
    if [ -z "$why" ] || [ "$(printf '%s' "$why" | wc -w)" -lt 3 ]; then
      bad="$bad$tool"$'"'"'\n'"'"'
    fi
  done <<EOF
$rows
EOF
  # The seventh tool must be IN the domain — that is the regression this guards.
  assert_contains "$rows" "yq" "fixture rows (a 7th tool must be selected, not skipped)"
  [ "$n" -eq 4 ] || {
    printf '"'"'expected 4 fixture rows, selected %s\n'"'"' "$n" >&2
    return 1
  }
  local expect
  for expect in yq podman curl; do
    assert_contains "$bad" "$expect" "rows the property must reject"
  done
}

@test "the gate script header no longer asserts the superseded rule" {
  run cat "$GATE_SH"
  assert_status 0 "$status" "reading hooks/gate.sh"
  assert_not_contains "$output" "no jq" "hooks/gate.sh header"
}

@test "the retained reasoning survives the rule change" {
  # The spec replaces the MECHANISM, not the reasoning. Losing the rationale
  # would make the prerequisite set look arbitrary to the next reader.
  run cat "$HOOKS_README"
  assert_contains "$output" "check that only runs on one machine" "hooks/README.md"
}

# ─────────────────────────────────────────────────────────────────────────
# Requirement: A superseded constraint is never inherited without retest
# ─────────────────────────────────────────────────────────────────────────

# spec: correctness-invariant — Scenario: a recorded limitation is re-tested and found obsolete
@test "the re-establishment rule is stated in the workflow definition" {
  run cat "$SCHEMA_YAML"
  assert_contains "$output" "RE-ESTABLISHED BEFORE IT IS RELIED UPON" "schema.yaml"
  assert_contains "$output" "re-tests it rather than inheriting it" "schema.yaml"
}

# spec: correctness-invariant — Scenario: a recorded limitation carries no date or mechanism
@test "the rule requires a date and an establishing mechanism" {
  run cat "$SCHEMA_YAML"
  assert_contains "$output" "carries the date and the mechanism" "schema.yaml"
}

# spec: correctness-invariant — Scenario: a recorded limitation carries no date or mechanism
@test "the concept inventory's consistency check records its date and mechanism" {
  # GREEN-BY-DESIGN: this was corrected before the baseline, so it passes now
  # and must keep passing. Its pre-implementation pass IS its evidential value.
  run cat "$ROOT/openspec/concept-inventory.md"
  assert_status 0 "$status" "reading concept-inventory.md"
  assert_contains "$output" "Last verified:" "concept-inventory.md"
  assert_contains "$output" "SEMANTIC scanner" "concept-inventory.md"
}

# spec: correctness-invariant — Scenario: a recorded limitation carries no date or mechanism
# spec: correctness-invariant — Scenario: a recorded limitation is re-tested and found obsolete
@test "PROPERTY every re-established record in BOTH living documents names a date and a mechanism" {
  # Generator strategy (enumerated): every re-establishment marker
  # ("re-established", "superseding the earlier", "Last verified") in each of
  # the two PROJECT-scoped living documents, discovered at test time.
  #
  # ADDED at Ring 8. Obligation #6 reads "scenario test over the living
  # documentS' limitation ENTRIES"; it was discharged by two substring checks
  # in ONE file. capability-profile.md — the other living document, edited by
  # the same commit — was unchecked, and was carrying two records that commit
  # had invalidated and not replaced.
  local doc offending="" total=0
  for doc in concept-inventory.md capability-profile.md; do
    local path="$ROOT/openspec/$doc"
    [ -f "$path" ] || {
      printf 'living document missing: %s\n' "$doc" >&2
      return 1
    }
    local markers
    markers="$(grep -ciE 're-established|superseding the earlier|last verified' "$path" || true)"
    total=$((total + markers))
    # Each document must carry at least one dated re-establishment record;
    # a living document with none has never been verified, which is not a pass.
    [ "${markers:-0}" -gt 0 ] || {
      printf '%s carries no re-establishment record at all\n' "$doc" >&2
      return 1
    }
    local bad
    bad="$(unsourced_limitation_records "$path")"
    [ -z "$bad" ] || offending="$offending$doc: $bad"$'\n'
  done
  [ "$total" -ge 2 ] || {
    printf 'only %s markers found across both documents — scan did not resolve\n' "$total" >&2
    return 1
  }
  assert_empty_list "$offending" "these re-established records lack a date or a mechanism:"
}

# spec: correctness-invariant — Scenario: a recorded limitation carries no date or mechanism
@test "PROPERTY the limitation-record check can actually fail" {
  # Polarity guard: a record that supersedes an earlier one but names neither
  # a date nor a mechanism must be reported.
  local tmp="$BATS_TEST_TMPDIR/bad-living-doc.md"
  printf '# Doc\n\nThe scanner returns no results, superseding the earlier note.\nNo date, no tool named.\n' >"$tmp"
  local bad
  bad="$(unsourced_limitation_records "$tmp")"
  [ -n "$bad" ] || {
    printf 'the check did not fire on a record with neither date nor mechanism\n' >&2
    return 1
  }
}

# ─────────────────────────────────────────────────────────────────────────
# Requirement: Correctness is defined by an unbroken evidence chain (loadability)
# ─────────────────────────────────────────────────────────────────────────

@test "the workflow definition still loads after the text changes" {
  local chg
  chg="$(first_active_change)" || skip "no active change to load"
  run bash -c "cd '$ROOT' && openspec status --change '$chg' 2>&1"
  assert_status 0 "$status" "openspec must still load the schema"
  assert_contains "$output" "verified-scala3" "openspec status output"
}

@test "the schema version is 12" {
  run awk -F': *' '/^version:/ {print $2; exit}' "$SCHEMA_YAML"
  assert_status 0 "$status" "reading schema version"
  [ "$output" = "12" ] || {
    printf 'expected schema version 12, got: %s\n' "$output" >&2
    return 1
  }
}

@test "the changelog records the v12 entry" {
  run head -40 "$SCHEMA_YAML"
  assert_contains "$output" "12 —" "schema.yaml changelog"
}

# ─────────────────────────────────────────────────────────────────────────
# REACHABILITY — added at Ring 8, which found a FAIL these presence tests
# could not see: the definition sat in the schema `description`, every presence
# test passed, and the CLI does NOT emit `description`. Text nobody receives is
# documentation, not a substratum.
#
# A fresh-context Ring 8 pass then found the first version of these tests was
# ALSO too weak twice over: it probed only `apply` — the one instruction that
# had been fixed — while the requirement says EVERY ARTIFACT instruction; and
# it hard-coded the change name, so the suite would go permanently red once
# that change was archived. Both are corrected below: the artifact list is
# enumerated from schema.yaml, and the change is discovered at test time.
# ─────────────────────────────────────────────────────────────────────────

# Enumerated from schema.yaml at test time, NOT listed here: a new artifact
# added without the prelude must fail this property, which a hard-coded list
# could not detect.
artifact_ids() {
  awk '
    /^artifacts:/        { in_a = 1; next }
    in_a && /^[a-z_-]+:/ { in_a = 0 }
    in_a && /^  - id: /  { print $3 }
  ' "$SCHEMA_YAML"
}

# spec: correctness-invariant — Scenario: all three conditions hold
# spec: correctness-invariant — Scenario: applicability asserted without looking
# spec: correctness-invariant — Scenario: a recorded limitation is re-tested and found obsolete
@test "REACHABILITY PROPERTY every artifact instruction carries the invariant and the retest rule" {
  # Generator strategy (enumerated): every artifact id declared in schema.yaml,
  # read at test time. Edge case: an artifact added later with no prelude.
  local chg missing="" ids n=0
  chg="$(first_active_change)" || skip "no active change to render instructions for"
  ids="$(artifact_ids)"
  [ -n "$ids" ] || {
    printf 'no artifact ids parsed from schema.yaml — empty scan, not a pass\n' >&2
    return 1
  }
  local a out
  while IFS= read -r a; do
    [ -n "$a" ] || continue
    n=$((n + 1))
    out="$(cd "$ROOT" && openspec instructions "$a" --change "$chg" 2>/dev/null)"
    case "$out" in
      *"NEVER LET A CLAIM OUTRUN ITS EVIDENCE"*) ;;
      *) missing="$missing$a (invariant)"$'\n' ;;
    esac
    case "$out" in
      *"RE-ESTABLISHED BEFORE IT IS RELIED UPON"*) ;;
      *) missing="$missing$a (retest rule)"$'\n' ;;
    esac
  done <<EOF
$ids
EOF
  [ "$n" -ge 8 ] || {
    printf 'only %s artifacts enumerated; expected at least 8\n' "$n" >&2
    return 1
  }
  assert_empty_list "$missing" "these artifact instructions do not carry the required text:"
}

# spec: correctness-invariant — Scenario: all three conditions hold
@test "REACHABILITY all three clauses reach the agent, not just the slogan" {
  local chg
  chg="$(first_active_change)" || skip "no active change to render instructions for"
  run bash -c "cd '$ROOT' && openspec instructions proposal --change '$chg' 2>/dev/null"
  assert_status 0 "$status" "openspec instructions must succeed"
  assert_contains "$output" "BOUND" "emitted artifact instruction"
  assert_contains "$output" "RESOLVED" "emitted artifact instruction"
  assert_contains "$output" "DISCHARGED" "emitted artifact instruction"
}

# spec: correctness-invariant — Scenario: applicability asserted without looking
@test "REACHABILITY the applicability corollary reaches the agent" {
  local chg
  chg="$(first_active_change)" || skip "no active change to render instructions for"
  run bash -c "cd '$ROOT' && openspec instructions proposal --change '$chg' 2>/dev/null"
  assert_contains "$output" "never inferred" "emitted artifact instruction"
}

# spec: correctness-invariant — Scenario: outcome carried over from a previous session
@test "REACHABILITY the four disguising phrasings reach the agent" {
  local chg
  chg="$(first_active_change)" || skip "no active change to render instructions for"
  run bash -c "cd '$ROOT' && openspec instructions proposal --change '$chg' 2>/dev/null"
  assert_contains "$output" '"N/A"' "emitted artifact instruction"
  assert_contains "$output" '"already handled"' "emitted artifact instruction"
}

# spec: correctness-invariant — Scenario: all three conditions hold
@test "REACHABILITY the apply instruction also carries the invariant" {
  local chg
  chg="$(first_active_change)" || skip "no active change to render instructions for"
  run bash -c "cd '$ROOT' && openspec instructions apply --change '$chg' 2>/dev/null"
  assert_status 0 "$status" "openspec instructions apply must succeed"
  assert_contains "$output" "NEVER LET A CLAIM OUTRUN ITS EVIDENCE" "emitted apply instruction"
}
