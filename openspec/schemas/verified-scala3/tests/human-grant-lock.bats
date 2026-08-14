#!/usr/bin/env bats
#
# Oracle for spec:human-grant-lock (change: add-verified-scala3-control-plane).
#
# Written from the spec and the approved Step 1 typed contract ONLY, before
# the grant-token / checkpoint-presentation / next-spec-lock logic exists.
# Tests gate.sh's OWN grant state machine via direct state-file manipulation
# — never re-tests checkpoint.sh's own report logic (that's already tested).
#
# The human-grant-lock prevents tacit approval: the agent cannot begin spec
# N+1 until a human grant for spec N exists. The grant is written only on a
# user prompt (prompt-submit) after a checkpoint-presentation record exists.

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  GATE="$SCHEMA/hooks/gate.sh"
  FX="$BATS_TEST_TMPDIR/repo"
  FAKE_CS="$BATS_TEST_TMPDIR/fake-chain-state.sh"
  FAKE_SL="$BATS_TEST_TMPDIR/fake-spec-lint.sh"
  CHANGE="add-verified-scala3-control-plane"
  # Two specs for the N / N+1 test pattern
  SPEC_N="human-grant-lock"
  SPEC_N_PLUS_1="ambient-evidence-capture"
  SESSION="test-session"
  # The gate base64-encodes the session for filename safety (jq @base64).
  # State files must use the encoded form to match what the gate reads.
  ENCODED_SESSION="$(printf '%s' "$SESSION" | jq -Rr '@base64' | tr '+/=' '-_.')"
  # These depend on $CHANGE which is set in setup(), so they must be set here too
  PROGRESS_PATH="openspec/changes/$CHANGE/implementation-progress.md"
  SPEC_N_PLUS_1_FILE="openspec/changes/$CHANGE/specs/$SPEC_N_PLUS_1/spec.md"
}

# ── fixtures ──────────────────────────────────────────────────────────────

