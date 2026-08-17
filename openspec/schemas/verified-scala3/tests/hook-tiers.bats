#!/usr/bin/env bats
#
# Oracle for spec:hook-tiers (change: add-correctness-substratum).
#
# Written from the spec and the approved Step 1 design ONLY, before gate.sh's
# post-edit/completion events exist. `spec-lint.sh`/`danger-scan.sh`/
# `chain-state.sh` are pre-existing, approved dependencies and are NOT
# re-tested here — stubbed via *_OVERRIDE seams (same pattern as
# CHAIN_STATE_OVERRIDE, already used in gate-payload.bats) so this suite
# tests gate.sh's OWN triggering/refusal logic, never the checks themselves.

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  GATE="$SCHEMA/hooks/gate.sh"
  FX="$BATS_TEST_TMPDIR/repo"
  FAKE_SL="$BATS_TEST_TMPDIR/fake-spec-lint.sh"
  FAKE_DS="$BATS_TEST_TMPDIR/fake-danger-scan.sh"
  FAKE_CS="$BATS_TEST_TMPDIR/fake-chain-state.sh"
  SL_MARKER="$BATS_TEST_TMPDIR/spec-lint-invoked"
  DS_MARKER="$BATS_TEST_TMPDIR/danger-scan-invoked"
}

# ── fixtures ──────────────────────────────────────────────────────────────

mk_repo() {
  mkdir -p "$FX/openspec/changes/some-change/specs/only"
  mkdir -p "$FX/openspec/changes/archived-change/archive"
  mkdir -p "$FX/src/main/scala/pkg"
  mkdir -p "$FX/src/test/scala/pkg"
  (cd "$FX" && git init -q && git config user.email t@t && git config user.name t)
}

# A stub reporting a FIXED finding set and exit code, recording that it was
# invoked (so triggering can be observed, not just its output).
fake_spec_lint() { # $1=output text $2=exit code (default 0)
  cat >"$FAKE_SL" <<EOF
#!/usr/bin/env bash
: >"$SL_MARKER"
cat <<'REPORT'
$1
REPORT
exit ${2:-0}
EOF
  chmod +x "$FAKE_SL"
}

fake_danger_scan() { # $1=output text $2=exit code (default 0)
  cat >"$FAKE_DS" <<EOF
#!/usr/bin/env bash
: >"$DS_MARKER"
cat <<'REPORT'
$1
REPORT
exit ${2:-0}
EOF
  chmod +x "$FAKE_DS"
}

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

# A chain-state.sh-shaped report with N unresolved entries.
cs_report() { # $1=count
  local n="$1" entries="[]"
  if [ "$n" -gt 0 ]; then
    entries="$(jq -n --argjson n "$n" '[range($n) | {spec:"only", requirement:("Req " + (.|tostring)), reasons:["undischarged"]}]')"
  fi
  jq -nc --argjson n "$n" --argjson u "$entries" \
    '{change:"some-change", baseline:"abc1234", total:($n+1), bound:($n+1), resolved:($n+1), discharged:(if $n==0 then ($n+1) else 1 end), unresolved:$u, unmapped_obligations:[]}'
}

cs_undetermined() {
  jq -nc '{change:"some-change", baseline:"abc1234", undetermined:true, reason:"stub", total:null, bound:null, resolved:null, discharged:null, unresolved:[], unmapped_obligations:[]}'
}

run_gate() { # extra args after --repo $FX are passed through
  run env -u CLAUDE_CODE_SESSION_ID "$GATE" --repo "$FX" "$@"
}

