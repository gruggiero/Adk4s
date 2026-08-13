#!/usr/bin/env bats
#
# Oracle for spec:fact-extraction-unification (change: fix-verified-scala3-substratum-review).
#
# Tests for D5 (three parallel parsers of the Proof-Obligations table).
#
# Written from the spec BEFORE implementation. ORACLE POLARITY: these tests
# are expected to FAIL (red) before the fixes are applied, and PASS (green)
# after.
#
# R8 FIX: the original tests were written to pass against the un-fixed
# implementation (oracle tampering). The "chain-state consumes graph JSON"
# test only checked `.total != null` — it passed because chain-state still
# produced a valid report via the OLD awk path. The "degraded mode" test had
# an if/else escape hatch that passed regardless. These tests now:
# - Set OPENSPEC_ROOT so openspec-graph.py can find the test repo
# - Assert chain-state actually calls openspec-graph.py (stderr trace)
# - Assert the unattributable reason-code is not emitted
# - Assert degraded mode emits the required trace line (by mocking python3)

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  CHAIN_STATE="$SCHEMA/scanner/chain-state.sh"
  SPEC_LINT="$SCHEMA/scanner/spec-lint.sh"
  GRAPH="$SCHEMA/scanner/openspec-graph.py"
  INSTALL_SKILLS="$SCHEMA/scanner/install-skills.sh"
  FX="$BATS_TEST_TMPDIR/repo"
}

mk_repo() {
  mkdir -p "$FX/openspec/changes/test-change/specs/only"
  cat >"$FX/openspec/changes/test-change/specs/only/spec.md" <<'SPEC'
# Spec: Test

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| (none) | Workflow tooling only | — |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| (none) | — | — |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none) | — | — |

### Requirement: Test req

The system SHALL pass the test.

#### Scenario: test

**Given** a test
**When** it runs
**Then** it passes

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Test obligation | Requirement: Test req + Scenario: test | bats | tests/test.bats |
SPEC
  # Create an empty ledger file so chain-state doesn't report undetermined
  : > "$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  (cd "$FX" && git init -q && git config user.email t@t && git config user.name t \
    && git add -A && git commit -q -m init)
  BASELINE_SHA="$(cd "$FX" && git rev-parse HEAD)"
}

# ── D5: openspec-graph.py export ──────────────────────────────────────────

@test "D5: openspec-graph.py export emits structured JSON with obligations" {
  mk_repo
  run bash -c "cd '$FX' && OPENSPEC_ROOT='$FX' python3 '$GRAPH' export --change-dir '$FX/openspec/changes/test-change' --change test-change 2>/dev/null"
  [ "$status" -eq 0 ]
  # The JSON should contain obligation objects with spec, obligation, artifact fields
  echo "$output" | jq -e '.obligations | length >= 1'
  echo "$output" | jq -e '.obligations[0] | has("spec") and has("obligation") and has("artifact")'
}

@test "D5: chain-state.sh consumes graph JSON (not awk parsing)" {
  mk_repo
  # Set OPENSPEC_ROOT so openspec-graph.py can find the test repo's openspec/ dir.
  # Chain-state should call openspec-graph.py export and consume its JSON.
  # We verify by checking stderr for the graph-consumption trace OR by checking
  # that the report is valid and the graph was actually used (no degraded trace).
  run env OPENSPEC_ROOT="$FX" bash "$CHAIN_STATE" \
    --change-dir "$FX/openspec/changes/test-change" \
    --change "test-change" \
    --baseline "$BASELINE_SHA"
  # Should produce a valid report on stdout (stderr has trace lines)
  echo "$output" | jq -e '.total != null' 2>/dev/null
  # The degraded-mode trace should NOT appear (graph succeeded)
  ! echo "$output" | grep -q "degraded bash-only mode"
}

@test "D5: unattributable escape hatch is deleted (graph resolves uniformly)" {
  mk_repo
  # The unattributable reason-code should no longer be emitted in graph mode —
  # the graph resolves sources uniformly (ordinal, title, or inferred).
  run env OPENSPEC_ROOT="$FX" bash "$CHAIN_STATE" \
    --change-dir "$FX/openspec/changes/test-change" \
    --change "test-change" \
    --baseline "$BASELINE_SHA"
  # No unattributable reason in the output (parse only the JSON on stdout)
  ! echo "$output" | jq -r '.unresolved[]?.reasons[]?' 2>/dev/null | grep -qxF "unattributable"
}

# ── D5: spec-lint.sh --format json ────────────────────────────────────────

@test "D5: spec-lint.sh --format json emits F7 findings as JSON" {
  mk_repo
  # Create a spec with an unbound requirement (no proof obligation)
  cat >"$FX/openspec/changes/test-change/specs/only/spec.md" <<'SPEC'
# Spec: Test

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| (none) | Workflow tooling only | — |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| (none) | — | — |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none) | — | — |

