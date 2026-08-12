#!/usr/bin/env bats
#
# Oracle for spec:gate-payload (change: add-correctness-substratum).
#
# Written from the spec and the approved Step 1 design ONLY, before gate.sh's
# new behaviour exists. `chain-state.sh` is a pre-existing, approved
# dependency (spec 3) and is NOT re-tested here — this suite tests gate.sh's
# OWN logic (assembly, escaping, suppression, heartbeat) against a STUB
# chain-state.sh via CHAIN_STATE_OVERRIDE, the same seam pattern
# spec-lint.sh/chain-state.sh already use (SPEC_LINT_OVERRIDE).

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  GATE="$SCHEMA/hooks/gate.sh"
  ROOT="$(repo_root)"
  FX="$BATS_TEST_TMPDIR/repo"
  FAKE_CS="$BATS_TEST_TMPDIR/fake-chain-state.sh"
}

# ── fixtures ──────────────────────────────────────────────────────────────

# A minimal repository the relevance guard accepts: has openspec/, is a git
# repo (heartbeat/fingerprint state lives under .git/).
mk_repo() {
  mkdir -p "$FX/openspec/changes/some-change/specs/only"
  (cd "$FX" && git init -q && git config user.email t@t && git config user.name t)
}

# A stub chain-state.sh that ignores its arguments and prints a FIXED report,
# exiting with a FIXED code — lets the oracle construct exact chain states
# without depending on chain-state.sh's own (already tested) classification.
fake_chain_state() { # $1=json report $2=exit code (default 0)
  cat >"$FAKE_CS" <<EOF
#!/usr/bin/env bash
cat <<'REPORT'
$1
REPORT
exit ${2:-0}
EOF
  chmod +x "$FAKE_CS"
}

# A report with N unresolved entries, deterministic names.
report_with_unresolved() { # $1=count
  local n="$1" entries="[]"
  if [ "$n" -gt 0 ]; then
    entries="$(jq -n --argjson n "$n" '[range($n) | {spec:"s", requirement:("Req " + (.|tostring)), reasons:["undischarged"]}]')"
  fi
  jq -nc --argjson n "$n" --argjson u "$entries" \
    '{change:"some-change", baseline:"abc1234", total:($n+1), bound:($n+1), resolved:($n+1), discharged:1, unresolved:$u, unmapped_obligations:[]}'
}

