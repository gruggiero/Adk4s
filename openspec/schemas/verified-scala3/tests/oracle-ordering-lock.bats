#!/usr/bin/env bats
#
# Oracle for spec:oracle-ordering-lock (change: add-verified-scala3-control-plane).
#
# Written from the spec and the approved Step 1 typed contract ONLY, before
# gate.sh's tool-call event exists. Tests gate.sh's OWN phase/polarity logic
# via the CHAIN_STATE_OVERRIDE / SPEC_LINT_OVERRIDE seams already established
# in hook-tiers.bats — never re-tests the checks themselves.
#
# The tool-call event is a PRE-EXECUTION gate: it consults oracle phase state
# and a polarity predicate over ledger rows, and blocks only */src/main/**
# edits while the phase is `oracle`. Test edits, change-artifact edits, and
# workflow-tooling edits are ALWAYS allowed — the agent is never blocked from
# producing the evidence that unblocks it.
#
# BOOTSTRAPPING: this is a workflow-self-change. The live gate's verdicts are
# NOT evidence during this change (bats is the evidence). These tests are the
# oracle for the tool-call event; they MUST fail (RED) before implementation.

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  GATE="$SCHEMA/hooks/gate.sh"
  FX="$BATS_TEST_TMPDIR/repo"
  FAKE_CS="$BATS_TEST_TMPDIR/fake-chain-state.sh"
  FAKE_SL="$BATS_TEST_TMPDIR/fake-spec-lint.sh"
  LEDGER="$BATS_TEST_TMPDIR/evidence-ledger.jsonl"
  CHANGE="add-verified-scala3-control-plane"
  SPEC="oracle-ordering-lock"
}

# ── fixtures ──────────────────────────────────────────────────────────────

mk_repo() {
  mkdir -p "$FX/openspec/changes/$CHANGE/specs/$SPEC"
  mkdir -p "$FX/openspec/changes/$CHANGE/specs/other-spec"
  mkdir -p "$FX/adk4s-core/src/main/scala/org/adk4s/core"
  mkdir -p "$FX/adk4s-core/src/test/scala/org/adk4s/core"
  (cd "$FX" && git init -q && git config user.email t@t && git config user.name t)
  # Initial commit so HEAD exists for merge-base checks. Git cannot commit
  # empty directories, so a placeholder file is required.
  printf 'placeholder\n' >"$FX/.gitignore-placeholder"
  (cd "$FX" && git add -A && git commit -q -m "initial" 2>/dev/null || true)
}

# A neutral chain-state stub so the session-start/prompt-submit injection path
# (which runs for unrecognized events before tool-call is implemented) does not
# invoke unrelated stubs. Same pattern as hook-tiers.bats's neutral_chain_state.
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

# Run the tool-call gate. Neutralizes chain-state/spec-lint so the ONLY logic
# under test is the tool-call event handler itself.
run_gate_tool_call() { # $@ = args after --repo $FX
  neutral_chain_state
  neutral_spec_lint
  run env -u CLAUDE_CODE_SESSION_ID \
    CHAIN_STATE_OVERRIDE="$FAKE_CS" \
    SPEC_LINT_OVERRIDE="$FAKE_SL" \
    VERIFIED_SCALA3_SESSION_ID="test-session" \
    "$GATE" --repo "$FX" "$@"
}

# Write a ledger row directly (bypassing ledger.sh run) to set up phase state.
# The tool-call gate reads the ledger to evaluate the polarity predicate.
write_ledger_row() { # $1=exit $2=baseline $3=ring (default R3)
  local row_exit="$1" row_baseline="$2" row_ring="${3:-R3}"
  jq -c -n \
    --argjson v 1 \
    --arg ts "2026-08-13T00:00:00Z" \
    --arg change "$CHANGE" \
    --arg spec "$SPEC" \
    --arg ring "$row_ring" \
    --arg obligation "test-obligation" \
    --arg artifact "tests/oracle-ordering-lock.bats" \
    --arg command "sbt adk4s-core/test" \
    --argjson exit "$row_exit" \
    --arg baseline "$row_baseline" \
    '{v:$v, ts:$ts, change:$change, spec:$spec, ring:$ring,
      obligation:$obligation, artifact:$artifact, command:$command,
      exit:$exit, baseline:$baseline}' >>"$LEDGER"
}