# FOUND while confirming RED polarity: gate.sh, before this spec, has no
# event dispatch at all — EVERY event (including these two new ones, before
# they exist) falls through to the OLD session-start-style context
# injection, which unconditionally calls chain-state.sh, which inherits
# SPEC_LINT_OVERRIDE from the environment (it already reads that var for its
# own, unrelated purpose) and invoked the STUB meant for the new post-edit
# tier — making several post-edit tests pass "green" before any post-edit
# logic existed. Neutralized by pointing CHAIN_STATE_OVERRIDE at a stub that
# never touches spec-lint, in every post-edit test, so the ONLY way the
# spec-lint/danger-scan stubs can be invoked is the tier actually being
# implemented and calling them for its own reason.
neutral_chain_state() {
  cat >"$FAKE_CS" <<'EOF'
#!/usr/bin/env bash
echo '{"change":"none","baseline":"0000000","total":0,"bound":0,"resolved":0,"discharged":0,"unresolved":[],"unmapped_obligations":[]}'
exit 0
EOF
  chmod +x "$FAKE_CS"
}
run_gate_post_edit() { # $@ = args after --repo $FX
  neutral_chain_state
  run env -u CLAUDE_CODE_SESSION_ID CHAIN_STATE_OVERRIDE="$FAKE_CS" "$GATE" --repo "$FX" "$@"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: A specification edit is checked immediately
# ═════════════════════════════════════════════════════════════════════════

# spec: hook-tiers — Scenario: findings are returned after a malformed edit
@test "a malformed spec edit returns the lint finding" {
  mk_repo
  fake_spec_lint "FAIL F7 line 12: requirement \"Foo\" is named by NO proof obligation" 1
  SPEC_LINT_OVERRIDE="$FAKE_SL" run_gate_post_edit --event post-edit \
    --file "$FX/openspec/changes/some-change/specs/only/spec.md" --format text
  assert_status 0 "$status" "post-edit never blocks"
  assert_contains "$output" "FAIL F7" "the lint finding must be returned"
  [ -f "$SL_MARKER" ] || { printf 'spec-lint was not invoked\n' >&2; return 1; }
}

# spec: hook-tiers — Scenario: a clean edit returns nothing
@test "a lint-clean spec edit returns no findings" {
  mk_repo
  fake_spec_lint "" 0
  SPEC_LINT_OVERRIDE="$FAKE_SL" run_gate_post_edit --event post-edit \
    --file "$FX/openspec/changes/some-change/specs/only/spec.md" --format text
  assert_status 0 "$status" "post-edit never blocks"
  assert_not_contains "$output" "FAIL" "a clean lint must report nothing"
  [ -f "$SL_MARKER" ] || { printf 'spec-lint was not invoked\n' >&2; return 1; }
}

# spec: hook-tiers — Scenario: an edit outside the specification directory does not trigger the lint
@test "an edit outside a specs directory never invokes the lint" {
  mk_repo
  fake_spec_lint "FAIL F7 line 1: should never appear" 1
  SPEC_LINT_OVERRIDE="$FAKE_SL" run_gate_post_edit --event post-edit \
    --file "$FX/README.md" --format text
  assert_status 0 "$status" "post-edit never blocks"
  [ ! -f "$SL_MARKER" ] || { printf 'spec-lint was wrongly invoked for a non-spec path\n' >&2; return 1; }
}

# spec: hook-tiers — Scenario (implied by the Property below): an archived change's spec does not trigger the lint
@test "an archived change's spec edit does not invoke the lint" {
  mk_repo
  fake_spec_lint "FAIL F7 line 1: should never appear" 1
  SPEC_LINT_OVERRIDE="$FAKE_SL" run_gate_post_edit --event post-edit \
    --file "$FX/openspec/changes/archived-change/archive/specs/only/spec.md" --format text
  assert_status 0 "$status" "post-edit never blocks"
  [ ! -f "$SL_MARKER" ] || { printf 'spec-lint was wrongly invoked for an archived change\n' >&2; return 1; }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: A production source edit is scanned immediately
# ═════════════════════════════════════════════════════════════════════════

# spec: hook-tiers — Scenario: an unjustified catch-all is reported at the edit
@test "an unjustified catch-all in a production source edit is reported" {
  mk_repo
  fake_danger_scan "src/main/scala/pkg/Foo.scala:10: catch-all — case _ =>" 1
  DANGER_SCAN_OVERRIDE="$FAKE_DS" run_gate_post_edit --event post-edit \
    --file "$FX/src/main/scala/pkg/Foo.scala" --format text
  assert_status 0 "$status" "post-edit never blocks"
  assert_contains "$output" "catch-all" "the danger-scan finding must be returned"
  [ -f "$DS_MARKER" ] || { printf 'danger-scan was not invoked\n' >&2; return 1; }
}

# spec: hook-tiers — Scenario: a justified occurrence is not reported
@test "a justified production-source edit returns no finding" {
  mk_repo
  fake_danger_scan "" 0
  DANGER_SCAN_OVERRIDE="$FAKE_DS" run_gate_post_edit --event post-edit \
    --file "$FX/src/main/scala/pkg/Foo.scala" --format text
  assert_status 0 "$status" "post-edit never blocks"
  assert_not_contains "$output" "catch-all" "a justified occurrence must report nothing"
  [ -f "$DS_MARKER" ] || { printf 'danger-scan was not invoked\n' >&2; return 1; }
}

@test "a test-source edit does not invoke the danger scan" {
  mk_repo
  fake_danger_scan "should never appear" 1
  DANGER_SCAN_OVERRIDE="$FAKE_DS" run_gate_post_edit --event post-edit \
    --file "$FX/src/test/scala/pkg/FooTest.scala" --format text
  assert_status 0 "$status" "post-edit never blocks"
  [ ! -f "$DS_MARKER" ] || { printf 'danger-scan was wrongly invoked for a test source\n' >&2; return 1; }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Post-edit correction never blocks the edit
# ═════════════════════════════════════════════════════════════════════════

# spec: hook-tiers — Scenario: the edit survives its own findings
@test "the edited file's content is untouched regardless of findings" {
  mk_repo
  local spec_path="$FX/openspec/changes/some-change/specs/only/spec.md"
  printf 'the exact edited content\n' >"$spec_path"
  before="$(cat "$spec_path")"
  fake_spec_lint "FAIL F7 line 1: several findings here" 1
  SPEC_LINT_OVERRIDE="$FAKE_SL" run_gate_post_edit --event post-edit --file "$spec_path" --format text
  after="$(cat "$spec_path")"
  [ "$before" = "$after" ] || {
    printf 'the edited file changed after the post-edit tier ran\nbefore: %s\nafter: %s\n' "$before" "$after" >&2
    return 1
  }
}

# spec: hook-tiers — Scenario: a failing check does not reject the edit
@test "a check that cannot run at all does not reject the edit" {
  mk_repo
  local spec_path="$FX/openspec/changes/some-change/specs/only/spec.md"
  SPEC_LINT_OVERRIDE="$BATS_TEST_TMPDIR/does-not-exist.sh" run_gate_post_edit --event post-edit --file "$spec_path" --format text
  assert_status 0 "$status" "an unavailable check must not fail the edit"
  # STRENGTHENED: a bare substring "check" would pass on unrelated boilerplate
  # text ("gate checks scanner/spec-lint.sh...") that exists even with no
  # post-edit tier at all. Requires the specific check-failure phrasing.
  assert_contains "$output" "could not run" "the failure must be reported as a check failure, specifically"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: A turn claiming completion while evidence is missing is refused
# ═════════════════════════════════════════════════════════════════════════

COMPLETION_CLAIM='## ✓ Spec 3/6 complete: chain-state'
RING_ONLY_TEXT='Ring 8: fresh-context review PASS. Continuing with the remaining work.'
NO_CLAIM_TEXT='Still investigating the failing test, will report back shortly.'

# ── how a turn ASSERTS COMPLETION ────────────────────────────────────────
# The requirement is unchanged: completion is refused when the turn asserts a
# checkpoint/spec/ring result and the chain is not discharged. What changed is
# how the gate DETECTS that assertion. It used to match text in the turn
# transcript; it now reads the checkpoint presentation marker that
# checkpoint.sh's own output produces. Text matching was approximate by
# construction — the spec's own obligation table recorded that as an accepted
# limit — and it failed twice in practice (a case-sensitive regex missed
# "COMPLETE"; a tight pattern missed "Spec 3/7: recorder-sink — COMPLETE").
#
# So these tests construct the claim the way the gate now recognises one.
# --turn-text no longer carries any meaning for the trigger and is dropped
# from the completion tests rather than left in to look load-bearing.
#
# NOTE on the turn-kind domain: the spec enumerates three kinds {asserts
# completion, asserts a ring result, asserts nothing}. Under a deterministic
# trigger the last two are realised IDENTICALLY (no marker) — the distinction
# only ever existed because a text heuristic could confuse them. The
# enumeration is kept because it is the spec's stated domain, and the case it
# guards (ring-result language must not read as a completion claim) is now
# true by construction rather than by pattern.
claim_completion() { # $1=session — mark this turn as asserting completion
  local gitdir state encoded
  gitdir="$(cd "$FX" && git rev-parse --absolute-git-dir 2>/dev/null)" || return 1
  state="$gitdir/verified-scala3-gate"
  mkdir -p "$state"
  encoded="$(printf '%s' "$1" | jq -Rr '@base64' | tr '+/=' '-_.')"
  printf 'deadbeef' >"$state/presentation-some-change-only-$encoded"
}

# spec: hook-tiers — Scenario: a completion claim with unresolved requirements is refused
@test "a completion claim with unresolved requirements is refused and names them" {
  mk_repo
  fake_chain_state "$(cs_report 2)"
  claim_completion "sess-refuse-1"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
    --session "sess-refuse-1" --format text
  assert_status 1 "$status" "a completion claim over unresolved requirements is a finding"
  assert_contains "$output" "Req 0" "the refusal must name the unresolved requirements"
  assert_contains "$output" "Req 1" "the refusal must name the unresolved requirements"
}

# spec: hook-tiers — Scenario: a completion claim with a full chain proceeds
@test "a completion claim with a fully discharged chain proceeds" {
  mk_repo
  fake_chain_state "$(cs_report 0)"
  claim_completion "sess-proceed-1"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
    --session "sess-proceed-1" --format text
  assert_status 0 "$status" "a fully discharged completion claim must proceed"
}

# spec: hook-tiers — Scenario: a turn making no completion claim proceeds
@test "a turn asserting nothing about completion proceeds despite unresolved requirements" {
  mk_repo
  fake_chain_state "$(cs_report 5)"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
    --turn-text "$NO_CLAIM_TEXT" --session "sess-noclaim-1" --format text
  assert_status 0 "$status" "no completion claim means nothing to refuse"
}

# Distinguishes "asserts completion" from merely mentioning a ring result —
# the heuristic must not fire on ring-result language alone.
@test "a turn reporting a ring result without claiming completion proceeds" {
  mk_repo
  fake_chain_state "$(cs_report 5)"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
    --turn-text "$RING_ONLY_TEXT" --session "sess-ringonly-1" --format text
  assert_status 0 "$status" "a ring-result mention alone is not a completion claim"
}

# spec: hook-tiers — Scenario: undetermined evidence does not silently permit completion
@test "undetermined chain state refuses completion with a distinct reason" {
  mk_repo
  fake_chain_state "$(cs_undetermined)" 2
  claim_completion "sess-undetermined-1"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
    --session "sess-undetermined-1" --format text
  assert_status 2 "$status" "undetermined evidence must never permit completion"
  assert_contains "$output" "could not be determined" "the reason must say evidence could not be determined"
  assert_not_contains "$output" "unresolved requirement" "must not be worded as an unresolved-requirements refusal"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The completion gate cannot deadlock a session
# ═════════════════════════════════════════════════════════════════════════

# spec: hook-tiers — Scenario: a second attempt proceeds
@test "a second completion attempt in the same turn proceeds after the first refusal" {
  mk_repo
  fake_chain_state "$(cs_report 3)"
  claim_completion "sess-bounded-1"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
    --session "sess-bounded-1" --format text
  assert_status 1 "$status" "the first attempt is refused"
  claim_completion "sess-bounded-1"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
    --session "sess-bounded-1" --format text
  assert_status 0 "$status" "the second attempt in the same turn must proceed, not refuse again"
}

# spec: hook-tiers — Scenario: refusal state does not carry into the next turn
@test "a new turn (marked by prompt-submit) is refused again if still unresolved" {
  mk_repo
  fake_chain_state "$(cs_report 3)"
  claim_completion "sess-bounded-2"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
    --session "sess-bounded-2" --format text
  assert_status 1 "$status" "the first attempt is refused"
  claim_completion "sess-bounded-2"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event prompt-submit --session "sess-bounded-2" --format text >/dev/null
  claim_completion "sess-bounded-2"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
    --session "sess-bounded-2" --format text
  assert_status 1 "$status" "a genuinely new turn must be evaluated fresh, not silently allowed by stale refusal state"
}

# FOUND at Ring 8, CRITICAL: relying on the script's raw exit code (1/2) to
# signal a block is not what a real harness actually reads. Verified against
# this project's own already-approved docs/11-enforcement.html and Claude
# Code's documented hook contract: exit 1 is treated as a NON-BLOCKING
# error (the turn proceeds — the refusal silently never happens at all),
# and on exit 2 the reason is read from STDERR, which nothing here ever
# wrote to. For `--format hook-json`, the only signal a harness actually
# honours is `{"decision":"block",...}` JSON on an exit-0 response — so
# that is what must be asserted, not the script's own internal exit code.
@test "hook-json format signals a refusal via decision:block on exit 0, for both refusal reasons" {
  mk_repo
  fake_chain_state "$(cs_report 2)"
  claim_completion "sess-hookjson-unresolved"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
    --session "sess-hookjson-unresolved" --format hook-json
  assert_status 0 "$status" "hook-json must exit 0 so Claude Code actually reads the JSON decision"
  assert_contains "$output" '"decision":"block"' "the refusal must be signalled via decision:block, not exit code alone"
  assert_contains "$output" "Req 0" "the unresolved requirements must still be named in the reason"

  fake_chain_state "$(cs_undetermined)" 2
  claim_completion "sess-hookjson-undetermined"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
    --session "sess-hookjson-undetermined" --format hook-json
  assert_status 0 "$status" "hook-json must exit 0 for the undetermined refusal too"
  assert_contains "$output" '"decision":"block"' "the undetermined refusal must also be signalled via decision:block"
  assert_contains "$output" "could not be determined" "the reason must remain distinct in hook-json format too"
}

@test "hook-json format emits nothing (no decision field) when completion proceeds" {
  mk_repo
  fake_chain_state "$(cs_report 0)"
  claim_completion "sess-hookjson-allow"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
    --session "sess-hookjson-allow" --format hook-json
  assert_status 0 "$status" "allow is exit 0"
  assert_not_contains "$output" "decision" "a proceeding turn must carry no decision field at all — silence where it does not apply"
}

# FOUND at Ring 8, CRITICAL: the bounded-refusal guarantee lives ENTIRELY in
# a state-directory marker file. When that state cannot be established (no
# .git yet — a real, already-anticipated condition for the injection tier),
# the "already refused" check can never become true, and a refusal-worthy
# claim would refuse EVERY attempt with no bound whatsoever — the exact
# "blocking hook that cannot be escaped" failure this schema's own
# documentation warns is genuinely difficult to recover from. Verified this
# was reachable before the fix (3 consecutive refusals, no .git present, no
# bound); the fix must fail OPEN instead when the safety net is unavailable.
@test "completion never blocks when the bounded-refusal state cannot be established (no .git)" {
  local no_git_fx="$BATS_TEST_TMPDIR/no-git-repo"
  mkdir -p "$no_git_fx/openspec/changes/some-change/specs/only"
  fake_chain_state "$(cs_report 2)"
  local i
  for i in 1 2 3; do
    CHAIN_STATE_OVERRIDE="$FAKE_CS" run env -u CLAUDE_CODE_SESSION_ID "$GATE" --repo "$no_git_fx" \
      --event completion --session "sess-nogit-1" --format text
    assert_status 0 "$status" "attempt $i must not block: refusing without a bounding mechanism is not safe, so this event fails open"
  done
}

# SUPERSEDED MECHANISM, PRESERVED CONCERN. The original test guarded a real
# Ring 8 finding: `tail -c 20000` applied AFTER extracting every string from
# the transcript dropped a genuine completion claim whenever enough other text
# followed it in the same window. That extraction no longer exists — the
# trigger is the checkpoint presentation marker — so the specific truncation
# it described is unreachable.
#
# The CONCERN behind it is not obsolete, and deleting the test would quietly
# discard it: a genuine claim must never go undetected because of what else
# the turn happens to contain. Restated against the current mechanism, and
# made stronger by it — detection must be independent of transcript content
# entirely, so the case that used to fail (a real claim buried under bulk
# output) is now asserted to pass with no claim text present at all.
@test "a genuine completion claim is detected regardless of transcript content" {
  mk_repo
  fake_chain_state "$(cs_report 2)"
  local transcript="$BATS_TEST_TMPDIR/transcript.jsonl"
  {
    # bulk output and NO claim phrasing anywhere: under the old text trigger
    # this shape is precisely what hid a real claim.
    python3 -c "import json; print(json.dumps({'type':'message','message':{'role':'assistant','content':[{'type':'tool_use','input':{'content':'x'*25000}}]}}))" 2>/dev/null \
      || printf '{"type":"message","message":{"role":"assistant","content":[{"type":"tool_use","input":{"content":"%s"}}]}}\n' "$(printf 'x%.0s' $(seq 1 25000))"
  } >"$transcript"
  claim_completion "sess-transcript-1"
  payload="$(jq -nc --arg tp "$transcript" '{stop_hook_active:false, transcript_path:$tp}')"
  run bash -c "CHAIN_STATE_OVERRIDE='$FAKE_CS' '$GATE' --repo '$FX' --event completion --session sess-transcript-1 --format text <<<'$payload'"
  assert_status 1 "$status" "the claim must be detected from the checkpoint marker, whatever the transcript holds"
}

@test "stop_hook_active always allows, bypassing refusal entirely" {
  mk_repo
  fake_chain_state "$(cs_report 3)"
  claim_completion "sess-active-1"
  CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
    --stop-hook-active true --session "sess-active-1" --format text
  assert_status 0 "$status" "stop_hook_active true means the harness is already re-invoking after a prior block"
}

@test "the completion gate respects VERIFIED_SCALA3_HOOKS=off" {
  mk_repo
  fake_chain_state "$(cs_report 3)"
  claim_completion "sess-off-1"
  run env -u CLAUDE_CODE_SESSION_ID VERIFIED_SCALA3_HOOKS=off CHAIN_STATE_OVERRIDE="$FAKE_CS" \
    "$GATE" --repo "$FX" --event completion --session "sess-off-1" --format text
  assert_status 0 "$status" "the escape hatch must disable the completion gate too"
}

# ═════════════════════════════════════════════════════════════════════════
# Properties (Ring 3) — ENUMERATED over finite listed domains.
# ═════════════════════════════════════════════════════════════════════════

# spec: hook-tiers — Property: The post-edit tier is triggered by path, exhaustively
# FOUND at Ring 8: both path classifications require a literal leading `/`
# immediately before the matched segment, so a RELATIVE file path (no
# leading slash — plausible from a harness/adapter that supplies one)
# matched neither, and silently triggered nothing at all.
@test "a relative file path still triggers the lint, normalized to absolute" {
  mk_repo
  fake_spec_lint "FAIL F7 line 1: relative path finding" 1
  cd "$FX" || return 1
  SPEC_LINT_OVERRIDE="$FAKE_SL" run_gate_post_edit --event post-edit \
    --file "openspec/changes/some-change/specs/only/spec.md" --format text
  assert_status 0 "$status" "post-edit never blocks"
  [ -f "$SL_MARKER" ] || { printf 'spec-lint was not invoked for a relative path\n' >&2; return 1; }
  assert_contains "$output" "relative path finding" "the finding must still be returned"
}

@test "PROPERTY the post-edit tier triggers by path exactly as specified" {
  # Generator strategy (enumerated, per spec): active-change spec, archived
  # spec, similarly-named template, production source, test source,
  # generated source, docs file, path with a space.
  mk_repo
  mkdir -p "$FX/openspec/schemas/verified-scala3/templates"
  mkdir -p "$FX/target/scala-3/src_managed/main"
  mkdir -p "$FX/openspec/schemas/verified-scala3/docs"
  mkdir -p "$FX/a dir with space"

  local -a paths=(
    "$FX/openspec/changes/some-change/specs/only/spec.md:sl"
    "$FX/openspec/changes/archived-change/archive/specs/only/spec.md:none"
    "$FX/openspec/schemas/verified-scala3/templates/spec.md:none"
    "$FX/src/main/scala/pkg/Foo.scala:ds"
    "$FX/src/test/scala/pkg/FooTest.scala:none"
    "$FX/target/scala-3/src_managed/main/Foo.scala:none"
    "$FX/openspec/schemas/verified-scala3/docs/09-tooling.html:none"
    "$FX/a dir with space/spec.md:none"
  )
  local entry path expect
  for entry in "${paths[@]}"; do
    path="${entry%:*}"
    expect="${entry##*:}"
    rm -f "$SL_MARKER" "$DS_MARKER"
    fake_spec_lint "" 0
    fake_danger_scan "" 0
    SPEC_LINT_OVERRIDE="$FAKE_SL" DANGER_SCAN_OVERRIDE="$FAKE_DS" \
      run_gate_post_edit --event post-edit --file "$path" --format text
    assert_status 0 "$status" "post-edit never blocks ($path)"
    case "$expect" in
      sl)
        [ -f "$SL_MARKER" ] || { printf 'expected spec-lint triggered for: %s\n' "$path" >&2; return 1; }
        [ ! -f "$DS_MARKER" ] || { printf 'expected danger-scan NOT triggered for: %s\n' "$path" >&2; return 1; }
        ;;
      ds)
        [ ! -f "$SL_MARKER" ] || { printf 'expected spec-lint NOT triggered for: %s\n' "$path" >&2; return 1; }
        [ -f "$DS_MARKER" ] || { printf 'expected danger-scan triggered for: %s\n' "$path" >&2; return 1; }
        ;;
      none)
        [ ! -f "$SL_MARKER" ] || { printf 'expected NEITHER triggered (spec-lint fired) for: %s\n' "$path" >&2; return 1; }
        [ ! -f "$DS_MARKER" ] || { printf 'expected NEITHER triggered (danger-scan fired) for: %s\n' "$path" >&2; return 1; }
        ;;
    esac
  done
}

# spec: hook-tiers — Property: The post-edit tier never alters the edited file
@test "PROPERTY the post-edit tier never alters the edited file, any path or outcome" {
  # Generator strategy (enumerated): cross product of {spec path, production
  # source path} x {clean, findings, check unavailable, check errored}.
  mk_repo
  local spec_path="$FX/openspec/changes/some-change/specs/only/spec.md"
  local prod_path="$FX/src/main/scala/pkg/Foo.scala"
  printf 'spec content\n' >"$spec_path"
  printf 'prod content\n' >"$prod_path"

  fake_spec_lint "" 0
  SPEC_LINT_OVERRIDE="$FAKE_SL" run_gate_post_edit --event post-edit --file "$spec_path" --format text
  [ "$(cat "$spec_path")" = "spec content" ] || { printf 'clean outcome altered the file\n' >&2; return 1; }

  fake_spec_lint "FAIL F7 line 1: finding" 1
  SPEC_LINT_OVERRIDE="$FAKE_SL" run_gate_post_edit --event post-edit --file "$spec_path" --format text
  [ "$(cat "$spec_path")" = "spec content" ] || { printf 'findings outcome altered the file\n' >&2; return 1; }

  SPEC_LINT_OVERRIDE="$BATS_TEST_TMPDIR/does-not-exist.sh" run_gate_post_edit --event post-edit --file "$spec_path" --format text
  [ "$(cat "$spec_path")" = "spec content" ] || { printf 'check-unavailable outcome altered the file\n' >&2; return 1; }

  cat >"$FAKE_SL" <<'EOF'
#!/usr/bin/env bash
exit 127
EOF
  chmod +x "$FAKE_SL"
  SPEC_LINT_OVERRIDE="$FAKE_SL" run_gate_post_edit --event post-edit --file "$spec_path" --format text
  [ "$(cat "$spec_path")" = "spec content" ] || { printf 'check-errored outcome altered the file\n' >&2; return 1; }

  fake_danger_scan "src/main/.../Foo.scala:1: catch-all" 1
  DANGER_SCAN_OVERRIDE="$FAKE_DS" run_gate_post_edit --event post-edit --file "$prod_path" --format text
  [ "$(cat "$prod_path")" = "prod content" ] || { printf 'production-source findings outcome altered the file\n' >&2; return 1; }
}

# spec: hook-tiers — Property: Completion is refused exactly when a claim outruns evidence
@test "PROPERTY completion is refused iff a claim is asserted and the chain is not fully discharged" {
  # Generator strategy (enumerated, per spec): cross product of turn kinds
  # {asserts completion, asserts a ring result, asserts nothing} x chain
  # states {fully discharged, partially discharged, none discharged,
  # undetermined}.
  mk_repo
  local -a turns=("$COMPLETION_CLAIM:claim" "$RING_ONLY_TEXT:ring" "$NO_CLAIM_TEXT:none")
  local ti=0
  local turn_entry turn_text turn_kind
  for turn_entry in "${turns[@]}"; do
    turn_text="${turn_entry%:*}"
    turn_kind="${turn_entry##*:}"
    local si=0
    local state_entry
    for state_entry in "full:0" "partial:2" "none:1" "undetermined:U"; do
      local state_name="${state_entry%:*}" n="${state_entry##*:}"
      ti=$((ti + 1))
      si=$((si + 1))
      if [ "$n" = "U" ]; then
        fake_chain_state "$(cs_undetermined)" 2
      else
        fake_chain_state "$(cs_report "$n")"
      fi
      # "asserts completion" is now the checkpoint marker, not the turn text.
      # The other two kinds are realised by its ABSENCE — see the note at
      # claim_completion for why they collapse to the same construction.
      [ "$turn_kind" = "claim" ] && claim_completion "sess-prop3-$ti-$si"
      CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
        --session "sess-prop3-$ti-$si" --format text
      local should_refuse=0
      if [ "$turn_kind" = "claim" ] && [ "$state_name" != "full" ]; then
        should_refuse=1
      fi
      if [ "$should_refuse" -eq 1 ]; then
        [ "$status" -ne 0 ] || {
          printf 'turn=%s state=%s: expected a refusal, got exit 0\n' "$turn_kind" "$state_name" >&2
          return 1
        }
      else
        [ "$status" -eq 0 ] || {
          printf 'turn=%s state=%s: expected proceed (exit 0), got exit %s\n' "$turn_kind" "$state_name" "$status" >&2
          return 1
        }
      fi
    done
  done
}

# spec: hook-tiers — Property: Refusal is bounded
@test "PROPERTY at most one refusal occurs across any sequence of completion attempts on one turn" {
  # Generator strategy (enumerated): attempt sequences of length 1, 2, 3, 5,
  # each against a chain state that would refuse.
  mk_repo
  fake_chain_state "$(cs_report 1)"
  local n
  for n in 1 2 3 5; do
    local refusals=0 i
    local session="sess-prop4-$n"
    claim_completion "$session"
    for i in $(seq 1 "$n"); do
      CHAIN_STATE_OVERRIDE="$FAKE_CS" run_gate --event completion \
        --session "$session" --format text
      [ "$status" -eq 0 ] || refusals=$((refusals + 1))
    done
    # FOUND while confirming RED polarity: asserting only "<= 1" is
    # vacuously true if the gate never refuses at all (0 <= 1) — which is
    # exactly the pre-implementation state, where every attempt proceeds.
    # A genuine bound requires the refusal to actually happen once, not
    # merely not happen twice.
    [ "$refusals" -eq 1 ] || {
      printf 'sequence length %s: expected EXACTLY 1 refusal (bounded, not zero), got %s\n' "$n" "$refusals" >&2
      return 1
    }
  done
}