# CLAUDE_CODE_SESSION_ID is unset here DELIBERATELY. Found during
# implementation: this suite runs INSIDE a live Claude Code session, which
# sets a REAL CLAUDE_CODE_SESSION_ID in the ambient environment — and gate.sh
# correctly prioritises it over VERIFIED_SCALA3_SESSION_ID (by design: it is
# the verified, harness-native signal). Left ambient, every test's intended
# session override was silently defeated by the REAL session id, making
# every call in this suite collide into ONE session regardless of what the
# test asked for. Unset by default; the one test that verifies the priority
# order itself sets it back explicitly.
run_gate() { # extra args after --repo $FX are passed through
  run env -u CLAUDE_CODE_SESSION_ID "$GATE" --repo "$FX" "$@"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The injected payload states the invariant and the unresolved count
# ═════════════════════════════════════════════════════════════════════════

# spec: gate-payload — Scenario: unresolved requirements appear in the payload
@test "three unresolved requirements all appear in the payload by name" {
  mk_repo
  fake_chain_state "$(report_with_unresolved 3)"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event session-start --format text
  assert_status 0 "$status" "the gate never fails"
  assert_contains "$output" "Req 0" "first unresolved requirement"
  assert_contains "$output" "Req 1" "second unresolved requirement"
  assert_contains "$output" "Req 2" "third unresolved requirement"
}

# spec: gate-payload — Scenario: a clean change reports zero without a list
@test "a clean change reports zero unresolved and no requirement lines" {
  mk_repo
  fake_chain_state "$(report_with_unresolved 0)"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event session-start --format text
  assert_contains "$output" "0" "the zero count itself"
  assert_not_contains "$output" "Req " "no requirement line when nothing is unresolved"
}

# spec: gate-payload — Scenario: position alone is not sufficient payload
@test "readiness is never reported without also reporting the unresolved count" {
  mk_repo
  fake_chain_state "$(report_with_unresolved 5)"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event session-start --format text
  # STRENGTHENED at Ring 8: a bare `assert_contains "$output" "5"` would pass
  # if a stray "5" appeared ANYWHERE in the payload (a change name, a byte
  # count, a schema version) — it never actually checked the count was
  # structurally attached to the "unresolved" label, which is what the
  # scenario requires.
  assert_contains "$output" "unresolved 5" "the unresolved count must be paired with its label, not merely present somewhere in the payload"
}

@test "the payload carries the correctness invariant text" {
  mk_repo
  fake_chain_state "$(report_with_unresolved 0)"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event session-start --format text
  assert_contains "$output" "NEVER LET A CLAIM OUTRUN ITS EVIDENCE" "the invariant must be in the payload"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The gate runs on every turn, not only at session start
# ═════════════════════════════════════════════════════════════════════════

# GREEN-BY-DESIGN: gate.sh ALREADY maps --event prompt-submit to
# UserPromptSubmit (verified pre-implementation) — the schema's own
# Implementation Anchors note this is "already built and unwired". This
# guards it from regressing while the adapters are updated to actually use it.
@test "the gate accepts the prompt-submit event and maps it to UserPromptSubmit" {
  mk_repo
  fake_chain_state "$(report_with_unresolved 0)"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event prompt-submit --format hook-json
  assert_status 0 "$status" "the gate never fails"
  assert_contains "$output" '"hookEventName":"UserPromptSubmit"' "prompt-submit maps to UserPromptSubmit"
}

# spec: gate-payload — Requirement: the gate is invoked on prompt submission as well as session start
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

# ═════════════════════════════════════════════════════════════════════════
# Requirement: An unchanged payload is not re-injected
# ═════════════════════════════════════════════════════════════════════════

# spec: gate-payload — Scenario: a changed fact triggers re-injection
@test "a changed unresolved count triggers re-injection within the same session" {
  mk_repo
  fake_chain_state "$(report_with_unresolved 1)"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="sess-a" run_gate --event prompt-submit --format text
  local first="$output"
  fake_chain_state "$(report_with_unresolved 2)"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="sess-a" run_gate --event prompt-submit --format text
  [ -n "$output" ] || {
    printf 'expected a payload on the second call (facts changed); got nothing\n' >&2
    return 1
  }
  [ "$output" != "$first" ]
}

# spec: gate-payload — Scenario: repetition is suppressed when nothing changed
@test "an identical payload is suppressed on the second and third call, same session" {
  mk_repo
  fake_chain_state "$(report_with_unresolved 1)"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="sess-b" run_gate --event prompt-submit --format text
  [ -n "$output" ] || {
    printf 'expected a payload on the first call\n' >&2
    return 1
  }
  CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="sess-b" run_gate --event prompt-submit --format text
  [ -z "$output" ] || {
    printf 'expected suppression on the second call; got: %s\n' "$output" >&2
    return 1
  }
  CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="sess-b" run_gate --event prompt-submit --format text
  [ -z "$output" ] || {
    printf 'expected suppression on the third call; got: %s\n' "$output" >&2
    return 1
  }
}

# FOUND at Ring 8: every existing suppression test used --format text. The
# hook-json path was never checked for the SAME behaviour — an untested gap
# that mattered, because the naive way to check it (piping through the jq
# contract) is vacuously true on empty input and would not have caught a
# regression here (see conforms_hookjson's own comment above).
@test "a suppressed call in hook-json format is exactly empty, not a JSON envelope" {
  mk_repo
  fake_chain_state "$(report_with_unresolved 1)"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="sess-hookjson-supp" \
    run_gate --event prompt-submit --format hook-json
  [ -n "$output" ] || {
    printf 'expected a payload on the first call\n' >&2
    return 1
  }
  conforms_hookjson "$output"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="sess-hookjson-supp" \
    run_gate --event prompt-submit --format hook-json
  [ -z "$output" ] || {
    printf 'expected exactly empty output on the suppressed second call; got: %s\n' "$output" >&2
    return 1
  }
}

@test "suppression state does not leak between different sessions" {
  # RESTATED after the polarity run. The first version only asserted "session
  # D still emits", which is VACUOUSLY true before suppression exists at
  # all — nothing to leak, so every call emits regardless of correctness.
  # This test is meaningless unless it first PROVES within-session
  # suppression genuinely happened; only then does "a different session
  # still emits" demonstrate isolation rather than "suppression never
  # existed to leak".
  mk_repo
  fake_chain_state "$(report_with_unresolved 1)"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="sess-c" run_gate --event prompt-submit --format text
  [ -n "$output" ] || {
    printf 'expected a payload on session c''s first call\n' >&2
    return 1
  }
  CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="sess-c" run_gate --event prompt-submit --format text
  [ -z "$output" ] || {
    printf 'precondition failed: session c did not suppress its own repeat, so this test cannot demonstrate isolation\n' >&2
    return 1
  }
  # NOW the real assertion: a genuinely different session, identical facts,
  # must inject regardless of session c's suppressed state.
  CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="sess-d" run_gate --event prompt-submit --format text
  [ -n "$output" ] || {
    printf 'a new session was wrongly suppressed by an unrelated session state\n' >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The gate never blocks and never fails a session
# ═════════════════════════════════════════════════════════════════════════

# spec: gate-payload — Scenario: a failing chain state tool does not fail the gate
@test "a chain-state tool exiting non-zero does not fail the gate" {
  mk_repo
  fake_chain_state '{}' 2
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event session-start --format text
  assert_status 0 "$status" "the gate must still exit 0"
  assert_contains "$output" "undetermined" "the evidence must be reported as undetermined"
}

# FOUND at Ring 8: a report claiming BOTH undetermined AND a present-but-
# null "total" (a shape the real, approved chain-state.sh contract never
# produces — it emits either the undetermined shape with no "total" key at
# all, or the clean shape with a numeric one — but which this override seam
# does not itself prevent) rendered as a clean "0 unresolved" because the
# old check only tested key PRESENCE, not that "total" was actually usable.
@test "a chain-state report combining undetermined with a null total is not rendered as clean" {
  mk_repo
  fake_chain_state '{"change":"some-change","baseline":"abc1234","undetermined":true,"reason":"stub","total":null,"bound":null,"resolved":null,"discharged":null,"unresolved":[],"unmapped_obligations":[]}' 0
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event session-start --format text
  assert_status 0 "$status" "the gate must still exit 0"
  assert_contains "$output" "undetermined" "a null total must never be reported as a clean, resolved state"
}

@test "an absent chain-state tool does not fail the gate, and is reported" {
  # STRENGTHENED after the polarity run: asserting only exit==0 was VACUOUS
  # pre-implementation, since current gate.sh never invokes chain-state.sh at
  # all, so an absent one trivially changes nothing. Requiring the
  # "undetermined" signal forces this test to depend on the new invocation
  # code actually existing and handling the absence explicitly.
  mk_repo
  CHAIN_STATE_OVERRIDE="$BATS_TEST_TMPDIR/does-not-exist.sh" run_gate --event session-start --format text
  assert_status 0 "$status" "the gate must still exit 0"
  assert_contains "$output" "undetermined" "an absent tool must be reported, not silently ignored"
}

# spec: gate-payload — Scenario: a repository outside the workflow receives nothing
@test "a repository with no openspec directory injects nothing and exits 0" {
  mkdir -p "$FX"
  (cd "$FX" && git init -q)
  run_gate --event session-start --format text
  assert_status 0 "$status" "the gate must still exit 0"
  [ -z "$output" ] || {
    printf 'expected no output for a non-workflow repository; got: %s\n' "$output" >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: A gate that never ran is detectable without operator setup
# ═════════════════════════════════════════════════════════════════════════

# spec: gate-payload — Scenario: a silent invocation still records that it ran
@test "a silent invocation (no openspec/) still records a heartbeat" {
  mkdir -p "$FX"
  (cd "$FX" && git init -q)
  run_gate --event session-start --format text
  run "$GATE" --check-installed --repo "$FX"
  assert_status 0 "$status" "the installation check itself must not fail"
  assert_contains "$output" '"installed":true' "a heartbeat exists despite the silent exit"
}

# spec: gate-payload — Scenario: an uninstalled gate is reported
@test "a repository the gate never ran against reports not installed" {
  mkdir -p "$FX"
  (cd "$FX" && git init -q)
  # no run_gate call at all — the gate genuinely never fired here
  run "$GATE" --check-installed --repo "$FX"
  assert_contains "$output" '"installed":false' "no heartbeat means not installed"
}

@test "no operator environment variable is required to check installation" {
  mkdir -p "$FX"
  (cd "$FX" && git init -q)
  mk_repo
  fake_chain_state "$(report_with_unresolved 0)"
  # deliberately UNSET the trace variable the OLD verification path required
  env -u VERIFIED_SCALA3_HOOKS_TRACE bash -c \
    "CHAIN_STATE_OVERRIDE='$FAKE_CS' '$GATE' --repo '$FX' --event session-start --format text >/dev/null"
  run env -u VERIFIED_SCALA3_HOOKS_TRACE bash -c "'$GATE' --check-installed --repo '$FX'"
  assert_contains "$output" '"installed":true' "no env var was needed to detect the run"
}

# FOUND during implementation, by hand-verifying this exact obligation from
# inside a real git worktree (this repository's own dev environment is one):
# `.git` in a worktree is a plain FILE (a "gitdir:" pointer), not a
# directory. Every `mk_repo` fixture above uses `git init`, which always
# produces a directory — so this shape was never exercised, and the
# directory-only check silently disabled the heartbeat/suppression state
# dir in every worktree, with no error and no visible symptom.
@test "the heartbeat and installation check work from inside a git worktree, not only a plain checkout" {
  mkdir -p "$FX"
  (cd "$FX" && git init -q && git config user.email t@t && git config user.name t \
    && git commit -q --allow-empty -m init \
    && git worktree add -q "$FX-wt" -b wt-branch)
  run_gate --event session-start --format text
  run "$GATE" --check-installed --repo "$FX"
  assert_contains "$output" '"installed":true' "heartbeat must persist for the main worktree checkout"

  run env -u CLAUDE_CODE_SESSION_ID "$GATE" --repo "$FX-wt" --event session-start --format text
  assert_status 0 "$status" "the gate never fails from a secondary worktree"
  run "$GATE" --check-installed --repo "$FX-wt"
  assert_contains "$output" '"installed":true' "heartbeat must persist for a secondary worktree checkout too"
}

# ═════════════════════════════════════════════════════════════════════════
# Properties (Ring 3) — ENUMERATED over finite listed domains.
# ═════════════════════════════════════════════════════════════════════════

# spec: gate-payload — Property: Payload contents follow the chain state exactly
@test "PROPERTY payload names exactly the unresolved set, no additions or omissions" {
  # Generator strategy (enumerated): unresolved counts 0, 1, 2, 12, plus one
  # requirement whose name contains a character requiring escaping.
  local n
  for n in 0 1 2 12; do
    mk_repo
    fake_chain_state "$(report_with_unresolved "$n")"
    CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event session-start --format text
    local i
    for i in $(seq 0 $((n - 1))); do
      assert_contains "$output" "Req $i" "count=$n missing Req $i"
    done
    # no PHANTOM entry beyond the constructed set
    assert_not_contains "$output" "Req $n" "count=$n has a phantom extra entry"
    rm -rf "$FX"
  done

  # the escaping edge case: a requirement name containing a double quote
  mk_repo
  local escaped
  escaped="$(jq -nc '{change:"c",baseline:"b",total:1,bound:1,resolved:1,discharged:0,unresolved:[{spec:"s",requirement:"has \"quote\" in it",reasons:["undischarged"]}],unmapped_obligations:[]}')"
  fake_chain_state "$escaped"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event session-start --format text
  assert_contains "$output" 'has "quote" in it' "the quoted name survives text-format assembly"
}

# spec: gate-payload — Property: Every output format carries the same facts
@test "PROPERTY hook-json and text carry the same facts" {
  # Generator strategy (enumerated): the two supported formats crossed with
  # unresolved counts 0, 1, 2, plus a name with a quote and one with a
  # newline, which must survive hook-json's escaping specifically.
  #
  # FOUND during implementation: the text call and the hook-json call for the
  # SAME comparison must use DIFFERENT --session values. Suppression is
  # keyed by session, and two calls issued back-to-back from this test
  # process legitimately share ONE session under PPID fallback — which would
  # make the SECOND call (whichever format runs second) come back empty by
  # DESIGN, not because the formats disagree. That is the suppression
  # feature working correctly; it just is not what this property tests, so
  # each format gets its own session to isolate the two concerns.
  local n
  for n in 0 1 2; do
    mk_repo
    fake_chain_state "$(report_with_unresolved "$n")"
    CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event session-start --format text --session "prop2-text-$n"
    local text_out="$output"
    CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event session-start --format hook-json --session "prop2-json-$n"
    local json_out="$output"
    conforms_hookjson "$json_out"
    local ctx
    ctx="$(printf '%s' "$json_out" | jq -r '.hookSpecificOutput.additionalContext // empty')"
    [ "$ctx" = "$text_out" ] || {
      printf 'count=%s: text and hook-json additionalContext differ.\n text: %s\n json: %s\n' "$n" "$text_out" "$ctx" >&2
      return 1
    }
    rm -rf "$FX"
  done

  mk_repo
  local nl_report
  nl_report="$(jq -nc '{change:"c",baseline:"b",total:1,bound:1,resolved:1,discharged:0,unresolved:[{spec:"s",requirement:("line one" + "\n" + "line two"),reasons:["undischarged"]}],unmapped_obligations:[]}')"
  fake_chain_state "$nl_report"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event session-start --format hook-json --session "prop2-nl"
  conforms_hookjson "$output"
  local ctx2
  ctx2="$(printf '%s' "$output" | jq -r '.hookSpecificOutput.additionalContext')"
  assert_contains "$ctx2" "line one" "newline-containing name survives hook-json escaping"
  assert_contains "$ctx2" "line two" "newline-containing name survives hook-json escaping"
}

# FOUND during implementation, alongside the fix above: this suite runs
# INSIDE a live Claude Code session with a REAL CLAUDE_CODE_SESSION_ID set,
# which every other test in this file must UNSET to test session behaviour
# in isolation (see run_gate). That priority (explicit env signal over a
# generic override) is deliberate design, not an accident, so it gets its
# own direct test rather than being merely inferred from everything else
# having to work around it.
@test "CLAUDE_CODE_SESSION_ID takes priority over VERIFIED_SCALA3_SESSION_ID" {
  mk_repo
  fake_chain_state "$(report_with_unresolved 1)"
  CLAUDE_CODE_SESSION_ID="real-sess" VERIFIED_SCALA3_SESSION_ID="ignored-a" \
    run "$GATE" --repo "$FX" --event prompt-submit --format text
  [ -n "$output" ] || {
    printf 'expected a payload on the first call\n' >&2
    return 1
  }
  # a DIFFERENT VERIFIED_SCALA3_SESSION_ID but the SAME CLAUDE_CODE_SESSION_ID
  # must be treated as the SAME session — proving CLAUDE_CODE_SESSION_ID, not
  # the generic override, decided identity.
  CLAUDE_CODE_SESSION_ID="real-sess" VERIFIED_SCALA3_SESSION_ID="ignored-b" \
    run "$GATE" --repo "$FX" --event prompt-submit --format text
  [ -z "$output" ] || {
    printf 'expected suppression: CLAUDE_CODE_SESSION_ID was unchanged, so this must be the same session despite the differing generic override\n' >&2
    return 1
  }
}

# FOUND at Ring 8: the raw session id was sanitized for filename safety with
# `tr -c 'A-Za-z0-9._-' '_'`, which is LOSSY — every disallowed character
# collapses to the same '_'. Two distinct `--session` values differing only
# outside that character set sanitized to the identical string and collided
# onto the same suppression-state file: a brand-new session was wrongly
# treated as a repeat of an unrelated one. `--session` is adapter-supplied
# and not restricted to a safe character set, so this was reachable, not
# theoretical.
@test "sessions whose ids differ only in filename-unsafe characters are not treated as the same session" {
  mk_repo
  fake_chain_state "$(report_with_unresolved 1)"
  run_gate --event prompt-submit --format text --session 'abc!def'
  [ -n "$output" ] || {
    printf 'expected a payload on the first session'\''s first call\n' >&2
    return 1
  }
  # a DIFFERENT raw session id that only the OLD lossy sanitizer would have
  # collapsed onto the same file as the one above
  run_gate --event prompt-submit --format text --session 'abc?def'
  [ -n "$output" ] || {
    printf 'a genuinely new session ("abc?def") was wrongly suppressed by an unrelated session'\''s state ("abc!def") — the sanitizer collapsed both to "abc_def"\n' >&2
    return 1
  }
}

# FOUND during Ring 8: `jq -e -f contract.jq` is VACUOUSLY TRUE on empty
# input — jq produces zero output values, so `-e` never sees a falsy result
# and exits 0 without the contract's logic ever running. A gate.sh no-op
# (suppressed call, out-of-scope repo) legitimately emits EMPTY stdout, even
# in hook-json format — not a JSON envelope. Piping that through this
# checker would have silently "passed" without checking anything. This
# helper is therefore for NON-EMPTY hook-json output only; the empty case is
# asserted directly with `[ -z "$output" ]`, never through here.
conforms_hookjson() { # $1=json text
  [ -n "$1" ] || {
    printf 'conforms_hookjson called on empty input — jq -e is vacuously true here, use [ -z "$output" ] to assert emptiness instead\n' >&2
    return 1
  }
  printf '%s' "$1" | jq -e -f "$SCHEMA/scanner/gate-hookjson-contract.jq" >/dev/null 2>&1 || {
    printf 'hook-json output does not conform to the approved contract: %s\n' "$1" >&2
    return 1
  }
}

# spec: gate-payload — Property: The gate always exits successfully
@test "PROPERTY the gate exits 0 under every induced failure condition" {
  # Generator strategy (enumerated): the six conditions the spec names.
  #
  # STRENGTHENED after the polarity run: checking ONLY exit==0 was vacuous
  # for every condition that routes through chain-state, since current
  # gate.sh never invokes it — nothing new is exercised by breaking a tool
  # that is never called. Conditions where a repository WITH an active
  # change is used now also require the "undetermined" signal, so the
  # property depends on the new invocation-and-degrade code actually
  # existing. "absent-workflow-dir" correctly expects NO output at all (a
  # repository outside the workflow, per its own scenario) — asserting
  # "undetermined" there would be wrong, not stronger.
  local condition
  for condition in absent-workflow-dir absent-chain-state-tool chain-state-nonzero \
    unreadable-ledger unreadable-spec-dir unwritable-heartbeat; do
    rm -rf "$FX"
    case "$condition" in
      absent-workflow-dir)
        mkdir -p "$FX"
        (cd "$FX" && git init -q)
        run_gate --event session-start --format text
        [ -z "$output" ] || {
          printf 'condition=%s: expected no output, got: %s\n' "$condition" "$output" >&2
          return 1
        }
        ;;
      absent-chain-state-tool)
        mk_repo
        CHAIN_STATE_OVERRIDE="$BATS_TEST_TMPDIR/nope-$condition.sh" run_gate --event session-start --format text
        assert_contains "$output" "undetermined" "condition=$condition must be reported"
        ;;
      chain-state-nonzero)
        mk_repo
        fake_chain_state '{}' 2
        CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event session-start --format text
        assert_contains "$output" "undetermined" "condition=$condition must be reported"
        ;;
      unreadable-ledger)
        mk_repo
        : >"$FX/openspec/changes/some-change/evidence-ledger.jsonl"
        chmod 000 "$FX/openspec/changes/some-change/evidence-ledger.jsonl"
        run_gate --event session-start --format text
        chmod 644 "$FX/openspec/changes/some-change/evidence-ledger.jsonl"
        assert_contains "$output" "undetermined" "condition=$condition must be reported"
        ;;
      unreadable-spec-dir)
        mk_repo
        chmod 000 "$FX/openspec/changes/some-change/specs/only"
        run_gate --event session-start --format text
        chmod 755 "$FX/openspec/changes/some-change/specs/only"
        assert_contains "$output" "undetermined" "condition=$condition must be reported"
        ;;
      unwritable-heartbeat)
        mk_repo
        fake_chain_state "$(report_with_unresolved 0)"
        mkdir -p "$FX/.git/verified-scala3-gate"
        chmod 000 "$FX/.git/verified-scala3-gate"
        CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event session-start --format text
        chmod 755 "$FX/.git/verified-scala3-gate"
        # An unwritable heartbeat is a DEGRADED capability, not a reason to
        # withhold the payload — the invariant text must still appear.
        assert_contains "$output" "NEVER LET A CLAIM OUTRUN ITS EVIDENCE" "condition=$condition must still produce a payload"
        ;;
    esac
    [ "$status" -eq 0 ] || {
      printf 'condition=%s: gate exited %s, expected 0\n' "$condition" "$status" >&2
      return 1
    }
  done
}