# Write a phase state file directly into the git-dir state directory.
write_phase() { # $1=phase (oracle|implementation|verified)
  local phase="$1"
  local git_dir
  git_dir="$(cd "$FX" && git rev-parse --absolute-git-dir 2>/dev/null)"
  if [ -n "$git_dir" ]; then
    mkdir -p "$git_dir/verified-scala3-gate"
    printf '%s' "$phase" >"$git_dir/verified-scala3-gate/phase-$CHANGE-$SPEC"
  fi
}

# Point the gate at the test ledger via the LEDGER_OVERRIDE environment seam.
# The tool-call gate reads ledger rows to evaluate the polarity predicate.
# If the gate does not support LEDGER_OVERRIDE, it falls back to the default
# ledger path inside the change dir — which the test also populates.
with_ledger() {
  # Also write to the default location the gate would discover
  local default_ledger="$FX/openspec/changes/$CHANGE/evidence-ledger.jsonl"
  if [ -f "$LEDGER" ]; then
    cp "$LEDGER" "$default_ledger"
  fi
}

PROD_PATH="adk4s-core/src/main/scala/org/adk4s/core/Foo.scala"
TEST_PATH="adk4s-core/src/test/scala/org/adk4s/core/FooSpec.scala"
ARTIFACT_PATH="openspec/changes/$CHANGE/implementation-progress.md"
TOOLING_PATH="openspec/schemas/verified-scala3/hooks/gate.sh"

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Implementation edits require the oracle phase to have advanced
# ═════════════════════════════════════════════════════════════════════════

# spec: oracle-ordering-lock — Scenario: implementation edit blocked while phase is oracle
@test "implementation edit blocked while phase is oracle" {
  mk_repo
  write_phase "oracle"
  with_ledger
  run_gate_tool_call --event tool-call --file "$FX/$PROD_PATH" --format text
  assert_status 2 "$status" "a production edit in oracle phase must be blocked (text format = exit 2)"
  assert_contains "$output" "oracle" "the reason must contain the word oracle"
  assert_contains "$output" "$SPEC" "the reason must name the spec"
  assert_contains "$output" "ledger.sh run" "the reason must point to ledger.sh run"
}

# spec: oracle-ordering-lock — Scenario: test edit allowed while phase is oracle
@test "test edit allowed while phase is oracle" {
  mk_repo
  write_phase "oracle"
  with_ledger
  run_gate_tool_call --event tool-call --file "$FX/$TEST_PATH" --format text
  assert_status 0 "$status" "a test edit in oracle phase must be allowed"
  assert_not_contains "$output" "block" "a test edit must not produce a block decision"
}

# spec: oracle-ordering-lock — Scenario: change-artifact edit allowed while phase is oracle
@test "change-artifact edit allowed while phase is oracle" {
  mk_repo
  write_phase "oracle"
  with_ledger
  run_gate_tool_call --event tool-call --file "$FX/$ARTIFACT_PATH" --format text
  assert_status 0 "$status" "a change-artifact edit in oracle phase must be allowed"
  assert_not_contains "$output" "block" "a change-artifact edit must not produce a block decision"
}

