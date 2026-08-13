#!/usr/bin/env bats
#
# Oracle for spec:evidence-capture (change: fix-verified-scala3-substratum-review).
#
# Tests for D3 (ledger's exit field is agent-supplied) and D6 (re-checkability
# promised, never mechanized).
#
# Written from the spec BEFORE implementation. ORACLE POLARITY: these tests
# are expected to FAIL (red) before the fixes are applied, and PASS (green)
# after.

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  LEDGER="$SCHEMA/scanner/ledger.sh"
  FX="$BATS_TEST_TMPDIR/repo"
}

mk_repo() {
  mkdir -p "$FX/openspec/changes/test-change/specs/only"
  echo "# Spec" > "$FX/openspec/changes/test-change/specs/only/spec.md"
  (cd "$FX" && git init -q && git config user.email t@t && git config user.name t \
    && git add -A && git commit -q -m init)
  BASELINE_SHA="$(cd "$FX" && git rev-parse HEAD)"
}

# Helper: append a ledger row (legacy mode)
append_row() { # $1=file $2=change $3=spec $4=ring $5=obligation $6=exit $7=baseline $8=artifact $9=command
  bash "$LEDGER" append \
    --file "$1" --change "$2" --spec "$3" --ring "$4" \
    --obligation "$5" --exit "$6" --baseline "$7" \
    --artifact "$8" --command "$9"
}

# ── D3: Capture mode observes the exit code ───────────────────────────────

@test "D3: run mode records exit 0 for a passing command" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  run bash "$LEDGER" run \
    --file "$LEDGER_FILE" --change test-change --spec only --ring R0 \
    --obligation "Test obligation" --baseline "$BASELINE_SHA" \
    --artifact "tests/test.bats" -- true
  [ "$status" -eq 0 ]
  # Verify the row has exit: 0
  bash "$LEDGER" read --file "$LEDGER_FILE" --change test-change --baseline "$BASELINE_SHA" | jq -e '.exit == 0'
}

@test "D3: run mode records the real exit code for a failing command" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  run bash "$LEDGER" run \
    --file "$LEDGER_FILE" --change test-change --spec only --ring R0 \
    --obligation "Test obligation" --baseline "$BASELINE_SHA" \
    --artifact "tests/test.bats" -- false
  # run mode should NOT suppress the failure — it records exit 1
  # The script itself may exit 0 (it recorded the failure) or 1 (it propagates)
  # The key check: the ledger row has exit: 1
  bash "$LEDGER" read --file "$LEDGER_FILE" --change test-change --baseline "$BASELINE_SHA" | jq -e '.exit == 1'
}

@test "D3: run mode rejects --exit argument" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  run bash "$LEDGER" run \
    --file "$LEDGER_FILE" --change test-change --spec only --ring R0 \
    --obligation "Test obligation" --exit 0 --baseline "$BASELINE_SHA" \
    --artifact "tests/test.bats" -- true
  [ "$status" -ne 0 ]
  echo "$output" | grep -i "run mode.*--exit\|does not accept.*--exit"
}

# ── D3: Capture records artifact content hash ─────────────────────────────

@test "D3: run mode records sha256 of the artifact" {
  mk_repo
  mkdir -p "$FX/scanner"
  echo "artifact content" > "$FX/scanner/ledger.sh"
  (cd "$FX" && git add -A && git commit -q -m "artifact")
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  bash "$LEDGER" run \
    --file "$LEDGER_FILE" --change test-change --spec only --ring R0 \
    --obligation "Test obligation" --baseline "$BASELINE_SHA" \
    --artifact "scanner/ledger.sh" -- true 2>/dev/null
  # The row should have a sha256 field matching sha256sum
  expected_sha="$(cd "$FX" && sha256sum scanner/ledger.sh | cut -d' ' -f1)"
  bash "$LEDGER" read --file "$LEDGER_FILE" --change test-change --baseline "$BASELINE_SHA" | jq -e ".sha256 == \"$expected_sha\""
}

@test "D3: append mode does not record sha256 (backward compat)" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "R0" "Test obligation" 0 "$BASELINE_SHA" "tests/test.bats" "true"
  # Legacy append rows should NOT have sha256 field
  row="$(bash "$LEDGER" read --file "$LEDGER_FILE" --change test-change --baseline "$BASELINE_SHA")"
  echo "$row" | jq -e '.sha256 == null' 2>/dev/null || echo "$row" | jq -e 'has("sha256") | not'
}