# spec: gate-payload — Property: Suppression depends only on whether facts changed
@test "PROPERTY re-injection occurs iff facts differ from the prior call, same session" {
  # Generator strategy (enumerated): pairs covering equal, differing-only-in-
  # count, and differing-only-in-requirement-names.
  local pair
  for pair in equal diff-count diff-names; do
    rm -rf "$FX"; mk_repo
    local sid="sess-prop-$pair"
    case "$pair" in
      equal)
        fake_chain_state "$(report_with_unresolved 2)"
        CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="$sid" run_gate --event prompt-submit --format text
        CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="$sid" run_gate --event prompt-submit --format text
        [ -z "$output" ] || {
          printf 'pair=equal: expected suppression, got output\n' >&2
          return 1
        }
        ;;
      diff-count)
        fake_chain_state "$(report_with_unresolved 2)"
        CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="$sid" run_gate --event prompt-submit --format text
        fake_chain_state "$(report_with_unresolved 3)"
        CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="$sid" run_gate --event prompt-submit --format text
        [ -n "$output" ] || {
          printf 'pair=diff-count: expected re-injection, got nothing\n' >&2
          return 1
        }
        ;;
      diff-names)
        fake_chain_state "$(jq -nc '{change:"c",baseline:"b",total:1,bound:1,resolved:1,discharged:0,unresolved:[{spec:"s",requirement:"Alpha",reasons:["undischarged"]}],unmapped_obligations:[]}')"
        CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="$sid" run_gate --event prompt-submit --format text
        fake_chain_state "$(jq -nc '{change:"c",baseline:"b",total:1,bound:1,resolved:1,discharged:0,unresolved:[{spec:"s",requirement:"Beta",reasons:["undischarged"]}],unmapped_obligations:[]}')"
        CHAIN_STATE_OVERRIDE="$FAKE_CS" VERIFIED_SCALA3_SESSION_ID="$sid" run_gate --event prompt-submit --format text
        [ -n "$output" ] || {
          printf 'pair=diff-names: expected re-injection (same count, different name), got nothing\n' >&2
          return 1
        }
        ;;
    esac
  done
}
