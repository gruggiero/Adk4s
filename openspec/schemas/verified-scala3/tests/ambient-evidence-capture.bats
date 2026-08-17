#!/usr/bin/env bats
#
# Oracle for spec:ambient-evidence-capture (change: add-verified-scala3-control-plane).
#
# Written from the spec and the approved Step 1 typed contract ONLY, before
# the post-bash event handler exists. Tests gate.sh's ambient capture logic
# via direct invocation — the gate receives --event post-bash with --command
# and --exit, and the test verifies ledger rows are (or are not) appended.
#
# Ambient capture makes recording a side effect of the observation channel
# each harness already fires on tool completion. The absence of rows then
# becomes the machine-visible signal that triggers the completion gate's
# block. The post-bash gate NEVER blocks (post-hoc tier discipline).

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  GATE="$SCHEMA/hooks/gate.sh"
  LEDGER="$SCHEMA/scanner/ledger.sh"
  FX="$BATS_TEST_TMPDIR/repo"
  FAKE_CS="$BATS_TEST_TMPDIR/fake-chain-state.sh"
  FAKE_SL="$BATS_TEST_TMPDIR/fake-spec-lint.sh"
  CHANGE="add-verified-scala3-control-plane"
  SESSION="test-session"
  ENCODED_SESSION="$(printf '%s' "$SESSION" | jq -Rr '@base64' | tr '+/=' '-_.')"
}

# ── fixtures ──────────────────────────────────────────────────────────────

mk_repo() {
  mkdir -p "$FX/openspec/changes/$CHANGE/specs/ambient-evidence-capture"
  mkdir -p "$FX/openspec/schemas/verified-scala3/scanner"
  # Copy the real ledger.sh and contract so rows are validated
  cp "$LEDGER" "$FX/openspec/schemas/verified-scala3/scanner/ledger.sh"
  cp "$SCHEMA/scanner/ledger-record-contract.jq" "$FX/openspec/schemas/verified-scala3/scanner/ledger-record-contract.jq"
  printf 'placeholder\n' >"$FX/.gitignore-placeholder"
  (cd "$FX" && git init -q && git config user.email t@t && git config user.name t)
  (cd "$FX" && git add -A && git commit -q -m "initial" 2>/dev/null || true)
}

neutral_chain_state() {
  cat >"$FAKE_CS" <<'EOF'
#!/usr/bin/env bash
echo '{"change":"none","baseline":"0000000","total":0,"bound":0,"resolved":0,"discharged":0,"unresolved":[],"unmapped_obligations":[]}'
exit 0
EOF
  chmod +x "$FAKE_CS"
}

neutral_spec_lint() {
  cat >"$FAKE_SL" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$FAKE_SL"
}

# Run the gate with neutral stubs. $@ = args after --repo $FX
run_gate() {
  neutral_chain_state
  neutral_spec_lint
  run env -u CLAUDE_CODE_SESSION_ID \
    CHAIN_STATE_OVERRIDE="$FAKE_CS" \
    SPEC_LINT_OVERRIDE="$FAKE_SL" \
    VERIFIED_SCALA3_SESSION_ID="$SESSION" \
    "$GATE" --repo "$FX" "$@"
}

# The ledger file path (conventional location)
ledger_file() {
  printf '%s/openspec/changes/%s/evidence-ledger.jsonl' "$FX" "$CHANGE"
}

# Count rows in the ledger file
ledger_count() {
  local lf
  lf="$(ledger_file)"
  [ -f "$lf" ] || { echo 0; return; }
  wc -l <"$lf" | tr -d ' '
}