mk_repo() {
  mkdir -p "$FX/openspec/changes/$CHANGE/specs/$SPEC_N"
  mkdir -p "$FX/openspec/changes/$CHANGE/specs/$SPEC_N_PLUS_1"
  mkdir -p "$FX/adk4s-core/src/main/scala/org/adk4s/core"
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

# Get the git-dir state directory for the test repo
state_dir() {
  local gd
  gd="$(cd "$FX" && git rev-parse --absolute-git-dir 2>/dev/null)"
  printf '%s/verified-scala3-gate' "$gd"
}

# Write a checkpoint-presentation record for spec N
write_presentation() {
  local sd
  sd="$(state_dir)"
  mkdir -p "$sd"
  printf '%s' "fake-checkpoint-report-hash-$SPEC_N" >"$sd/presentation-$CHANGE-$SPEC_N-$ENCODED_SESSION"
}

# Write a grant token for spec N
write_grant() {
  local sd
  sd="$(state_dir)"
  mkdir -p "$sd"
  printf '%s' "fake-checkpoint-report-hash-$SPEC_N" >"$sd/grant-$CHANGE-$SPEC_N-$ENCODED_SESSION"
}

# Write a checkpoint-output file (as checkpoint.sh would)
write_checkpoint_output() {
  local sd
  sd="$(state_dir)"
  mkdir -p "$sd"
  printf 'fake checkpoint report output for %s\n' "$SPEC_N" >"$sd/checkpoint-output-$CHANGE-$SPEC_N-$ENCODED_SESSION"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Next-spec Step-0 actions require a grant for the prior spec
# ═════════════════════════════════════════════════════════════════════════

# spec: human-grant-lock — Scenario: next-spec start blocked without a grant
@test "next-spec start blocked without a grant" {
  mk_repo
  write_presentation
  # No grant written
  run_gate --event tool-call --file "$FX/$PROGRESS_PATH" --format text
  assert_status 2 "$status" "editing implementation-progress.md without a grant for the prior spec must be blocked"
  assert_contains "$output" "grant" "the reason must contain the word grant"
  assert_contains "$output" "$SPEC_N" "the reason must name the prior spec"
}

# spec: human-grant-lock — Scenario: next-spec start allowed with a grant
@test "next-spec start allowed with a grant" {
  mk_repo
  write_presentation
  write_grant
  run_gate --event tool-call --file "$FX/$PROGRESS_PATH" --format text
  assert_status 0 "$status" "editing implementation-progress.md with a grant for the prior spec must be allowed"
  assert_not_contains "$output" "block" "a granted next-spec start must not produce a block decision"
}

# spec: human-grant-lock — Scenario: a grant for spec N does not authorize spec N+2
@test "a grant for spec N does not authorize spec N+2" {
  mk_repo
  # Grant for spec N exists, but no grant for spec N+1
  write_presentation
  write_grant
  # Also need a presentation for spec N+1 (it was checkpointed)
  local sd
  sd="$(state_dir)"
  printf '%s' "fake-checkpoint-report-hash-$SPEC_N_PLUS_1" >"$sd/presentation-$CHANGE-$SPEC_N_PLUS_1-$ENCODED_SESSION"
  # Now try to edit spec N+2's section — but we only have 2 specs in the fixture.
  # Instead, test the principle: a grant for spec N does not authorize if the
  # target is spec N+2 (i.e., the immediately prior spec N+1 has no grant).
  # We simulate this by making the target spec N+1 (which needs a grant for
  # spec N, which exists) vs a hypothetical spec N+2 (which needs a grant for
  # spec N+1, which doesn't exist).
  # Since we only have 2 specs, we test: grant for SPEC_N exists, but editing
  # a file that requires a grant for SPEC_N_PLUS_1 (which doesn't exist) is blocked.
  # We need a third spec for this. Add one.
  mkdir -p "$FX/openspec/changes/$CHANGE/specs/judgment-ring-provenance"
  local spec_n2_file="openspec/changes/$CHANGE/specs/judgment-ring-provenance/spec.md"
  # Grant for SPEC_N exists, but no grant for SPEC_N_PLUS_1
  run_gate --event tool-call --file "$FX/$spec_n2_file" --format text
  assert_status 2 "$status" "a grant for spec N must not authorize spec N+2 — the immediately prior spec N+1 has no grant"
  assert_contains "$output" "$SPEC_N_PLUS_1" "the reason must name the missing grant's spec (N+1)"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: A grant is recorded only on a user prompt after a checkpoint
# ═════════════════════════════════════════════════════════════════════════

# spec: human-grant-lock — Scenario: a user prompt after a checkpoint writes a grant
@test "a user prompt after a checkpoint writes a grant" {
  mk_repo
  write_presentation
  run_gate --event prompt-submit --format text >/dev/null 2>&1
  local sd
  sd="$(state_dir)"
  [ -f "$sd/grant-$CHANGE-$SPEC_N-$ENCODED_SESSION" ] || {
    printf 'grant token was not written after prompt-submit with a presentation record\n' >&2
    return 1
  }
}

# spec: human-grant-lock — Scenario: a user prompt before any checkpoint writes no grant
@test "a user prompt before any checkpoint writes no grant" {
  mk_repo
  # No presentation record written
  run_gate --event prompt-submit --format text >/dev/null 2>&1
  local sd
  sd="$(state_dir)"
  [ ! -f "$sd/grant-$CHANGE-$SPEC_N-$ENCODED_SESSION" ] || {
    printf 'grant token was written without a presentation record\n' >&2
    return 1
  }
}

# spec: human-grant-lock — Scenario: an assistant turn writes no grant
@test "an assistant turn (non-prompt event) writes no grant" {
  mk_repo
  write_presentation
  # Fire a tool-call event (not prompt-submit)
  run_gate --event tool-call --file "$FX/adk4s-core/src/main/scala/org/adk4s/core/Foo.scala" --format text >/dev/null 2>&1
  local sd
  sd="$(state_dir)"
  [ ! -f "$sd/grant-$CHANGE-$SPEC_N-$ENCODED_SESSION" ] || {
    printf 'grant token was written on a non-prompt event\n' >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The checkpoint presentation is a recorded fact
# ═════════════════════════════════════════════════════════════════════════

# spec: human-grant-lock — Scenario: a checkpoint report writes a presentation record
@test "a checkpoint report writes a presentation record" {
  mk_repo
  write_checkpoint_output
  # Fire any event — the gate should consume the checkpoint-output file and
  # write a presentation record
  run_gate --event tool-call --file "$FX/adk4s-core/src/main/scala/org/adk4s/core/Foo.scala" --format text >/dev/null 2>&1
  local sd
  sd="$(state_dir)"
  [ -f "$sd/presentation-$CHANGE-$SPEC_N-$ENCODED_SESSION" ] || {
    printf 'presentation record was not written after a checkpoint-output file existed\n' >&2
    return 1
  }
  # The checkpoint-output file should be consumed (deleted)
  [ ! -f "$sd/checkpoint-output-$CHANGE-$SPEC_N-$ENCODED_SESSION" ] || {
    printf 'checkpoint-output file was not consumed after presentation recording\n' >&2
    return 1
  }
}

# spec: human-grant-lock — Scenario: no checkpoint report means no grant can be recorded
@test "no checkpoint report means no grant can be recorded" {
  mk_repo
  # No checkpoint-output, no presentation
  run_gate --event prompt-submit --format text >/dev/null 2>&1
  local sd
  sd="$(state_dir)"
  [ ! -f "$sd/grant-$CHANGE-$SPEC_N-$ENCODED_SESSION" ] || {
    printf 'grant was written without any checkpoint report or presentation\n' >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Grants and presentations are scoped to a session
# ═════════════════════════════════════════════════════════════════════════

# spec: human-grant-lock — Scenario: a grant does not leak across sessions
@test "a grant does not leak across sessions" {
  mk_repo
  # S1 has a presentation and a grant
  write_presentation
  write_grant
  # S2 has a presentation (same checkpoint) but NO grant — S1's grant must
  # not satisfy S2's requirement. Without a S2 presentation, the gate has
  # nothing to require a grant for, so the isolation is only meaningful
  # when S2 also has a presentation.
  local sd enc_other
  sd="$(state_dir)"
  enc_other="$(printf 'other-session' | jq -Rr '@base64' | tr '+/=' '-_.')"
  printf '%s' "fake-checkpoint-report-hash-$SPEC_N" >"$sd/presentation-$CHANGE-$SPEC_N-$enc_other"
  # Query with S2 — should block because S2 has a presentation but no grant
  neutral_chain_state
  neutral_spec_lint
  run env -u CLAUDE_CODE_SESSION_ID \
    CHAIN_STATE_OVERRIDE="$FAKE_CS" \
    SPEC_LINT_OVERRIDE="$FAKE_SL" \
    VERIFIED_SCALA3_SESSION_ID="other-session" \
    "$GATE" --repo "$FX" --event tool-call \
    --file "$FX/$PROGRESS_PATH" --format text
  assert_status 2 "$status" "a grant from session S1 must not authorize session S2 — S2 has its own presentation but no grant"
  assert_contains "$output" "grant" "the block must mention the missing grant"
}

# spec: human-grant-lock — Scenario: a new turn clears stale refusal state
@test "a new turn clears stale grant-refusal state" {
  mk_repo
  write_presentation
  # No grant — first tool-call is refused
  run_gate --event tool-call --file "$FX/$PROGRESS_PATH" --format text
  assert_status 2 "$status" "the first attempt must be refused (no grant)"
  # prompt-submit clears refusal state AND writes a grant (presentation exists)
  run_gate --event prompt-submit --format text >/dev/null 2>&1
  # Now the grant exists — next tool-call should be allowed
  run_gate --event tool-call --file "$FX/$PROGRESS_PATH" --format text
  assert_status 0 "$status" "after prompt-submit writes a grant, the next attempt must be allowed"
}