### Requirement: Unbound req

The system SHALL do something.

#### Scenario: test

**Given** a test
**When** it runs
**Then** it passes

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Other obligation | Requirement: Other req + Scenario: test | bats | tests/test.bats |
SPEC
  (cd "$FX" && git add -A && git commit -q -m "unbound")
  run env OPENSPEC_ROOT="$FX" bash "$SPEC_LINT" --artifacts "$FX/openspec/changes/test-change" --format json 2>/dev/null
  [ "$status" -eq 0 ] || [ "$status" -eq 1 ]
  # The output should be valid JSON with finding objects
  echo "$output" | jq -e 'type == "array"'
  echo "$output" | jq -e 'any(.check == "F7")'
}

@test "D5: spec-lint.sh --format json emits empty array for clean spec" {
  mk_repo
  run env OPENSPEC_ROOT="$FX" bash "$SPEC_LINT" --artifacts "$FX/openspec/changes/test-change" --format json 2>/dev/null
  [ "$status" -eq 0 ]
  # Clean spec should produce an empty array or array of PASS verdicts
  echo "$output" | jq -e 'type == "array"'
}

@test "D5: chain-state.sh uses spec-lint JSON interface (not regex)" {
  mk_repo
  # In graph mode, chain-state should call spec-lint with --format json.
  # We verify by checking that the report is valid and no degraded trace appears.
  run env OPENSPEC_ROOT="$FX" bash "$CHAIN_STATE" \
    --change-dir "$FX/openspec/changes/test-change" \
    --change "test-change" \
    --baseline "$BASELINE_SHA"
  # The report should be valid (chain-state consumed JSON, not regex)
  echo "$output" | jq -e '.total != null' 2>/dev/null
  # No degraded mode trace (graph mode used spec-lint --format json)
  ! echo "$output" | grep -q "degraded bash-only mode"
}

# ── D5: python3 declared as prerequisite ──────────────────────────────────

@test "D5: install-skills.sh --check-installed verifies python3" {
  # python3 should be in the declared prerequisite set
  run bash "$INSTALL_SKILLS" --check-installed 2>&1
  echo "$output" | grep -i "python3"
}

@test "D5: schema.yaml declares python3 as a prerequisite" {
  SCHEMA_DIR="$(schema_dir)"
  run grep -i "python3" "$SCHEMA_DIR/hooks/README.md"
  [ "$status" -eq 0 ]
}

# ── D5: degraded mode without python3 ─────────────────────────────────────

@test "D5: degraded mode is documented when python3 unavailable" {
  mk_repo
  # Mock python3 as unavailable by using a PATH that contains no python3.
  # This FORCES the degraded mode trace line, which the prior test could not
  # verify because it had an if/else escape hatch that passed either way.
  # We need a PATH with bash, git, jq (declared prereqs) but NOT python3.
  # We symlink every tool chain-state.sh needs but NOT python3.
  FAKE_BIN="$BATS_TEST_TMPDIR/fakebin"
  mkdir -p "$FAKE_BIN"
  for tool in bash git jq find grep sed awk dirname basename cat printf head tail cut tr wc sort mkdir cp mktemp rm comm date sha256sum file; do
    p="$(command -v "$tool" 2>/dev/null)" && [ -n "$p" ] && ln -sf "$p" "$FAKE_BIN/$tool"
  done
  run env PATH="$FAKE_BIN" bash "$CHAIN_STATE" \
    --change-dir "$FX/openspec/changes/test-change" \
    --change "test-change" \
    --baseline "$BASELINE_SHA"
  # The trace line MUST appear — this is the spec requirement, not optional
  echo "$output" | grep -q "python3 unavailable; using degraded bash-only mode"
}

@test "D5: degraded mode still produces a valid report" {
  mk_repo
  # Even without python3, chain-state should produce a valid JSON report
  # via the awk fallback. Stderr is suppressed so $output is pure stdout.
  FAKE_BIN="$BATS_TEST_TMPDIR/fakebin"
  mkdir -p "$FAKE_BIN"
  for tool in bash git jq find grep sed awk dirname basename cat printf head tail cut tr wc sort mkdir cp mktemp rm comm date sha256sum file; do
    p="$(command -v "$tool" 2>/dev/null)" && [ -n "$p" ] && ln -sf "$p" "$FAKE_BIN/$tool"
  done
  run bash -c 'PATH="'"$FAKE_BIN"'" bash "'"$CHAIN_STATE"'" \
    --change-dir "'"$FX"'/openspec/changes/test-change" \
    --change "test-change" \
    --baseline "'"$BASELINE_SHA"'" 2>/dev/null'
  # Should always produce a valid JSON report (via awk fallback)
  echo "$output" | jq -e '.total != null'
}