# spec: oracle-ordering-lock — Scenario: workflow-tooling edit allowed while phase is oracle
@test "workflow-tooling edit allowed while phase is oracle" {
  mk_repo
  write_phase "oracle"
  with_ledger
  run_gate_tool_call --event tool-call --file "$FX/$TOOLING_PATH" --format text
  assert_status 0 "$status" "a workflow-tooling edit in oracle phase must be allowed"
  assert_not_contains "$output" "block" "a workflow-tooling edit must not produce a block decision"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The oracle phase advances only on a recorded RED run
# ═════════════════════════════════════════════════════════════════════════

# spec: oracle-ordering-lock — Scenario: a recorded RED run advances the phase
@test "a recorded RED run advances the phase" {
  mk_repo
  write_phase "oracle"
  local baseline
  baseline="$(cd "$FX" && git rev-parse HEAD)"
  write_ledger_row 1 "$baseline"
  with_ledger
  run_gate_tool_call --event tool-call --file "$FX/$PROD_PATH" --format text
  assert_status 0 "$status" "a RED run at an ancestor baseline must advance the phase to implementation, allowing the edit"
  # Assert the TRANSITION happened — this is the new behavior that only the
  # tool-call event produces. Without it, the phase file stays "oracle" (RED).
  local git_dir phase_file
  git_dir="$(cd "$FX" && git rev-parse --absolute-git-dir 2>/dev/null)"
  phase_file="$git_dir/verified-scala3-gate/phase-$CHANGE-$SPEC"
  [ -f "$phase_file" ] && [ "$(cat "$phase_file")" = "implementation" ] || {
    printf 'phase must transition to "implementation" after a recorded RED run\n' >&2
    printf 'phase file: %s\n' "$phase_file" >&2
    [ -f "$phase_file" ] && printf 'phase content: "%s"\n' "$(cat "$phase_file")" >&2
    return 1
  }
}

# spec: oracle-ordering-lock — Scenario: a green run does not advance the phase
@test "a green run does not advance the phase" {
  mk_repo
  write_phase "oracle"
  local baseline
  baseline="$(cd "$FX" && git rev-parse HEAD)"
  write_ledger_row 0 "$baseline"
  with_ledger
  run_gate_tool_call --event tool-call --file "$FX/$PROD_PATH" --format text
  assert_status 2 "$status" "a green run must NOT advance the phase from oracle — the edit must still be blocked"
}

# spec: oracle-ordering-lock — Scenario: a row at a non-ancestor baseline does not advance the phase
@test "a row at a non-ancestor baseline does not advance the phase" {
  mk_repo
  write_phase "oracle"
  # Create a commit, then make another commit the HEAD, so the first is NOT an ancestor
  # Actually: use a bogus baseline that is not an ancestor of HEAD
  write_ledger_row 1 "0000000000000000000000000000000000000000"
  with_ledger
  run_gate_tool_call --event tool-call --file "$FX/$PROD_PATH" --format text
  assert_status 2 "$status" "a row at a non-ancestor (stale) baseline must not advance the phase — the edit must be blocked"
}

# spec: oracle-ordering-lock — Scenario: no row does not advance the phase
@test "no row does not advance the phase" {
  mk_repo
  write_phase "oracle"
  # No ledger rows written
  with_ledger
  run_gate_tool_call --event tool-call --file "$FX/$PROD_PATH" --format text
  assert_status 2 "$status" "with no ledger row the phase must remain oracle — the edit must be blocked"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The verified phase requires a GREEN run after implementation
# ═════════════════════════════════════════════════════════════════════════

# spec: oracle-ordering-lock — Scenario: a green run after a red run verifies the phase
@test "a green run after a red run verifies the phase" {
  mk_repo
  write_phase "implementation"
  local baseline
  baseline="$(cd "$FX" && git rev-parse HEAD)"
  write_ledger_row 1 "$baseline"
  write_ledger_row 0 "$baseline"
  with_ledger
  run_gate_tool_call --event tool-call --file "$FX/$PROD_PATH" --format text
  assert_status 0 "$status" "a green run after a red run must allow the edit (phase advances to verified)"
  # Assert the TRANSITION to "verified" happened — new behavior only the
  # tool-call event produces. Without it, the phase file stays "implementation" (RED).
  local git_dir phase_file
  git_dir="$(cd "$FX" && git rev-parse --absolute-git-dir 2>/dev/null)"
  phase_file="$git_dir/verified-scala3-gate/phase-$CHANGE-$SPEC"
  [ -f "$phase_file" ] && [ "$(cat "$phase_file")" = "verified" ] || {
    printf 'phase must transition to "verified" after a green run following a red run\n' >&2
    printf 'phase file: %s\n' "$phase_file" >&2
    [ -f "$phase_file" ] && printf 'phase content: "%s"\n' "$(cat "$phase_file")" >&2
    return 1
  }
}

# spec: oracle-ordering-lock — Scenario: a green run without a prior red run does not verify
@test "a green run without a prior red run does not verify" {
  mk_repo
  write_phase "implementation"
  local baseline
  baseline="$(cd "$FX" && git rev-parse HEAD)"
  # Only a green row, no red row — this is the inversion the gate exists to prevent
  write_ledger_row 0 "$baseline"
  with_ledger
  run_gate_tool_call --event tool-call --file "$FX/$PROD_PATH" --format text
  # In implementation phase, the edit is allowed (implementation phase allows edits);
  # but the phase must NOT transition to verified. The key assertion is that the
  # gate does not claim "verified" status. Since implementation allows edits, this
  # test asserts the edit is allowed but the phase file does not read "verified".
  assert_status 0 "$status" "implementation phase allows the edit, but the phase must not be verified"
  local git_dir
  git_dir="$(cd "$FX" && git rev-parse --absolute-git-dir 2>/dev/null)"
  local phase_file="$git_dir/verified-scala3-gate/phase-$CHANGE-$SPEC"
  [ -f "$phase_file" ] && [ "$(cat "$phase_file")" != "verified" ] || {
    printf 'phase must not be "verified" without a prior red run\n' >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The gate fails open when state is unavailable
# ═════════════════════════════════════════════════════════════════════════

# R8 adversarial review found: a ledger row from a non-R3 ring (e.g. R8)
# could advance the phase without actual test-execution evidence. The typed
# contract specifies .ring == "R3" — only test runs advance the oracle.
@test "R8 fix: a non-R3 ring row does not advance the phase" {
  mk_repo
  write_phase "oracle"
  local baseline
  baseline="$(cd "$FX" && git rev-parse HEAD)"
  # A RED row but with ring "R8" (adversarial review), not "R3" (test run)
  write_ledger_row 1 "$baseline" "R8"
  with_ledger
  run_gate_tool_call --event tool-call --file "$FX/$PROD_PATH" --format text
  assert_status 2 "$status" "a non-R3 ring row must NOT advance the phase — only test-execution evidence (R3) counts"
}

# spec: oracle-ordering-lock — Scenario: no state dir means fail open
@test "no state dir means fail open" {
  # Create a repo where the git-dir state directory cannot be created.
  # We use a repo with .git as a file (worktree-style) pointing to a
  # read-only location, or simply a non-git directory with openspec/.
  local no_git_fx="$BATS_TEST_TMPDIR/no-git-repo"
  mkdir -p "$no_git_fx/openspec/changes/$CHANGE/specs/$SPEC"
  mkdir -p "$no_git_fx/adk4s-core/src/main/scala/org/adk4s/core"
  # No git init — git rev-parse --absolute-git-dir will fail, STATE_DIR stays empty
  neutral_chain_state
  neutral_spec_lint
  run env -u CLAUDE_CODE_SESSION_ID \
    CHAIN_STATE_OVERRIDE="$FAKE_CS" \
    SPEC_LINT_OVERRIDE="$FAKE_SL" \
    VERIFIED_SCALA3_SESSION_ID="test-session" \
    "$GATE" --repo "$no_git_fx" --event tool-call \
    --file "$no_git_fx/$PROD_PATH" --format text
  assert_status 0 "$status" "without a state dir the gate must fail open (allow), not block without a bound"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The VERIFIED_SCALA3_HOOKS escape hatch applies to the tool-call tier
# ═════════════════════════════════════════════════════════════════════════

# spec: oracle-ordering-lock — Scenario: the escape hatch disables the tool-call block
@test "the escape hatch disables the tool-call block" {
  mk_repo
  write_phase "oracle"
  with_ledger
  neutral_chain_state
  neutral_spec_lint
  run env -u CLAUDE_CODE_SESSION_ID \
    CHAIN_STATE_OVERRIDE="$FAKE_CS" \
    SPEC_LINT_OVERRIDE="$FAKE_SL" \
    VERIFIED_SCALA3_HOOKS=off \
    VERIFIED_SCALA3_SESSION_ID="test-session" \
    "$GATE" --repo "$FX" --event tool-call \
    --file "$FX/$PROD_PATH" --format text
  assert_status 0 "$status" "VERIFIED_SCALA3_HOOKS=off must disable the tool-call gate — the edit must be allowed"
  assert_not_contains "$output" "block" "the escape hatch must not produce a block decision"
}