# ── D3: Capture records digest and wallTime ───────────────────────────────

@test "D3: run mode records digest and wallTime" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  bash "$LEDGER" run \
    --file "$LEDGER_FILE" --change test-change --spec only --ring R0 \
    --obligation "Test obligation" --baseline "$BASELINE_SHA" \
    --artifact "tests/test.bats" -- echo hello
  row="$(bash "$LEDGER" read --file "$LEDGER_FILE" --change test-change --baseline "$BASELINE_SHA")"
  echo "$row" | jq -e 'has("digest")'
  echo "$row" | jq -e 'has("wallTime")'
  echo "$row" | jq -e '.wallTime >= 0'
}

# ── D6: Verify replays recorded commands ──────────────────────────────────

@test "D6: verify reports replay matches for a matching row" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "R0" "Test obligation" 0 "$BASELINE_SHA" "tests/test.bats" "true"
  run bash "$LEDGER" verify --file "$LEDGER_FILE" 2>&1
  [ "$status" -eq 0 ]
  echo "$output" | grep -i "match\|pass\|ok"
}

@test "D6: verify reports replay diverges for a wrong exit" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  # Record exit 0 but command is 'false' which exits 1
  append_row "$LEDGER_FILE" "test-change" "only" "R0" "Test obligation" 0 "$BASELINE_SHA" "tests/test.bats" "false"
  run bash "$LEDGER" verify --file "$LEDGER_FILE" 2>&1
  [ "$status" -ne 0 ]
  echo "$output" | grep -i "diverg\|disagree\|mismatch"
}

@test "D6: verify names manual rows as manual/unreplayable" {
  mk_repo
  mkdir -p "$FX/reviews"
  echo "# Report" > "$FX/reviews/r8.md"
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "R8" "Manual review" 0 "$BASELINE_SHA" "reviews/r8.md" "fresh-context Agent review..."
  run bash "$LEDGER" verify --file "$LEDGER_FILE" 2>&1
  # Manual rows should be named, not silently skipped
  echo "$output" | grep -i "manual\|unreplayable"
}

# ── D6: Verify exits non-zero on any divergence ───────────────────────────

@test "D6: verify exits 0 when all rows match" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "R0" "Test obligation 1" 0 "$BASELINE_SHA" "tests/test.bats" "true"
  append_row "$LEDGER_FILE" "test-change" "only" "R0" "Test obligation 2" 0 "$BASELINE_SHA" "tests/test.bats" "true"
  run bash "$LEDGER" verify --file "$LEDGER_FILE" 2>&1
  [ "$status" -eq 0 ]
}

@test "D6: verify exits non-zero when one row diverges" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "R0" "Test obligation 1" 0 "$BASELINE_SHA" "tests/test.bats" "true"
  append_row "$LEDGER_FILE" "test-change" "only" "R0" "Test obligation 2" 0 "$BASELINE_SHA" "tests/test.bats" "false"
  run bash "$LEDGER" verify --file "$LEDGER_FILE" 2>&1
  [ "$status" -ne 0 ]
}

# ── D3: New fields are backward-compatible ────────────────────────────────

@test "D3: legacy rows without new fields are readable" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "R0" "Test obligation" 0 "$BASELINE_SHA" "tests/test.bats" "true"
  run bash "$LEDGER" read --file "$LEDGER_FILE" --change test-change --baseline "$BASELINE_SHA"
  [ "$status" -eq 0 ]
}

@test "D3: mixed legacy and capture rows are readable" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  # Legacy append row
  append_row "$LEDGER_FILE" "test-change" "only" "R0" "Legacy obligation" 0 "$BASELINE_SHA" "tests/test.bats" "true"
  # Capture row (run mode)
  bash "$LEDGER" run \
    --file "$LEDGER_FILE" --change test-change --spec only --ring R0 \
    --obligation "Capture obligation" --baseline "$BASELINE_SHA" \
    --artifact "tests/test.bats" -- true 2>/dev/null
  # Read should return both rows
  run bash "$LEDGER" read --file "$LEDGER_FILE" --change test-change --baseline "$BASELINE_SHA"
  [ "$status" -eq 0 ]
}
