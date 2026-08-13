#!/usr/bin/env bats
#
# Oracle for spec:workflow-hygiene (change: fix-verified-scala3-substratum-review).
#
# Tests for D7 (dangling reference in drift detector remediation) and D8
# (hygiene: dead code, cwd parse, heartbeat ordering, invariant banner
# duplication, payload cap).
#
# Written from the spec BEFORE implementation. ORACLE POLARITY: these tests
# are expected to FAIL (red) before the fixes are applied, and PASS (green)
# after.

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  SPEC_LINT="$SCHEMA/scanner/spec-lint.sh"
  LEDGER="$SCHEMA/scanner/ledger.sh"
  GATE="$SCHEMA/hooks/gate.sh"
  SCHEMA_YAML="$SCHEMA/schema.yaml"
}

# ── D7: drift message references the correct script ──────────────────────

@test "D7: spec-lint.sh drift message references scanner/install-skills.sh, not sync-skills.sh" {
  # The INSTRUCTION DRIFT remediation message must reference the script
  # that actually exists (scanner/install-skills.sh), not the dangling
  # reference (verified-scala3/sync-skills.sh).
  # grep returns exit 1 when 0 matches found — so we check the output count
  run grep -c 'sync-skills\.sh' "$SPEC_LINT"
  [ "$output" -eq 0 ]
  run grep -c 'install-skills\.sh' "$SPEC_LINT"
  [ "$output" -ge 1 ]
}

@test "D7: every tool-name in scanner messages resolves to a tracked file" {
  # Extract tool-name references (paths containing .sh) from scanner
  # messages and verify each resolves to a tracked file.
  local root schema rel
  root="$(repo_root)"
  schema="$(schema_dir)"
  rel="${schema#"$root"/}"

  # Find all .sh references in scanner scripts' echo/printf messages
  local refs
  refs="$(grep -rohE '[a-zA-Z0-9_/.-]+\.sh' "$SCHEMA/scanner/"*.sh | sort -u)"

  # Each referenced .sh must either be a tracked file or a well-known
  # external tool (jq, git, etc. don't end in .sh so won't match)
  local ref
  local dangling=""
  while IFS= read -r ref; do
    # Skip bare filenames that are standard tools (not paths)
    case "$ref" in
      */*) ;;  # path-like — check it
      *) continue ;;  # bare name — skip
    esac
    # Check if it resolves relative to schema dir or repo root
    if [ -f "$schema/$ref" ] || [ -f "$root/$ref" ]; then
      : # resolves
    else
      # Check if it's a tracked file (might be referenced as a relative path)
      if ! (cd "$root" && git ls-files --error-unmatch "$ref" >/dev/null 2>&1); then
        # Check relative to schema dir
        local basename
        basename="$(basename "$ref")"
        if ! (cd "$root" && git ls-files "$rel/**/$basename" | grep -q .); then
          dangling="$dangling $ref"
        fi
      fi
    fi
  done <<<"$refs"
  [ -z "$dangling" ] || { echo "dangling references:$dangling"; false; }
}

# ── D8: dead duplicate case arm removed ───────────────────────────────────

@test "D8: ledger.sh has no dead duplicate update|delete|rewrite|edit case arm in subcommand dispatch" {
  # The pre-parse check at the top of ledger.sh already handles
  # update|delete|rewrite|edit. The duplicate case arm at the bottom of
  # the subcommand dispatch is dead code.
  # Count occurrences of the case arm pattern.
  local count
  count="$(grep -c 'update | delete | rewrite | edit)' "$LEDGER")"
  # Should be exactly 1 (the pre-parse check), not 2 (pre-parse + dead duplicate)
  [ "$count" -eq 1 ]
}

# ── D8: gate.sh parses cwd with jq, not sed ──────────────────────────────

