#!/usr/bin/env bats
#
# Oracle for spec:judgment-ring-provenance (change: add-verified-scala3-control-plane).
#
# Written from the spec and the approved Step 1 typed contract ONLY, before
# the session field, contract update, or checkpoint provenance check exists.
# Tests ledger.sh's session field on R8 rows and checkpoint.sh's provenance
# check (R8 session ≠ implementing session, or explicit limitation).
#
# The fresh-context mandate (schema Step 8): "the review MUST be performed
# by a reviewer with NO implementation context." Same-session is the proxy
# for same-context where the harness exposes session identity.

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  LEDGER="$SCHEMA/scanner/ledger.sh"
  CHECKPOINT="$SCHEMA/scanner/checkpoint.sh"
  FX="$BATS_TEST_TMPDIR/repo"
  CHANGE="test-change"
  SPEC="test-spec"
  BASE="abc1234"
}

# ── fixtures ──────────────────────────────────────────────────────────────

mk_repo() {
  mkdir -p "$FX/openspec/changes/$CHANGE/specs/$SPEC"
  # Copy real ledger.sh, contract, checkpoint.sh, chain-state.sh
  mkdir -p "$FX/openspec/schemas/verified-scala3/scanner"
  cp "$LEDGER" "$FX/openspec/schemas/verified-scala3/scanner/ledger.sh"
  cp "$SCHEMA/scanner/ledger-record-contract.jq" "$FX/openspec/schemas/verified-scala3/scanner/ledger-record-contract.jq"
  cp "$CHECKPOINT" "$FX/openspec/schemas/verified-scala3/scanner/checkpoint.sh"
  cp "$SCHEMA/scanner/chain-state.sh" "$FX/openspec/schemas/verified-scala3/scanner/chain-state.sh"
  printf 'placeholder\n' >"$FX/.gitignore-placeholder"
  (cd "$FX" && git init -q && git config user.email t@t && git config user.name t)
  (cd "$FX" && git add -A && git commit -q -m "initial" 2>/dev/null || true)
  # Create a fake review artifact for D4 checks
  printf 'fake review content\n' >"$FX/openspec/changes/$CHANGE/review.md"
  (cd "$FX" && git add -A && git commit -q -m "review" 2>/dev/null || true)
  BASE="$(cd "$FX" && git rev-parse --short HEAD)"
}

ledger_file() {
  printf '%s/openspec/changes/%s/evidence-ledger.jsonl' "$FX" "$CHANGE"
}

# Append a ledger row with given fields. Uses ledger.sh append.
append_row() { # $1=ring $2=exit $3=obligation $4=artifact $5=command [$6=session]
  local ring="$1" exit_code="$2" obligation="$3" artifact="$4" command="$5" session="${6:-}"
  local args=(append --file "$(ledger_file)" --change "$CHANGE" --spec "$SPEC" \
    --ring "$ring" --obligation "$obligation" --artifact "$artifact" \
    --command "$command" --exit "$exit_code" --baseline "$BASE")
  if [ -n "$session" ]; then
    args+=(--session "$session")
  fi
  "$LEDGER" "${args[@]}" 2>/dev/null
}

# Write a raw JSONL row directly (bypassing ledger.sh validation)
write_raw_row() { # $1=json
  printf '%s\n' "$1" >>"$(ledger_file)"
}

