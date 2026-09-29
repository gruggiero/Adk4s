#!/usr/bin/env bats
#
# Implementation-shape suite — tests moved out of the acceptance oracle by
# spec:oracle-independence (change: finish-probatio-replacement).
#
# These tests assert over a tool's SOURCE TEXT — how it is written, not what
# it does. Origin: ../gate-payload.bats.

setup() {
  load ../helpers
  SCHEMA="$(schema_dir)"
}

@test "the harness configuration files register the prompt-submission event" {
  # Testable half of a manual obligation: the CONFIGURATION names the event.
  # Whether the harness actually FIRES it is Ring 8 / the README procedure.
  run cat "$SCHEMA/hooks/adapters/claude.settings.json"
  assert_contains "$output" "UserPromptSubmit" "Claude Code config names the event"
  run cat "$SCHEMA/hooks/adapters/devin.hooks.v1.json"
  assert_contains "$output" "UserPromptSubmit" "Devin config names the event"
  run cat "$SCHEMA/hooks/adapters/pi/verified-scala3-gate.ts"
  assert_contains "$output" "before_agent_start" "pi's per-prompt equivalent event"
}