@test "D8: gate.sh extracts cwd from hook JSON using jq, not sed" {
  # The cwd extraction from the hook JSON payload must use jq (the
  # declared prerequisite), not a sed regex over JSON.
  # Find the cwd extraction line.
  run grep -n 'cwd' "$GATE"
  [ "$status" -eq 0 ]
  # The line that extracts cwd must use jq, not sed
  # Look for sed-based cwd extraction (the bug)
  run grep -E 'sed.*cwd|cwd.*sed' "$GATE"
  [ "$status" -eq 1 ]
  # Verify jq is used for cwd extraction
  run grep -E 'jq.*cwd|cwd.*jq' "$GATE"
  # If jq is not directly on the cwd line, check that the payload is
  # parsed with jq somewhere before cwd is used
  if [ "$status" -ne 0 ]; then
    # At minimum, the payload must be parsed with jq, not sed
    run grep -E 'sed.*"cwd"' "$GATE"
    [ "$status" -eq 1 ]
  fi
}

# ── D8: heartbeat written after relevance guard ──────────────────────────

@test "D8: gate.sh does not create .git/verified-scala3-gate/ in non-openspec repos" {
  # The heartbeat (and STATE_DIR creation) must happen AFTER the relevance
  # guard, not before. A non-openspec git repo should NOT get
  # .git/verified-scala3-gate/ created.
  local tmp_repo
  tmp_repo="$BATS_TEST_TMPDIR/non-openspec-repo"
  mkdir -p "$tmp_repo"
  (cd "$tmp_repo" && git init -q && git config user.email t@t && git config user.name t)

  # Run gate.sh in the non-openspec repo
  run bash "$GATE" --event user-prompt-submit --format text --repo "$tmp_repo" <<< '{}'
  [ "$status" -eq 0 ]

  # Verify .git/verified-scala3-gate/ was NOT created
  [ ! -d "$tmp_repo/.git/verified-scala3-gate" ]
}

@test "D8: gate.sh creates heartbeat in openspec repos after relevance guard" {
  # In an openspec repo, the heartbeat should be written AFTER the
  # relevance guard passes.
  local tmp_repo
  tmp_repo="$BATS_TEST_TMPDIR/openspec-repo"
  mkdir -p "$tmp_repo/openspec"
  (cd "$tmp_repo" && git init -q && git config user.email t@t && git config user.name t)

  # Run gate.sh in the openspec repo
  run bash "$GATE" --event user-prompt-submit --format text --repo "$tmp_repo" <<< '{}'
  [ "$status" -eq 0 ]

  # Verify .git/verified-scala3-gate/ WAS created (heartbeat after guard)
  [ -d "$tmp_repo/.git/verified-scala3-gate" ]
  [ -f "$tmp_repo/.git/verified-scala3-gate/heartbeat" ]
}

# ── D8: invariant banner defined once ─────────────────────────────────────

@test "D8: schema.yaml defines the invariant banner as a canonical top-level key" {
  # The invariant banner text should be defined once as a top-level
  # invariant_banner key. The instruction blocks still embed it verbatim
  # (CLI interpolation is a follow-up), but this key is the canonical source.
  run grep -E '^invariant_banner:' "$SCHEMA_YAML"
  [ "$status" -eq 0 ]
  [ -n "$output" ]
  # The invariant_banner key must contain the operative rule
  run grep -A20 '^invariant_banner:' "$SCHEMA_YAML"
  echo "$output" | grep 'NEVER LET A CLAIM OUTRUN ITS EVIDENCE'
}

@test "D8: every invariant banner copy in instruction blocks matches the canonical definition" {
  # R8 FIX: the prior test only checked that invariant_banner EXISTS. The
  # banner is still duplicated verbatim in instruction blocks (CLI
  # interpolation is a follow-up). This test checks that every copy of the
  # operative rule line in the instruction blocks matches the canonical
  # definition — a drift check, not just an existence check.
  # Extract the operative rule from the canonical definition
  canonical="$(grep -A20 '^invariant_banner:' "$SCHEMA_YAML" | grep 'NEVER LET A CLAIM OUTRUN ITS EVIDENCE' | head -1)"
  [ -n "$canonical" ]
  # Count how many times the operative rule appears in the full schema.yaml
  # It should appear in the canonical definition AND in each instruction block
  n_copies="$(grep -c 'NEVER LET A CLAIM OUTRUN ITS EVIDENCE' "$SCHEMA_YAML")"
  # At least 2 copies: the canonical definition + at least one instruction block
  [ "$n_copies" -ge 2 ]
  # Every copy must be identical in TEXT (no drift). Indentation may differ
  # because the canonical definition and instruction blocks are at different
  # YAML nesting levels — compare the stripped text, not the raw line.
  all_copies="$(grep 'NEVER LET A CLAIM OUTRUN ITS EVIDENCE' "$SCHEMA_YAML" | sed 's/^[[:space:]]*//' | sort -u)"
  n_unique="$(echo "$all_copies" | grep -c .)"
  # All copies must be identical in text — exactly one unique stripped line
  [ "$n_unique" -eq 1 ]
}

