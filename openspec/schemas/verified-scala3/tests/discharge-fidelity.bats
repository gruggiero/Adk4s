#!/usr/bin/env bats
#
# Oracle for spec:discharge-fidelity (change: fix-verified-scala3-substratum-review).
#
# Tests for D1 (discharge ignores exit status) and D2 (baseline = HEAD vs
# per-spec baseline causes permanent red indictment).
#
# Written from the spec BEFORE implementation. ORACLE POLARITY: these tests
# are expected to FAIL (red) before the fixes are applied, and PASS (green)
# after.

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  CHAIN_STATE="$SCHEMA/scanner/chain-state.sh"
  LEDGER="$SCHEMA/scanner/ledger.sh"
  GATE="$SCHEMA/hooks/gate.sh"
  CHECKPOINT="$SCHEMA/scanner/checkpoint.sh"
  FX="$BATS_TEST_TMPDIR/repo"
}

# ── fixtures ──────────────────────────────────────────────────────────────

mk_repo() {
  mkdir -p "$FX/openspec/changes/test-change/specs/only"
  cat >"$FX/openspec/changes/test-change/specs/only/spec.md" <<'SPEC'
# Spec: Test

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
  (cd "$FX" && git init -q && git config user.email t@t && git config user.name t \
    && git add -A && git commit -q -m init)
  BASELINE_SHA="$(cd "$FX" && git rev-parse HEAD)"
}

# Helper: append a ledger row with specific exit code
append_row() { # $1=file $2=change $3=spec $4=obligation $5=exit $6=baseline $7=artifact $8=command
  bash "$LEDGER" append \
    --file "$1" --change "$2" --spec "$3" --ring R0 \
    --obligation "$4" --exit "$5" --baseline "$6" \
    --artifact "$7" --command "$8"
}

# Helper: run chain-state and parse the report (stderr suppressed — trace
# messages go to stderr and would corrupt JSON parsing if mixed in).
# Uses bash -c wrapper because bats `run` captures stderr into $output
# regardless of 2>/dev/null on the command line.
run_chain_state() { # $1=baseline
  run bash -c 'OPENSPEC_ROOT="'"$FX"'" bash "'"$CHAIN_STATE"'" \
    --change-dir "'"$FX"'/openspec/changes/test-change" \
    --change "test-change" \
    --baseline "'"$1"'" 2>/dev/null'
}

# ── D1: Discharge requires a green run, not any run ──────────────────────

@test "D1: a failed run (exit 1) does not discharge" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "Test obligation" 1 "$BASELINE_SHA" "tests/test.bats" "bats tests/test.bats"
  run_chain_state "$BASELINE_SHA"
  [ "$status" -eq 0 ] || [ "$status" -eq 1 ]
  discharged="$(echo "$output" | jq '.discharged')"
  [ "$discharged" -eq 0 ]
}

@test "D1: a green run (exit 0) discharges" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "Test obligation" 0 "$BASELINE_SHA" "tests/test.bats" "bats tests/test.bats"
  run_chain_state "$BASELINE_SHA"
  [ "$status" -eq 0 ]
  discharged="$(echo "$output" | jq '.discharged')"
  [ "$discharged" -eq 1 ]
}

@test "D1: both a failed and a green run exist — green wins, discharged" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "Test obligation" 1 "$BASELINE_SHA" "tests/test.bats" "bats tests/test.bats"
  append_row "$LEDGER_FILE" "test-change" "only" "Test obligation" 0 "$BASELINE_SHA" "tests/test.bats" "bats tests/test.bats"
  run_chain_state "$BASELINE_SHA"
  [ "$status" -eq 0 ]
  discharged="$(echo "$output" | jq '.discharged')"
  [ "$discharged" -eq 1 ]
}

@test "D1: only a failed run exists — reason is 'failed', not 'undischarged'" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "Test obligation" 1 "$BASELINE_SHA" "tests/test.bats" "bats tests/test.bats"
  run_chain_state "$BASELINE_SHA"
  # The obligation should have reason "failed", not "undischarged"
  reason="$(echo "$output" | jq -r '.unresolved[0].reasons[0]')"
  [ "$reason" = "failed" ]
}

@test "D1: failed reason-code is distinct from undischarged" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  # Add a second spec with no ledger rows for the undischarged case
  mkdir -p "$FX/openspec/changes/test-change/specs/other"
  cat >"$FX/openspec/changes/test-change/specs/other/spec.md" <<'SPEC'
# Spec: Other

### Requirement: Other req

The system SHALL pass the other test.

#### Scenario: other

**Given** a test
**When** it runs
**Then** it passes

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Other obligation | Requirement: Other req + Scenario: other | bats | tests/other.bats |
SPEC
  (cd "$FX" && git add -A && git commit -q -m "add other spec")
  # "only" has a failed row; "other" has no rows
  append_row "$LEDGER_FILE" "test-change" "only" "Test obligation" 1 "$BASELINE_SHA" "tests/test.bats" "bats tests/test.bats"
  run_chain_state "$BASELINE_SHA"
  # Should have two unresolved: one "failed", one "undischarged"
  reasons="$(echo "$output" | jq -r '.unresolved | map(.reasons[0]) | sort | join(",")')"
  echo "$reasons" | grep "failed"
  echo "$reasons" | grep "undischarged"
}

# ── D2: Per-spec baselines from implementation-progress.md ───────────────

