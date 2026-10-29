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

# ── D7/D8 source-shape tests MOVED to shape/workflow-hygiene-shape.bats ──
#
# spec:oracle-independence (change: finish-probatio-replacement): the four
# tests asserting over tool SOURCE TEXT — the drift-message scan, the
# tool-name resolution scan, the ledger mutation-case check and the
# gate.sh cwd-parse check — assert how a tool is written, not what it
# does. They live in the shape suite where following the implementation
# is expected; the acceptance oracle holds only behavioural tests.

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
  unresolved: [range(60) | {spec: "s", requirement: ("req-\(.|tostring)"), reasons: ["undischarged"]}]
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
    {spec: "s", requirement: "req-a", reasons: ["undischarged"]},
    {spec: "s", requirement: "req-b", reasons: ["undischarged"]},
    {spec: "s", requirement: "req-c", reasons: ["undischarged"]}
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

# ── spec 10 of repair-probatio-cutover: schema-rename-completion ──────────
# The shipped documents use the current name; the changelog keeps the old
# one as history.

# spec: schema-rename-completion — Scenario: Happy path — the tutorial names the current schema
@test "the tutorial's entry document names the current schema" {
  local index_html="$SCHEMA/docs/index.html"
  [ -f "$index_html" ] || { printf 'tutorial index missing\n' >&2; return 1; }
  # Title and brand name the current schema.
  grep -q '<title>probatio — the workflow tutorial</title>' "$index_html" || {
    printf 'index.html <title> does not name the current schema\n' >&2
    return 1
  }
  grep -q '>probatio</a>' "$index_html" || {
    printf 'index.html brand does not name the current schema\n' >&2
    return 1
  }
  # The body names the schema by its current name.
  grep -q '<code>probatio</code>' "$index_html" || {
    printf 'index.html body does not name the current schema\n' >&2
    return 1
  }
}

# spec: schema-rename-completion — Scenario: Edge case — the changelog retains the previous name as history
@test "the changelog's rename entry keeps the previous name, marked as the pre-rename identity" {
  local changelog="$SCHEMA/CHANGELOG.md"
  [ -f "$changelog" ] || return 1
  local v14_block
  v14_block="$(awk '/^ 14 /{found=1} found{print} /^ 13 /{if(found)exit}' "$changelog")"
  [ -n "$v14_block" ] || { printf 'could not extract v14 changelog block\n' >&2; return 1; }
  # The previous name appears in the rename entry...
  echo "$v14_block" | grep -q 'verified-scala3' || {
    printf 'v14 entry does not record the previous name\n' >&2
    return 1
  }
  # ...marked as the pre-rename identity, not presented as current.
  echo "$v14_block" | grep -qi 'former\|pre-rename\|renamed' || {
    printf 'v14 entry names the previous identity without a historical marker\n' >&2
    return 1
  }
}
