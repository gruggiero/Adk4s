#!/usr/bin/env bats
#
# Implementation-shape suite — tests moved out of the acceptance oracle by
# spec:oracle-independence (change: finish-probatio-replacement).
#
# These tests assert over a tool's SOURCE TEXT — how it is written, not what
# it does. Origin: ../chain-state.bats.

setup() {
  load ../helpers
  SCHEMA="$(schema_dir)"
  CS="$SCHEMA/scanner/chain-state.sh"
}

# ═════════════════════════════════════════════════════════════════════════
# Manual obligation: no second implementation of reachability/resolution
# ═════════════════════════════════════════════════════════════════════════
# Verified structurally: chain-state.sh must never call `git ls-files` or
# otherwise independently decide artifact existence — that judgment must come
# only from spec-lint's F9 output.

@test "chain-state.sh contains no independent artifact-existence check" {
  [ -f "$CS" ] || skip "chain-state.sh not yet implemented"
  # `git ls-files` deciding what resolves is spec-lint's job alone. Spec-file
  # DISCOVERY (`find ... -name spec.md`) is legitimate structural bookkeeping
  # (enumerating requirement TITLES, not deciding artifact existence) and is
  # explicitly excluded — flagging it would make this guard fail on the
  # tool's own approved design.
  run grep -nE 'git .*ls-files' "$CS"
  [ "$status" -ne 0 ] || {
    printf 'chain-state.sh appears to check artifact existence independently:\n%s\n' "$output" >&2
    return 1
  }
}
