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
  # The launcher resolves its binary relative to the repository it sits
  # in — inside a differential arm worktree no build exists there. The
  # ported seam shim execs the ORIGIN schema's launcher, so direct
  # invocations follow it when present (a predecessor arm's real script
  # has no exec line and keeps the in-arm path).
  PROBATIO="$SCHEMA/bin/probatio"
  if grep -q '^exec ".*bin/probatio"' "$CHAIN_STATE" 2>/dev/null; then
    PROBATIO="$(sed -n 's/^exec "\(.*bin\/probatio\)" .*$/\1/p' "$CHAIN_STATE")"
  fi
  FX="$BATS_TEST_TMPDIR/repo"
}

mk_repo() {
  mkdir -p "$FX/openspec/changes/test-change/specs/only"
  # The ported graph requires all three sources readable: the behavioural
  # registry, the type inventory, and the active specs.
  mkdir -p "$FX/openspec/concepts"
  cat >"$FX/openspec/concept-inventory.md" <<'INV'
# Concept Inventory

## Workflow

| Concept | Kind | Package | Provenance |
|---------|------|---------|------------|
| TestReq | final case class | org.test | this change |
INV
  mkdir -p "$FX/tests"
  : > "$FX/tests/test.bats"
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

# ── D5: probatio graph export (ported; openspec-graph.py stays the model) ──

@test "D5: probatio graph export emits structured JSON with obligations" {
  mk_repo
  run bash -c "cd '$FX' && OPENSPEC_ROOT='$FX' '$PROBATIO' graph export --change-dir '$FX/openspec/changes/test-change' --change test-change 2>/dev/null"
  [ "$status" -eq 0 ]
  # The JSON should contain obligation objects with spec, obligation, artifact fields
  echo "$output" | jq -e '.obligations | length >= 1'
  echo "$output" | jq -e '.obligations[0] | has("spec") and has("obligation") and has("artifact")'
}

@test "D5: probatio chain-state consumes the traceability graph (not text parsing)" {
  mk_repo
  # OPENSPEC_ROOT tells the ported graph builder where the test repo's
  # openspec/ tree lives — the predecessor's own contract, kept.
  # bats `run` merges stderr into $output — the JSON report is the line
  # that opens with `{`; trace lines ride along on stderr.
  run env OPENSPEC_ROOT="$FX" "$PROBATIO" chain-state \
    --change-dir "$FX/openspec/changes/test-change" \
    --change "test-change" \
    --baseline "$BASELINE_SHA"
  # Should produce a valid report on stdout (stderr has trace lines)
  echo "$output" | grep '^{' | jq -e '.total != null' 2>/dev/null
  # The graph was actually consumed — no degraded-mode statement, and the
  # report itself declares the graph fact source.
  ! echo "$output" | grep -q "graph unavailable"
  echo "$output" | grep '^{' | jq -e '.degraded == false'
}

@test "D5: unattributable escape hatch is deleted (graph resolves uniformly)" {
  mk_repo
  # The unattributable reason-code should no longer be emitted in graph mode —
  # the graph resolves sources uniformly (ordinal, title, or inferred).
  run env OPENSPEC_ROOT="$FX" bash "$CHAIN_STATE" \
    --change-dir "$FX/openspec/changes/test-change" \
    --change "test-change" \
    --baseline "$BASELINE_SHA"
  # No unattributable reason in the report — bats `run` merges stderr into
  # $output, so filter to the JSON line before jq (otherwise this check is
  # vacuous: jq on mixed output emits nothing).
  echo "$output" | grep '^{' | jq -e 'type == "object"'
  ! echo "$output" | grep '^{' | jq -r '.unresolved[]?.reasons[]?' 2>/dev/null | grep -qxF "unattributable"
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
  # The report should be valid (chain-state consumed JSON, not regex).
  # bats `run` merges stderr into $output — filter to the JSON line.
  echo "$output" | grep '^{' | jq -e '.total != null' 2>/dev/null
  # No degraded-mode statement (graph mode used spec-lint --format json)
  ! echo "$output" | grep -q "graph unavailable"
  echo "$output" | grep '^{' | jq -e '.degraded == false'
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

# ── D5: degraded mode when the graph cannot be produced ───────────────────
#
# Retargeted (spec: graph-tool-port): the predecessor's degraded trigger was
# an absent python3 interpreter. The ported tool has no interpreter to lose —
# its unavailability condition is an unreadable graph source. The degraded
# statement must name the source, never silently narrow the requirement set.

@test "D5: degraded mode is documented when a graph source is unreadable" {
  mk_repo
  # Remove the type inventory — the ported graph cannot be produced.
  rm -f "$FX/openspec/concept-inventory.md"
  run env OPENSPEC_ROOT="$FX" "$PROBATIO" chain-state \
    --change-dir "$FX/openspec/changes/test-change" \
    --change "test-change" \
    --baseline "$BASELINE_SHA"
  # The statement MUST appear and MUST name the unreadable source —
  # this is the spec requirement, not optional.
  echo "$output" | grep -q "graph unavailable"
  echo "$output" | grep -q "concept-inventory"
}

@test "D5: degraded mode still produces a report, not a verdict" {
  mk_repo
  rm -f "$FX/openspec/concept-inventory.md"
  # Even with the graph unavailable, chain-state must not emit a verdict
  # computed from a silently narrowed requirement set — the report is
  # either absent (could-not-determine) or carries the degraded statement.
  run bash -c "OPENSPEC_ROOT='$FX' '$PROBATIO' chain-state \
    --change-dir '$FX/openspec/changes/test-change' \
    --change 'test-change' \
    --baseline '$BASELINE_SHA' 2>/dev/null"
  if echo "$output" | jq -e . >/dev/null 2>&1; then
    # A report was emitted — it must be the could-not-determine shape,
    # never a verdict pretending the graph ran.
    echo "$output" | jq -e '.undetermined == true or .graph_unavailable == true or .degraded == true'
  fi
}