# Run checkpoint report
run_checkpoint() { # $@ = extra args
  local lf
  lf="$(ledger_file)"
  local cs_json
  cs_json='{"change":"test-change","baseline":"abc1234","total":1,"bound":1,"resolved":1,"discharged":1,"unresolved":[],"unmapped_obligations":[]}'
  run "$CHECKPOINT" report --ledger "$lf" --change "$CHANGE" --spec "$SPEC" \
    --baseline "$BASE" --rings "R8" --chain-state-json <(echo "$cs_json") \
    --format json "$@"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Adversarial-review ring rows carry a session provenance
# ═════════════════════════════════════════════════════════════════════════

# spec: judgment-ring-provenance — Scenario: an adversarial-review row carries a session
@test "an adversarial-review row carries a session" {
  mk_repo
  append_row "R8" 0 "adversarial review" "openspec/changes/$CHANGE/review.md" \
    "openspec-adversarial-review" "session-S2"
  local lf
  lf="$(ledger_file)"
  local session_val
  session_val="$(tail -1 "$lf" | jq -r '.session // empty')"
  [ -n "$session_val" ] || {
    printf 'expected session field on R8 row, got empty\n' >&2
    return 1
  }
  [ "$session_val" = "session-S2" ] || {
    printf 'expected session=session-S2, got %s\n' "$session_val" >&2
    return 1
  }
}

# spec: judgment-ring-provenance — Scenario: an adversarial-review row without a session is rejected
@test "an adversarial-review row without a session is rejected" {
  mk_repo
  # Write an R8 row without session via raw JSONL (bypass ledger.sh validation)
  local row
  row="$(jq -c -n \
    --argjson v 1 \
    --arg ts "2026-01-01T00:00:00Z" \
    --arg change "$CHANGE" \
    --arg spec "$SPEC" \
    --arg ring "R8" \
    --arg obligation "adversarial review" \
    --arg artifact "openspec/changes/$CHANGE/review.md" \
    --arg command "openspec-adversarial-review" \
    --argjson exit 0 \
    --arg baseline "$BASE" \
    '{v:$v,ts:$ts,change:$change,spec:$spec,ring:$ring,obligation:$obligation,artifact:$artifact,command:$command,exit:$exit,baseline:$baseline}')"
  # Verify this row does NOT conform to the contract
  printf '%s' "$row" | jq -e -f "$SCHEMA/scanner/ledger-record-contract.jq" >/dev/null 2>&1 && {
    printf 'R8 row without session should be rejected by the contract\n' >&2
    return 1
  }
  # The contract should reject it — verify the error mentions session
  local err
  err="$(printf '%s' "$row" | jq -e -f "$SCHEMA/scanner/ledger-record-contract.jq" 2>&1 || true)"
  case "$err" in
    *session*) ;; # Good — the error names the missing session field
    *) printf 'contract error should mention session, got: %s\n' "$err" >&2; return 1 ;;
  esac
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The checkpoint requires the adversarial-review session to differ
# ═════════════════════════════════════════════════════════════════════════

# spec: judgment-ring-provenance — Scenario: a same-session adversarial review is flagged
@test "a same-session adversarial review is flagged" {
  mk_repo
  # R8 row with session S1 (same as implementing session)
  append_row "R8" 0 "adversarial review" "openspec/changes/$CHANGE/review.md" \
    "openspec-adversarial-review" "session-S1"
  # Run checkpoint with --session S1 (the implementing session)
  run_checkpoint --session "session-S1"
  # The R8 ring should NOT be green (same session = no fresh-context evidence)
  local r8_status
  r8_status="$(printf '%s' "$output" | jq -r '.rings[] | select(.ring=="R8") | .status')"
  [ "$r8_status" != "green" ] || {
    printf 'expected R8 not green (same session), got: %s\n' "$r8_status" >&2
    return 1
  }
}

