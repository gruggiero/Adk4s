#!/usr/bin/env bats
#
# Implementation-shape suite — tests moved out of the acceptance oracle by
# spec:oracle-independence (change: finish-probatio-replacement).
#
# These tests assert over a tool's SOURCE TEXT — how it is written, not what
# it does. Origin: ../correctness-invariant.bats.

setup() {
  load ../helpers
  SCHEMA="$(schema_dir)"
  GATE_SH="$SCHEMA/hooks/gate.sh"
}

@test "the gate script header no longer asserts the superseded rule" {
  run cat "$GATE_SH"
  assert_status 0 "$status" "reading hooks/gate.sh"
  assert_not_contains "$output" "no jq" "hooks/gate.sh header"
}
