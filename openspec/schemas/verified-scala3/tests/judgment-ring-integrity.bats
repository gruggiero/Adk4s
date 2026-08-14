#!/usr/bin/env bats
#
# Oracle for spec:judgment-ring-integrity (change: fix-verified-scala3-substratum-review).
#
# Tests for D4 (judgment rings recorded as prose degrade re-checkability).
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

# Helper: append a ledger row
append_row() { # $1=file $2=change $3=spec $4=ring $5=obligation $6=exit $7=baseline $8=artifact $9=command
  local session_args=()
  if [ "$4" = "R8" ]; then
    session_args=(--session "test-reviewer-session")
  fi
  bash "$LEDGER" append \
    --file "$1" --change "$2" --spec "$3" --ring "$4" \
    --obligation "$5" --exit "$6" --baseline "$7" \
    --artifact "$8" --command "$9" "${session_args[@]}"
}

# ── D4: Manual ring rows must name a resolvable report artifact ──────────

@test "D4: a manual row with a resolvable artifact passes verify" {
  mk_repo
  mkdir -p "$FX/reviews"
  echo "# Ring 8 Report" > "$FX/reviews/r8.md"
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "R8" "Manual review" 0 "$BASELINE_SHA" "reviews/r8.md" "fresh-context Agent review"
  run bash "$LEDGER" verify --file "$LEDGER_FILE"
  [ "$status" -eq 0 ]
}

@test "D4: a manual row with a missing artifact fails verify" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "R8" "Manual review" 0 "$BASELINE_SHA" "reviews/missing.md" "fresh-context Agent review"
  run bash "$LEDGER" verify --file "$LEDGER_FILE"
  [ "$status" -ne 0 ]
  echo "$output" | grep -i "does not resolve\|missing\|not found"
}

@test "D4: a manual row without an artifact field fails verify" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  # We need to create a row without an artifact field. Since ledger.sh append
  # requires --artifact, we'll write the JSONL directly.
  cat > "$LEDGER_FILE" <<JSONL
{"v":1,"ts":"2026-08-12T16:00:00Z","change":"test-change","spec":"only","ring":"R8","obligation":"Manual review","artifact":"","command":"fresh-context Agent review","exit":0,"baseline":"$BASELINE_SHA","session":"test-reviewer-session"}
JSONL
  run bash "$LEDGER" verify --file "$LEDGER_FILE"
  [ "$status" -ne 0 ]
  echo "$output" | grep -i "artifact"
}

# ── D4: Runnable commands must agree with their recorded exit ────────────

@test "D4: grep no-match disagreement is flagged" {
  mk_repo
  # Create a file that does NOT contain the string we grep for
  echo "hello world" > "$FX/somefile.txt"
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  # Record exit 0, but grep will exit 1 (no match)
  append_row "$LEDGER_FILE" "test-change" "only" "R0" "Test obligation" 0 "$BASELINE_SHA" "somefile.txt" "grep -qF 'nonexistent_string' somefile.txt"
  run bash "$LEDGER" verify --file "$LEDGER_FILE" 2>&1
  [ "$status" -ne 0 ]
  echo "$output" | grep -i "disagree\|mismatch"
}

@test "D4: a matching command/exit pair is not flagged" {
  mk_repo
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  # 'true' always exits 0
  append_row "$LEDGER_FILE" "test-change" "only" "R0" "Test obligation" 0 "$BASELINE_SHA" "tests/test.bats" "true"
  run bash "$LEDGER" verify --file "$LEDGER_FILE" 2>&1
  [ "$status" -eq 0 ]
}

@test "D4: a non-runnable command (prose) is not flagged as disagreement" {
  mk_repo
  mkdir -p "$FX/reviews"
  echo "# Report" > "$FX/reviews/r8.md"
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  # Prose command — not runnable, should not be flagged as disagreement
  append_row "$LEDGER_FILE" "test-change" "only" "R8" "Manual review" 0 "$BASELINE_SHA" "reviews/r8.md" "fresh-context Agent subagent review (spec+diff only) + reproduction..."
  run bash "$LEDGER" verify --file "$LEDGER_FILE" 2>&1
  # Should pass (artifact exists, command is prose/not runnable)
  [ "$status" -eq 0 ]
}

# ── D4: Disagreement detection is part of ledger.sh verify ───────────────

@test "D4: verify subcommand exists and reports disagreement" {
  mk_repo
  echo "hello world" > "$FX/somefile.txt"
  LEDGER_FILE="$FX/openspec/changes/test-change/evidence-ledger.jsonl"
  append_row "$LEDGER_FILE" "test-change" "only" "R0" "Test obligation" 0 "$BASELINE_SHA" "somefile.txt" "grep -qF 'nonexistent_string' somefile.txt"
  run bash "$LEDGER" verify --file "$LEDGER_FILE" 2>&1
  [ "$status" -ne 0 ]
  echo "$output" | grep -i "disagree"
}