# spec: judgment-ring-provenance — Scenario: a different-session adversarial review is accepted
@test "a different-session adversarial review is accepted" {
  mk_repo
  # R8 row with session S2 (different from implementing session S1)
  append_row "R8" 0 "adversarial review" "openspec/changes/$CHANGE/review.md" \
    "openspec-adversarial-review" "session-S2"
  # Run checkpoint with --session S1 (the implementing session)
  run_checkpoint --session "session-S1"
  assert_status 0 "$status" "different-session R8 should pass (green + chain clean)"
  local r8_status
  r8_status="$(printf '%s' "$output" | jq -r '.rings[] | select(.ring=="R8") | .status')"
  [ "$r8_status" = "green" ] || {
    printf 'expected R8 green (different session), got: %s\n' "$r8_status" >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Where the harness cannot observe session, the limitation is explicit
# ═════════════════════════════════════════════════════════════════════════

# spec: judgment-ring-provenance — Scenario: an unverified session source is flagged as a limitation
@test "an unverified session source is flagged as a limitation" {
  mk_repo
  # R8 row with PPID fallback session (starts with "ppid-")
  append_row "R8" 0 "adversarial review" "openspec/changes/$CHANGE/review.md" \
    "openspec-adversarial-review" "ppid-12345"
  # Run checkpoint with --session S1
  run_checkpoint --session "session-S1"
  # The R8 ring should have a limitation note about the unverified session
  local r8_status r8_note
  r8_status="$(printf '%s' "$output" | jq -r '.rings[] | select(.ring=="R8") | .status')"
  [ "$r8_status" != "green" ] || {
    printf 'expected R8 not green (unverified session), got green\n' >&2
    return 1
  }
  # Check that the output mentions the unverified session / limitation
  assert_contains "$output" "ppid" "the limitation note should mention the PPID session source"
}

# spec: judgment-ring-provenance — Scenario: a verified session source is not flagged
@test "a verified session source is not flagged" {
  mk_repo
  # R8 row with a verified session (not PPID fallback)
  append_row "R8" 0 "adversarial review" "openspec/changes/$CHANGE/review.md" \
    "openspec-adversarial-review" "session-S2"
  # Run checkpoint with --session S1 (different session)
  run_checkpoint --session "session-S1"
  assert_status 0 "$status" "verified different-session R8 should pass"
  # The output should NOT contain a limitation note about unverified session
  assert_not_contains "$output" "ppid" "a verified session should not trigger a PPID limitation note"
  assert_not_contains "$output" "unverified" "a verified session should not trigger an unverified limitation note"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The adversarial-review report artifact remains resolvable and hashed
# ═════════════════════════════════════════════════════════════════════════

# spec: judgment-ring-provenance — Scenario: an adversarial-review row with a missing artifact is rejected
@test "an adversarial-review row with a missing artifact is rejected" {
  mk_repo
  # R8 row with a non-existent artifact
  append_row "R8" 0 "adversarial review" "openspec/changes/$CHANGE/nonexistent-review.md" \
    "openspec-adversarial-review" "session-S2"
  # Run ledger.sh verify — should fail
  run "$LEDGER" verify --file "$(ledger_file)"
  [ "$status" -ne 0 ] || {
    printf 'expected verify to fail (missing artifact), got exit 0\n' >&2
    return 1
  }
  assert_contains "$output" "artifact" "the failure should mention the artifact"
}

# spec: judgment-ring-provenance — Scenario: an adversarial-review with artifact and different session passes
@test "an adversarial-review with artifact and different session passes" {
  mk_repo
  # R8 row with resolvable artifact and different session
  append_row "R8" 0 "adversarial review" "openspec/changes/$CHANGE/review.md" \
    "openspec-adversarial-review" "session-S2"
  # Run ledger.sh verify — should pass (artifact exists)
  run "$LEDGER" verify --file "$(ledger_file)"
  assert_status 0 "$status" "verify should pass (artifact exists and row conforms)"
  # Run checkpoint with --session S1 (different from R8's session-S2)
  run_checkpoint --session "session-S1"
  assert_status 0 "$status" "checkpoint should pass (artifact + different session)"
  local r8_status
  r8_status="$(printf '%s' "$output" | jq -r '.rings[] | select(.ring=="R8") | .status')"
  [ "$r8_status" = "green" ] || {
    printf 'expected R8 green (artifact + different session), got: %s\n' "$r8_status" >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# R8 adversarial review fixes: fail-closed on empty session
# ═════════════════════════════════════════════════════════════════════════

# R8 fix: ledger.sh must reject R8 rows without --session (fail-closed)
@test "R8 row without --session is rejected by ledger.sh" {
  mk_repo
  # Try to append an R8 row without --session — should fail
  run "$LEDGER" append --file "$(ledger_file)" --change "$CHANGE" --spec "$SPEC" \
    --ring "R8" --obligation "adversarial review" \
    --artifact "openspec/changes/$CHANGE/review.md" \
    --command "openspec-adversarial-review" --exit 0 --baseline "$BASE"
  [ "$status" -ne 0 ] || {
    printf 'expected ledger.sh to reject R8 without --session, got exit 0\n' >&2
    return 1
  }
  assert_contains "$output" "session" "the error should mention the missing session"
}

# R8 fix: checkpoint without --session must NOT report R8 as green
@test "checkpoint without --session reports R8 as unverified" {
  mk_repo
  append_row "R8" 0 "adversarial review" "openspec/changes/$CHANGE/review.md" \
    "openspec-adversarial-review" "session-S2"
  # Run checkpoint WITHOUT --session
  run_checkpoint
  # The R8 ring should NOT be green (unverified — no implementing session to compare)
  local r8_status
  r8_status="$(printf '%s' "$output" | jq -r '.rings[] | select(.ring=="R8") | .status')"
  [ "$r8_status" != "green" ] || {
    printf 'expected R8 not green (no --session to checkpoint), got: %s\n' "$r8_status" >&2
    return 1
  }
  assert_contains "$output" "unverified" "the limitation note should mention unverified"
}
