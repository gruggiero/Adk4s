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
@test "PROPERTY no tracked workflow document asserts that a JSON processor is forbidden" {
  # Generator strategy (enumerated): every tracked *.md and *.sh under the
  # schema directory, discovered at test time so a new file is covered
  # automatically. Edge case: a file mentioning jq in a PERMITTED context —
  # the prerequisite table itself — must not be flagged, so the match is on
  # the prohibition phrasing, not on the tool name.
  local offenders=""
  local f
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    if grep -qiE 'no[[:space:]]+jq|without[[:space:]]+jq|never[[:space:]]+use[[:space:]]+jq' "$ROOT/$f" 2>/dev/null; then
      offenders="$offenders$f"$'\n'
    fi
  done < <(schema_docs_and_scripts)
  assert_empty_list "$offenders" "these documents still assert the superseded no-jq rule:"
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
  # Generator strategy (enumerated): the prerequisite rows are READ from the
  # table at test time rather than hard-coded, so adding a prerequisite with
  # no justification fails this property. Edge case: an empty justification
  # cell must fail, not pass as present-but-blank.
  local unjustified=""
  local row tool why
  while IFS= read -r row; do
    tool="$(printf '%s' "$row" | awk -F'|' '{gsub(/[ `]/,"",$2); print $2}')"
    why="$(printf '%s' "$row" | awk -F'|' '{gsub(/^[ \t]+|[ \t]+$/,"",$3); print $3}')"
    [ -n "$tool" ] || continue
    case "$tool" in Prerequisite|---*) continue ;; esac
    if [ -z "$why" ]; then
      unjustified="$unjustified$tool"$'\n'
    fi
  done < <(awk '/^\| *`?(bash|git|jq|shellcheck|bats|shfmt)/' "$HOOKS_README" 2>/dev/null)

  # A table with no parsed rows FAILS rather than passing vacuously — the
  # silent-empty-scan shape this workflow has shipped twice.
  local rows
  rows="$(awk '/^\| *`?(bash|git|jq|shellcheck|bats|shfmt)/' "$HOOKS_README" 2>/dev/null | grep -c . || true)"
  [ "${rows:-0}" -gt 0 ] || {
    printf 'no prerequisite rows parsed from hooks/README.md — empty scan, not a pass\n' >&2
    return 1
  }
  assert_empty_list "$unjustified" "these prerequisites have no named consumer:"
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

# ─────────────────────────────────────────────────────────────────────────
# Requirement: Correctness is defined by an unbroken evidence chain (loadability)
# ─────────────────────────────────────────────────────────────────────────

@test "the workflow definition still loads after the text changes" {
  run bash -c "cd '$ROOT' && openspec status --change add-correctness-substratum 2>&1"
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
# REACHABILITY — added at Ring 8 (spec 1), which found a FAIL the tests above
# could not see. The definition was present in the schema `description` and
# every presence test passed, but the CLI does NOT emit `description` to the
# agent: `openspec instructions apply` returned ZERO matches for the operative
# rule. Text nobody receives is documentation, not a substratum.
#
# These tests assert the definition REACHES an agent, which is what the
# requirement actually says. Presence in a file is necessary and not
# sufficient, and every test above only checked presence.
# ─────────────────────────────────────────────────────────────────────────

# spec: correctness-invariant — Scenario: all three conditions hold
@test "REACHABILITY the operative rule reaches the agent via the apply instruction" {
  run bash -c "cd '$ROOT' && openspec instructions apply --change add-correctness-substratum 2>/dev/null"
  assert_status 0 "$status" "openspec instructions apply must succeed"
  assert_contains "$output" "NEVER LET A CLAIM OUTRUN ITS EVIDENCE" "emitted apply instruction"
}

# spec: correctness-invariant — Scenario: all three conditions hold
@test "REACHABILITY all three clauses reach the agent, not just the slogan" {
  run bash -c "cd '$ROOT' && openspec instructions apply --change add-correctness-substratum 2>/dev/null"
  assert_contains "$output" "BOUND" "emitted apply instruction"
  assert_contains "$output" "RESOLVED" "emitted apply instruction"
  assert_contains "$output" "DISCHARGED" "emitted apply instruction"
}

# spec: correctness-invariant — Scenario: applicability asserted without looking
@test "REACHABILITY the applicability corollary reaches the agent" {
  run bash -c "cd '$ROOT' && openspec instructions apply --change add-correctness-substratum 2>/dev/null"
  assert_contains "$output" "never inferred" "emitted apply instruction"
}

# spec: correctness-invariant — Scenario: outcome carried over from a previous session
@test "REACHABILITY the four disguising phrasings reach the agent" {
  run bash -c "cd '$ROOT' && openspec instructions apply --change add-correctness-substratum 2>/dev/null"
  assert_contains "$output" '"N/A"' "emitted apply instruction"
  assert_contains "$output" '"already handled"' "emitted apply instruction"
}
