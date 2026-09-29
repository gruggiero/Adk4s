#!/usr/bin/env bats
#
# Implementation-shape suite — tests moved out of the acceptance oracle by
# spec:oracle-independence (change: finish-probatio-replacement).
#
# These tests assert over a tool's SOURCE TEXT — how it is written, not what
# it does. Origin: ../harness-install-verification.bats.

setup() {
  load ../helpers
  SCHEMA_DIR="$(schema_dir)"
  ADAPTERS="$SCHEMA_DIR/hooks/adapters"
}

@test "pi adapter has a tool_call handler that shells out to gate.sh" {
  local pi_adapter="$ADAPTERS/pi/verified-scala3-gate.ts"
  [ -f "$pi_adapter" ] || {
    printf 'pi adapter not found: %s\n' "$pi_adapter" >&2
    return 1
  }
  # The adapter must register a tool_call handler
  grep -q 'tool_call' "$pi_adapter" || {
    printf 'pi adapter missing tool_call handler\n' >&2
    return 1
  }
  # The handler must shell out to gate.sh with --event tool-call
  grep -q 'tool-call' "$pi_adapter" || {
    printf 'pi adapter tool_call handler missing --event tool-call\n' >&2
    return 1; }
  # The handler must map a block decision to {block:true,reason}
  grep -q 'block.*true\|block:.*true' "$pi_adapter" || {
    printf 'pi adapter missing block:true mapping\n' >&2
    return 1
  }
}