# Get the last row's field value (jq)
ledger_field() { # $1=field
  local lf
  lf="$(ledger_file)"
  [ -f "$lf" ] || { echo ""; return; }
  tail -1 "$lf" | jq -r ".$1" 2>/dev/null
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Ring-shaped bash commands produce a ledger row automatically
# ═════════════════════════════════════════════════════════════════════════

# spec: ambient-evidence-capture — Scenario: a matching test command records a green row
@test "a matching test command records a green row" {
  mk_repo
  run_gate --event post-bash --command "sbt adk4s-core/test" --exit 0 --format text
  assert_status 0 "$status" "post-bash must always exit 0"
  [ "$(ledger_count)" -ge 1 ] || {
    printf 'expected at least 1 ledger row, got %s\n' "$(ledger_count)" >&2
    return 1
  }
  [ "$(ledger_field exit)" = "0" ] || {
    printf 'expected exit=0 in the row, got %s\n' "$(ledger_field exit)" >&2
    return 1
  }
  local cmd
  cmd="$(ledger_field command)"
  case "$cmd" in
    *sbt*adk4s-core/test*) ;;
    *) printf 'expected command to contain "sbt adk4s-core/test", got: %s\n' "$cmd" >&2; return 1 ;;
  esac
}

# spec: ambient-evidence-capture — Scenario: a matching test command records a red row
@test "a matching test command records a red row" {
  mk_repo
  run_gate --event post-bash --command "sbt adk4s-core/test" --exit 1 --format text
  assert_status 0 "$status" "post-bash must always exit 0 (even for red rows)"
  [ "$(ledger_count)" -ge 1 ] || {
    printf 'expected at least 1 ledger row, got %s\n' "$(ledger_count)" >&2
    return 1
  }
  [ "$(ledger_field exit)" = "1" ] || {
    printf 'expected exit=1 in the row, got %s\n' "$(ledger_field exit)" >&2
    return 1
  }
}

# spec: ambient-evidence-capture — Scenario: a non-matching command records nothing
@test "a non-matching command records nothing" {
  mk_repo
  run_gate --event post-bash --command "ls -la" --exit 0 --format text
  assert_status 0 "$status" "post-bash must always exit 0"
  [ "$(ledger_count)" = "0" ] || {
    printf 'expected 0 ledger rows for non-matching command, got %s\n' "$(ledger_count)" >&2
    return 1
  }
}