# ── D8: payload named-unresolved list is capped ──────────────────────────

# Stub chain-state.sh to emit a large unresolved list, then check the
# gate payload caps it.
mk_fake_chain_state_large() { # $1=output file
  cat >"$1" <<'EOF'
#!/usr/bin/env bash
# Emit a report with 60 unresolved requirements
jq -cn '{
  total: 60,
  bound: 60,
  resolved: 60,
  discharged: 0,
  unresolved: [range(60) | {requirement: ("req-\(.|tostring)"), reasons: ["undischarged"]}]
}'
EOF
  chmod +x "$1"
}

mk_fake_chain_state_small() { # $1=output file
  cat >"$1" <<'EOF'
#!/usr/bin/env bash
# Emit a report with 3 unresolved requirements
jq -cn '{
  total: 3,
  bound: 3,
  resolved: 3,
  discharged: 0,
  unresolved: [
    {requirement: "req-a", reasons: ["undischarged"]},
    {requirement: "req-b", reasons: ["undischarged"]},
    {requirement: "req-c", reasons: ["undischarged"]}
  ]
}'
EOF
  chmod +x "$1"
}

@test "D8: gate payload caps named-unresolved list at N entries with '+M more'" {
  local tmp_repo fake_cs
  tmp_repo="$BATS_TEST_TMPDIR/cap-repo"
  fake_cs="$BATS_TEST_TMPDIR/fake-chain-state.sh"
  mkdir -p "$tmp_repo/openspec/changes/test-change/specs/only"
  (cd "$tmp_repo" && git init -q && git config user.email t@t && git config user.name t)
  mk_fake_chain_state_large "$fake_cs"

  run env CHAIN_STATE_OVERRIDE="$fake_cs" bash "$GATE" --event user-prompt-submit --format text --repo "$tmp_repo" <<< '{}'
  [ "$status" -eq 0 ]
  # The payload should contain "+N more" with the EXACT suffix format —
  # not all 60 req- entries. The cap is exactly 10, so the suffix should
  # be "+50 more" (60 - 10 = 50).
  echo "$output" | grep -E '\+[0-9]+ more'
  # R8 FIX: the prior test used `<= 15` which is weak — it would pass even
  # if the cap were 15 instead of 10. The cap is EXACTLY 10 entries.
  local n_listed
  n_listed="$(echo "$output" | grep -c 'req-')"
  [ "$n_listed" -eq 10 ]  # EXACTLY 10, not "approximately 10"
  # The suffix should be exactly "+50 more" (60 total - 10 listed = 50 more)
  echo "$output" | grep -F '+50 more'
}

@test "D8: gate payload does not cap short unresolved lists" {
  local tmp_repo fake_cs
  tmp_repo="$BATS_TEST_TMPDIR/short-repo"
  fake_cs="$BATS_TEST_TMPDIR/fake-chain-state.sh"
  mkdir -p "$tmp_repo/openspec/changes/test-change/specs/only"
  (cd "$tmp_repo" && git init -q && git config user.email t@t && git config user.name t)
  mk_fake_chain_state_small "$fake_cs"

  run env CHAIN_STATE_OVERRIDE="$fake_cs" bash "$GATE" --event user-prompt-submit --format text --repo "$tmp_repo" <<< '{}'
  [ "$status" -eq 0 ]
  # All 3 requirements should be listed — no "+M more" suffix
  echo "$output" | grep 'req-a'
  echo "$output" | grep 'req-b'
  echo "$output" | grep 'req-c'
  ! echo "$output" | grep -E '\+[0-9]+ more'
}