@test "D2: chain-state.sh uses per-spec baseline, not HEAD" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  # Record a green run at the initial baseline
  append_row "$LEDGER_FILE" "test-change" "only" "Test obligation" 0 "$BASELINE_SHA" "tests/test.bats" "bats tests/test.bats"
  # Add an implementation-progress.md with a per-spec baseline
  cat >"$FX/openspec/changes/test-change/implementation-progress.md" <<PROG
# Implementation Progress

## Spec 1/1: only

- **BASELINE SHA**: \`$BASELINE_SHA\`
PROG
  # Commit so HEAD advances past the baseline
  (cd "$FX" && git add -A && git commit -q -m "advance HEAD")
  HEAD_SHA="$(cd "$FX" && git rev-parse HEAD)"
  # Run chain-state with HEAD as the gate baseline — it should use the
  # per-spec baseline from implementation-progress.md. Suppress stderr to
  # avoid trace messages corrupting the JSON output.
  cs_out="$(OPENSPEC_ROOT="$FX" bash "$CHAIN_STATE" \
    --change-dir "$FX/openspec/changes/test-change" \
    --change "test-change" \
    --baseline "$HEAD_SHA" 2>/dev/null)"
  # The obligation should still be discharged (using per-spec baseline)
  discharged="$(echo "$cs_out" | jq '.discharged')"
  [ "$discharged" -eq 1 ]
}

@test "D2: fallback to HEAD when no per-spec baseline recorded" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "Test obligation" 0 "$BASELINE_SHA" "tests/test.bats" "bats tests/test.bats"
  # No implementation-progress.md — should fall back to the passed baseline
  run_chain_state "$BASELINE_SHA"
  discharged="$(echo "$output" | jq '.discharged')"
  [ "$discharged" -eq 1 ]
}

# ── D2: --forgive-unchanged ──────────────────────────────────────────────

@test "D2: --forgive-unchanged forgives unchanged artifacts" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  # Record a row at the initial baseline
  append_row "$LEDGER_FILE" "test-change" "only" "Test obligation" 0 "$BASELINE_SHA" "tests/test.bats" "bats tests/test.bats"
  # Commit so HEAD advances past the baseline
  (cd "$FX" && git commit -q --allow-empty -m "advance HEAD")
  NEW_HEAD="$(cd "$FX" && git rev-parse HEAD)"
  # Read with --forgive-unchanged using NEW_HEAD as the read baseline.
  # The row's baseline (BASELINE_SHA) != read baseline (NEW_HEAD), so the
  # row is stale. --forgive-unchanged should check if the artifact
  # (tests/test.bats) changed between BASELINE_SHA and HEAD — it doesn't
  # exist in git, so git diff --quiet exits 0 (no diff). Row should be forgiven.
  run bash "$LEDGER" read --file "$LEDGER_FILE" --change "test-change" --baseline "$NEW_HEAD" --forgive-unchanged
  [ "$status" -eq 0 ]
  # The row should appear in the output (forgiven)
  echo "$output" | grep "Test obligation"
}

@test "D2: --forgive-unchanged does not forgive changed artifacts" {
  mk_repo
  # Create and commit the artifact first
  mkdir -p "$FX/tests"
  echo "initial" > "$FX/tests/test.bats"
  (cd "$FX" && git add -A && git commit -q -m "initial")
  ARTIFACT_BASELINE="$(cd "$FX" && git rev-parse HEAD)"
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  # Record a row at that baseline
  append_row "$LEDGER_FILE" "test-change" "only" "Test obligation" 0 "$ARTIFACT_BASELINE" "tests/test.bats" "bats tests/test.bats"
  # Now modify the artifact and commit (HEAD advances)
  echo "changed" > "$FX/tests/test.bats"
  (cd "$FX" && git add -A && git commit -q -m "change artifact")
  NEW_HEAD="$(cd "$FX" && git rev-parse HEAD)"
  # Read with --forgive-unchanged using NEW_HEAD as the read baseline.
  # The row's baseline (ARTIFACT_BASELINE) != read baseline (NEW_HEAD), so
  # the row is stale. --forgive-unchanged should check if the artifact
  # changed between ARTIFACT_BASELINE and HEAD — it DID change, so the row
  # should NOT be forgiven.
  run bash "$LEDGER" read --file "$LEDGER_FILE" --change "test-change" --baseline "$NEW_HEAD" --forgive-unchanged
  [ "$status" -eq 0 ]
  # The row should NOT appear (artifact changed, not forgiven)
  ! echo "$output" | grep "Test obligation"
}

@test "D2: without --forgive-unchanged, stale rows are filtered (current behavior)" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "Test obligation" 0 "$BASELINE_SHA" "tests/test.bats" "bats tests/test.bats"
  # Read with a different baseline — row should be filtered as stale
  (cd "$FX" && git commit -q --allow-empty -m "advance")
  OTHER_SHA="$(cd "$FX" && git rev-parse HEAD)"
  run bash "$LEDGER" read --file "$LEDGER_FILE" --change "test-change" --baseline "$OTHER_SHA"
  [ "$status" -eq 0 ]
  # No rows should appear
  local n
  n="$(echo "$output" | grep -c 'Test obligation' || true)"
  [ "$n" -eq 0 ]
}

# ── D2: Gate and checkpoint share forgiveness discipline ─────────────────

@test "D2: ledger.sh accepts --forgive-unchanged flag" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "Test obligation" 0 "$BASELINE_SHA" "tests/test.bats" "bats tests/test.bats"
  # The flag should be accepted without error
  run bash "$LEDGER" read --file "$LEDGER_FILE" --change "test-change" --baseline "$BASELINE_SHA" --forgive-unchanged
  [ "$status" -eq 0 ]
}