# spec: ambient-evidence-capture — Scenario: an explicit ledger.sh run is not double-recorded
@test "an explicit ledger.sh run is not double-recorded" {
  mk_repo
  # Simulate an explicit ledger.sh run command
  run_gate --event post-bash --command "ledger.sh run --file evidence-ledger.jsonl --change $CHANGE --spec ambient-evidence-capture --ring R0 --obligation test --artifact tests/foo.bats --baseline abc1234 -- sbt test" --exit 0 --format text
  assert_status 0 "$status" "post-bash must always exit 0"
  [ "$(ledger_count)" = "0" ] || {
    printf 'expected 0 ledger rows (de-duplicated), got %s\n' "$(ledger_count)" >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The match table is enumerated and conservative
# ═════════════════════════════════════════════════════════════════════════

# spec: ambient-evidence-capture — Scenario: sbt compile is not a ring shape
@test "sbt compile is not a ring shape" {
  mk_repo
  run_gate --event post-bash --command "sbt adk4s-core/compile" --exit 0 --format text
  assert_status 0 "$status" "post-bash must always exit 0"
  [ "$(ledger_count)" = "0" ] || {
    printf 'expected 0 ledger rows for sbt compile, got %s\n' "$(ledger_count)" >&2
    return 1
  }
}

# spec: ambient-evidence-capture — Scenario: a danger-scan invocation is a ring shape
#
# AMENDED (ambient-capture wiring): this scenario asserted `ring=R8`, which was
# wrong in a way the scenario's own words do not require — the Then clause says
# only "a row is appended for the danger-scan ring", and R8 is the ADVERSARIAL
# REVIEW ring, a judgment a fresh-context agent renders. danger-scan.sh is a
# static scan. Filing it as R8 made every scan assert a review that never
# happened, and R8 additionally carries the fresh-context mandate, so the row
# claimed provenance it could not have. R1 (static analysis) is what the
# command actually is; the scenario's intent — a danger-scan invocation
# produces a row — is unchanged and still asserted.
@test "a danger-scan invocation is a ring shape" {
  mk_repo
  run_gate --event post-bash --command "openspec/schemas/verified-scala3/scanner/danger-scan.sh abc1234" --exit 0 --format text
  assert_status 0 "$status" "post-bash must always exit 0"
  [ "$(ledger_count)" -ge 1 ] || {
    printf 'expected at least 1 ledger row for danger-scan, got %s\n' "$(ledger_count)" >&2
    return 1
  }
  [ "$(ledger_field ring)" = "R1" ] || {
    printf 'expected ring=R1 (static analysis) for danger-scan, got %s\n' "$(ledger_field ring)" >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The ledger format is unchanged
# ═════════════════════════════════════════════════════════════════════════

# spec: ambient-evidence-capture — Scenario: an ambient row decodes with existing readers
@test "an ambient row decodes with existing readers" {
  mk_repo
  run_gate --event post-bash --command "sbt adk4s-core/test" --exit 0 --format text
  assert_status 0 "$status" "post-bash must always exit 0"
  # Verify the row passes the ledger-record-contract.jq
  local lf
  lf="$(ledger_file)"
  [ -f "$lf" ] || { printf 'no ledger file produced\n' >&2; return 1; }
  # The row must conform to the contract
  tail -1 "$lf" | jq -e -f "$SCHEMA/scanner/ledger-record-contract.jq" >/dev/null 2>&1 || {
    printf 'ambient row does not conform to ledger-record-contract.jq\n' >&2
    tail -1 "$lf" >&2
    return 1
  }
}

# spec: ambient-evidence-capture — Scenario: a legacy fixture decodes unchanged
@test "a legacy fixture decodes unchanged" {
  mk_repo
  # Write a legacy-format row (no sha256/digest/wallTime — the original append format)
  local lf
  lf="$(ledger_file)"
  mkdir -p "$(dirname "$lf")"
  local legacy_row
  legacy_row="$(jq -c -n \
    --argjson v 1 \
    --arg ts "2026-01-01T00:00:00Z" \
    --arg change "$CHANGE" \
    --arg spec "ambient-evidence-capture" \
    --arg ring "R0" \
    --arg obligation "legacy test" \
    --arg artifact "tests/legacy.bats" \
    --arg command "sbt test" \
    --argjson exit 0 \
    --arg baseline "abc1234" \
    '{v:$v,ts:$ts,change:$change,spec:$spec,ring:$ring,obligation:$obligation,artifact:$artifact,command:$command,exit:$exit,baseline:$baseline}')"
  printf '%s\n' "$legacy_row" >"$lf"
  # Now run an ambient capture — the new row must not break decoding of the old one
  run_gate --event post-bash --command "sbt adk4s-core/test" --exit 0 --format text
  assert_status 0 "$status" "post-bash must always exit 0"
  # Both rows must conform to the contract — verify each line individually
  local count ok_count
  count="$(wc -l <"$lf" | tr -d ' ')"
  ok_count=0
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    if printf '%s' "$line" | jq -e -f "$SCHEMA/scanner/ledger-record-contract.jq" >/dev/null 2>&1; then
      ok_count=$((ok_count + 1))
    fi
  done <"$lf"
  [ "$count" = "$ok_count" ] || {
    printf 'expected %s rows to conform, got %s\n' "$count" "$ok_count" >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The post-bash gate never blocks
# ═════════════════════════════════════════════════════════════════════════

# spec: ambient-evidence-capture — Scenario: a failed command does not block the post-bash gate
@test "a failed command does not block the post-bash gate" {
  mk_repo
  run_gate --event post-bash --command "sbt adk4s-core/test" --exit 1 --format text
  assert_status 0 "$status" "post-bash must exit 0 even when the command exited 1"
  assert_not_contains "$output" "block" "post-bash must never emit a block decision"
}
