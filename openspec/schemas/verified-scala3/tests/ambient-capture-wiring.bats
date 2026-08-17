#!/usr/bin/env bats
#
# Oracle for the ambient-capture WIRING + RECONCILIATION fix.
#
# WHY THIS SUITE EXISTS
# tests/ambient-evidence-capture.bats drove the post-bash gate through
# explicit `--command` / `--exit` FLAGS. That tested the unit and never the
# SEAM: no adapter ever wired the event, no harness can pass those flags
# (hook command strings expand env vars only — payload fields arrive on
# stdin), and the handler had no stdin path at all. All nine obligations went
# green while the feature was connected to nothing.
#
# So this suite asserts the three things that suite could not:
#   1. the gate reads the command and the OUTCOME from the harness payload
#   2. the adapters actually wire the event (an INSTALLATION obligation —
#      the thing whose absence let the original change archive unwired)
#   3. an `append` row that no witness corroborates is DETECTABLE
#
# On (1): the outcome predicate is the delicate part. Verified first-hand
# against this project's own session transcripts — a Bash tool_response is an
# OBJECT on success (no exit field at all) and the STRING "Error: Exit code N"
# on failure. Harness refusals ("requires approval", "Path does not exist")
# are ALSO strings. Reading "string ⇒ failure" would file a permission denial
# as a RED test run — fabricated evidence of exactly the class this workflow
# exists to prevent — so those cases must record NOTHING.

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  GATE="$SCHEMA/hooks/gate.sh"
  LEDGER="$SCHEMA/scanner/ledger.sh"
  RECONCILE="$SCHEMA/scanner/reconcile.sh"
  CONTRACT="$SCHEMA/scanner/ledger-record-contract.jq"
  FX="$BATS_TEST_TMPDIR/repo"
  FAKE_CS="$BATS_TEST_TMPDIR/fake-chain-state.sh"
  FAKE_SL="$BATS_TEST_TMPDIR/fake-spec-lint.sh"
  CHANGE="add-verified-scala3-control-plane"
  SPEC="ambient-evidence-capture"
  SESSION="test-session"
}

# ── fixtures ──────────────────────────────────────────────────────────────

mk_repo() {
  mkdir -p "$FX/openspec/changes/$CHANGE/specs/$SPEC"
  mkdir -p "$FX/openspec/schemas/verified-scala3/scanner"
  cp "$LEDGER" "$FX/openspec/schemas/verified-scala3/scanner/ledger.sh"
  cp "$CONTRACT" "$FX/openspec/schemas/verified-scala3/scanner/ledger-record-contract.jq"
  printf 'placeholder\n' >"$FX/.gitignore-placeholder"
  (cd "$FX" && git init -q && git config user.email t@t && git config user.name t)
  (cd "$FX" && git add -A && git commit -q -m "initial" 2>/dev/null || true)
  BASE="$(cd "$FX" && git rev-parse --short HEAD)"
}

neutral_stubs() {
  printf '#!/usr/bin/env bash\necho %s\nexit 0\n' \
    "'{\"change\":\"none\",\"baseline\":\"0000000\",\"total\":0,\"bound\":0,\"resolved\":0,\"discharged\":0,\"unresolved\":[],\"unmapped_obligations\":[]}'" \
    >"$FAKE_CS"
  printf '#!/usr/bin/env bash\nexit 0\n' >"$FAKE_SL"
  chmod +x "$FAKE_CS" "$FAKE_SL"
}

# Run the gate feeding a harness-shaped JSON payload on STDIN — the only
# channel a real hook has. Deliberately passes NO --command / --exit.
run_gate_payload() { # $1=json payload; rest=extra args
  local payload="$1"; shift
  neutral_stubs
  run env -u CLAUDE_CODE_SESSION_ID \
    CHAIN_STATE_OVERRIDE="$FAKE_CS" \
    SPEC_LINT_OVERRIDE="$FAKE_SL" \
    VERIFIED_SCALA3_SESSION_ID="$SESSION" \
    bash -c 'printf "%s" "$1" | "$2" --repo "$3" "${@:4}"' _ \
      "$payload" "$GATE" "$FX" --event post-bash --format text "$@"
}

# A successful Bash payload: tool_response is an OBJECT, no exit field.
payload_ok() { # $1=command
  jq -cn --arg c "$1" '{
    hook_event_name:"PostToolUse", tool_name:"Bash",
    tool_input:{command:$c},
    tool_response:{stdout:"...", stderr:"", interrupted:false, isImage:false}
  }'
}

# A failing Bash payload: tool_response is the STRING "Error: Exit code N".
payload_err() { # $1=command $2=exit code
  jq -cn --arg c "$1" --arg e "$2" '{
    hook_event_name:"PostToolUse", tool_name:"Bash",
    tool_input:{command:$c},
    tool_response:("Error: Exit code " + $e)
  }'
}

# A payload whose tool_response is some OTHER error string (a harness refusal).
payload_refusal() { # $1=command $2=refusal text
  jq -cn --arg c "$1" --arg t "$2" '{
    hook_event_name:"PostToolUse", tool_name:"Bash",
    tool_input:{command:$c}, tool_response:$t
  }'
}

ledger_file() { printf '%s/openspec/changes/%s/evidence-ledger.jsonl' "$FX" "$CHANGE"; }
ledger_count() { local f; f="$(ledger_file)"; [ -f "$f" ] || { echo 0; return; }; grep -c . "$f"; }
ledger_field() { local f; f="$(ledger_file)"; [ -f "$f" ] || { echo ""; return; }; tail -1 "$f" | jq -r ".$1 // empty"; }

# Write a ledger row directly (bypassing the gate) to set up reconciliation.
put_row() { # $1=json
  local f; f="$(ledger_file)"
  mkdir -p "$(dirname "$f")"
  printf '%s\n' "$1" >>"$f"
}

row() { # --ring R --exit N [--source S] [--digest D] [--spec S]
  local ring="R3" ex=0 source="" digest="" spec="$SPEC" cmd="sbt test"
  while [ $# -gt 0 ]; do
    case "$1" in
      --ring) ring="$2"; shift 2 ;;
      --exit) ex="$2"; shift 2 ;;
      --source) source="$2"; shift 2 ;;
      --digest) digest="$2"; shift 2 ;;
      --spec) spec="$2"; shift 2 ;;
      --command) cmd="$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  jq -cn --argjson v 1 --arg ts "2026-01-01T00:00:00Z" --arg change "$CHANGE" \
    --arg spec "$spec" --arg ring "$ring" --arg ob "an obligation" \
    --arg art "tests/x.bats" --arg cmd "$cmd" --argjson ex "$ex" \
    --arg base "$BASE" --arg source "$source" --arg digest "$digest" \
    --arg session "$SESSION" '
    {v:$v, ts:$ts, change:$change, spec:$spec, ring:$ring, obligation:$ob,
     artifact:$art, command:$cmd, exit:$ex, baseline:$base}
    + (if $source != "" then {source:$source} else {} end)
    + (if $digest != "" then {digest:$digest} else {} end)
    + (if $ring == "R8" then {session:$session} else {} end)'
}

run_reconcile() {
  run bash "$RECONCILE" --file "$(ledger_file)" --change "$CHANGE" "$@"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: the gate reads the command and outcome from the harness payload
# ═════════════════════════════════════════════════════════════════════════

@test "a successful bash payload records a green row without any --exit flag" {
  mk_repo
  run_gate_payload "$(payload_ok "sbt adk4s-core/test")"
  assert_status 0 "$status" "post-bash must always exit 0"
  [ "$(ledger_count)" -ge 1 ] || { echo "no row recorded from payload" >&2; return 1; }
  [ "$(ledger_field exit)" = "0" ] || { echo "expected exit=0, got $(ledger_field exit)" >&2; return 1; }
  assert_contains "$(ledger_field command)" "sbt adk4s-core/test" "recorded command"
}

@test "a failing bash payload records the exit code parsed from the error string" {
  mk_repo
  run_gate_payload "$(payload_err "sbt adk4s-core/test" 1)"
  assert_status 0 "$status" "post-bash must always exit 0"
  [ "$(ledger_count)" -ge 1 ] || { echo "no row recorded for a red run" >&2; return 1; }
  [ "$(ledger_field exit)" = "1" ] || { echo "expected exit=1, got $(ledger_field exit)" >&2; return 1; }
}

@test "a two-digit exit code is parsed exactly, not truncated" {
  mk_repo
  run_gate_payload "$(payload_err "sbt adk4s-core/test" 13)"
  [ "$(ledger_field exit)" = "13" ] || { echo "expected exit=13, got $(ledger_field exit)" >&2; return 1; }
}

# The fabrication vector: a refusal is NOT a command outcome.
@test "a permission refusal records nothing, never a red row" {
  mk_repo
  run_gate_payload "$(payload_refusal "sbt adk4s-core/test" "Error: This command requires approval")"
  assert_status 0 "$status" "post-bash must always exit 0"
  [ "$(ledger_count)" = "0" ] || {
    echo "a harness refusal was recorded as evidence: $(tail -1 "$(ledger_file)")" >&2
    return 1
  }
}

@test "a path-does-not-exist error records nothing" {
  mk_repo
  run_gate_payload "$(payload_refusal "sbt adk4s-core/test" "Error: Path does not exist: /nope")"
  [ "$(ledger_count)" = "0" ] || { echo "a harness error was recorded as evidence" >&2; return 1; }
}

@test "an interrupted command records nothing" {
  mk_repo
  local p
  p="$(jq -cn '{hook_event_name:"PostToolUse", tool_name:"Bash",
      tool_input:{command:"sbt adk4s-core/test"},
      tool_response:{stdout:"", stderr:"", interrupted:true, isImage:false}}')"
  run_gate_payload "$p"
  assert_status 0 "$status" "post-bash must always exit 0"
  [ "$(ledger_count)" = "0" ] || { echo "an interrupted run was recorded" >&2; return 1; }
}

@test "a non-Bash tool payload records nothing" {
  mk_repo
  local p
  p="$(jq -cn '{hook_event_name:"PostToolUse", tool_name:"Edit",
      tool_input:{command:"sbt adk4s-core/test"}, tool_response:{stdout:""}}')"
  run_gate_payload "$p"
  [ "$(ledger_count)" = "0" ] || { echo "a non-Bash tool produced a row" >&2; return 1; }
}

@test "a non-matching command in a payload records nothing" {
  mk_repo
  run_gate_payload "$(payload_ok "ls -la")"
  [ "$(ledger_count)" = "0" ] || { echo "a non-ring command produced a row" >&2; return 1; }
}

# Found in live operation, not by this suite's first draft: the handler's very
# first production row claimed exit 0 for a bats run with eight failures,
# because the command was piped into grep and the shell reported grep's status.
@test "a piped ring command records nothing (the exit is not the ring's)" {
  mk_repo
  run_gate_payload "$(payload_ok 'bats tests/ | grep -E "^not ok"')"
  assert_status 0 "$status" "post-bash must always exit 0"
  [ "$(ledger_count)" = "0" ] || {
    echo "a pipeline's exit was recorded as the ring's: $(tail -1 "$(ledger_file)")" >&2
    return 1
  }
}

@test "a chained ring command records nothing" {
  mk_repo
  run_gate_payload "$(payload_ok 'sbt adk4s-core/test; echo done')"
  [ "$(ledger_count)" = "0" ] || { echo "a chained command was recorded" >&2; return 1; }
}

@test "a redirected ring command is still recorded (redirection preserves the exit)" {
  mk_repo
  run_gate_payload "$(payload_ok 'sbt adk4s-core/test >/tmp/log 2>/tmp/err')"
  [ "$(ledger_count)" -ge 1 ] || { echo "a plain redirection must not disqualify a run" >&2; return 1; }
  [ "$(ledger_field exit)" = "0" ] || { echo "expected exit=0" >&2; return 1; }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: ambient rows are usable by the machinery that consumes them
# ═════════════════════════════════════════════════════════════════════════

# The oracle-ordering lock advances phase ONLY on .ring == "R3". An ambient
# test row filed as R0 could never advance it — the capture would be inert
# for the one gate it most needs to feed.
@test "an ambient test row is filed as R3 so it can advance the oracle phase" {
  mk_repo
  run_gate_payload "$(payload_ok "sbt adk4s-core/test")"
  [ "$(ledger_field ring)" = "R3" ] || {
    echo "expected ring=R3 for a test run, got $(ledger_field ring)" >&2; return 1; }
}

@test "an ambient row is marked as ambient so a witness is distinguishable" {
  mk_repo
  run_gate_payload "$(payload_ok "sbt adk4s-core/test")"
  [ "$(ledger_field source)" = "ambient" ] || {
    echo "expected source=ambient, got '$(ledger_field source)'" >&2; return 1; }
}

@test "an ambient row still satisfies the record contract" {
  mk_repo
  run_gate_payload "$(payload_ok "sbt adk4s-core/test")"
  # The row must EXIST before it is checked. `jq -e` is vacuously true on
  # empty input, so piping an absent row into the contract passes without
  # evaluating a single clause — this assertion graded itself green while the
  # capture path was recording nothing at all.
  [ "$(ledger_count)" -ge 1 ] || { echo "no row to check against the contract" >&2; return 1; }
  tail -1 "$(ledger_file)" | jq -e -f "$CONTRACT" >/dev/null 2>&1 || {
    echo "ambient row violates the contract:" >&2; tail -1 "$(ledger_file)" >&2; return 1; }
}

@test "the contract rejects an unknown source value" {
  mk_repo
  run bash -c "printf '%s' '$(row --source invented)' | jq -e -f '$CONTRACT'"
  [ "$status" -ne 0 ] || { echo "contract accepted an unknown source value" >&2; return 1; }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: the adapters wire the event (the INSTALLATION obligation)
# ═════════════════════════════════════════════════════════════════════════

@test "the claude adapter wires a Bash PostToolUse hook to post-bash" {
  local f="$SCHEMA/hooks/adapters/claude.settings.json"
  run jq -e '[.hooks.PostToolUse[]
    | select(.matcher | test("Bash"))
    | .hooks[] | select(.command | test("--event post-bash"))] | length > 0' "$f"
  assert_status 0 "$status" "claude adapter has no Bash -> post-bash PostToolUse entry"
}

@test "the devin adapter wires a Bash PostToolUse hook to post-bash" {
  local f="$SCHEMA/hooks/adapters/devin.hooks.v1.json"
  run jq -e '[.PostToolUse[] | .hooks[]
    | select(.command | test("--event post-bash"))] | length > 0' "$f"
  assert_status 0 "$status" "devin adapter has no post-bash PostToolUse entry"
}

# The post-edit/tool-call/completion handlers re-read stdin AFTER the repo
# probe already consumed it. With no --repo (which is exactly how every
# adapter invokes the gate) the second read returns EOF, so the file path is
# empty and the handler silently does nothing. Every existing test passes
# --repo, which is why the whole tier read as working.
@test "the file path survives the repo probe when --repo is absent" {
  mk_repo
  mkdir -p "$FX/mod/src/main/scala"
  printf 'object A\n' >"$FX/mod/src/main/scala/A.scala"
  neutral_stubs
  local p
  p="$(jq -cn --arg cwd "$FX" --arg f "$FX/mod/src/main/scala/A.scala" \
    '{hook_event_name:"PreToolUse", cwd:$cwd, tool_name:"Edit",
      tool_input:{file_path:$f}}')"
  # No --repo: the repo must come from the payload AND the file path must
  # still be readable by the tool-call handler from the same payload.
  run env -u CLAUDE_CODE_SESSION_ID VERIFIED_SCALA3_SESSION_ID="$SESSION" \
    CHAIN_STATE_OVERRIDE="$FAKE_CS" SPEC_LINT_OVERRIDE="$FAKE_SL" \
    bash -c 'printf "%s" "$1" | "$2" --event tool-call --format text' _ "$p" "$GATE"
  # The oracle phase is the default, so a production edit must be REFUSED.
  # An empty file path would make it fall through and allow.
  [ "$status" -eq 2 ] || {
    echo "expected a refusal (exit 2) for a production edit in the oracle phase, got $status" >&2
    echo "output: $output" >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: an uncorroborated append is detectable
# ═════════════════════════════════════════════════════════════════════════

@test "a bare append green row with no witness is reported as testimony" {
  mk_repo
  put_row "$(row --ring R3 --exit 0)"
  run_reconcile
  [ "$status" -eq 1 ] || { echo "expected exit 1 (finding), got $status: $output" >&2; return 1; }
  assert_contains "$output" "testimony" "reconcile output"
}

@test "a bare append green row with a matching ambient witness passes" {
  mk_repo
  put_row "$(row --ring R3 --exit 0)"
  put_row "$(row --ring R3 --exit 0 --source ambient)"
  run_reconcile
  assert_status 0 "$status" "a witnessed claim must reconcile clean"
}

@test "a run-produced row is self-witnessed and needs no ambient row" {
  mk_repo
  put_row "$(row --ring R3 --exit 0 --digest abc123)"
  run_reconcile
  assert_status 0 "$status" "a run-mode row observes its own exit"
}

@test "an ambient witness that contradicts the claimed exit is a finding" {
  mk_repo
  put_row "$(row --ring R3 --exit 0)"
  put_row "$(row --ring R3 --exit 1 --source ambient)"
  run_reconcile
  [ "$status" -eq 1 ] || { echo "expected exit 1 for a contradicted claim, got $status" >&2; return 1; }
  assert_contains "$output" "contradicted" "reconcile output"
}

# Judgment rings have no deterministic command to witness — ledger.sh verify
# already holds them to naming a report artifact instead.
@test "a judgment ring row is exempt from corroboration" {
  mk_repo
  put_row "$(row --ring R8 --exit 0)"
  run_reconcile
  assert_status 0 "$status" "R8 must not require an ambient witness"
}

@test "a red claim needs no witness (it discharges nothing)" {
  mk_repo
  put_row "$(row --ring R3 --exit 1)"
  run_reconcile
  assert_status 0 "$status" "a red row claims no discharge"
}

@test "an absent ledger is undetermined, never a clean pass" {
  mk_repo
  run_reconcile
  [ "$status" -eq 2 ] || { echo "expected exit 2 (undetermined) for an absent ledger, got $status" >&2; return 1; }
}

@test "a corrupt ledger is undetermined, never a clean pass" {
  mk_repo
  put_row 'this is not json'
  run_reconcile
  [ "$status" -eq 2 ] || { echo "expected exit 2 for a corrupt ledger, got $status" >&2; return 1; }
}

# install-hooks.sh is the documented install path. Merging only SessionStart
# meant it could report success while wiring none of the tiers that enforce
# anything — the same "installed but inert" shape as the unwired event itself.
@test "install-hooks wires every event the adapter declares" {
  local proj="$BATS_TEST_TMPDIR/proj"
  mkdir -p "$proj/openspec" "$proj/.claude"
  printf '{}\n' >"$proj/.claude/settings.json"
  run bash "$SCHEMA/hooks/install-hooks.sh" --agent claude --apply --project "$proj"
  assert_status 0 "$status" "install-hooks --apply"
  local declared installed
  declared="$(jq -r '[.hooks[][].hooks[].command] | sort | join("\n")' \
    "$SCHEMA/hooks/adapters/claude.settings.json")"
  installed="$(jq -r '[.hooks[][].hooks[].command] | sort | join("\n")' \
    "$proj/.claude/settings.json")"
  [ "$declared" = "$installed" ] || {
    printf 'adapter declares:\n%s\n\ninstalled:\n%s\n' "$declared" "$installed" >&2
    return 1
  }
}

@test "install-hooks is idempotent across repeated applies" {
  local proj="$BATS_TEST_TMPDIR/proj2"
  mkdir -p "$proj/openspec" "$proj/.claude"
  printf '{}\n' >"$proj/.claude/settings.json"
  bash "$SCHEMA/hooks/install-hooks.sh" --agent claude --apply --project "$proj" >/dev/null
  local first
  first="$(jq -S . "$proj/.claude/settings.json")"
  bash "$SCHEMA/hooks/install-hooks.sh" --agent claude --apply --project "$proj" >/dev/null
  [ "$first" = "$(jq -S . "$proj/.claude/settings.json")" ] || {
    echo "a second apply changed the settings file" >&2; return 1; }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: the completion gate acts on an uncorroborated ledger
# ═════════════════════════════════════════════════════════════════════════

# Drives the completion event with a checkpoint presentation marker in place
# (the deterministic trigger), so the gate actually evaluates the chain.
run_completion() {
  neutral_stubs
  local gitdir state
  gitdir="$(cd "$FX" && git rev-parse --absolute-git-dir)"
  state="$gitdir/verified-scala3-gate"
  mkdir -p "$state"
  local encoded
  encoded="$(printf '%s' "$SESSION" | jq -Rr '@base64' | tr '+/=' '-_.')"
  printf 'deadbeef' >"$state/presentation-$CHANGE-$SPEC-$encoded"
  rm -f "$state/completion-refused-$encoded"
  run env -u CLAUDE_CODE_SESSION_ID \
    CHAIN_STATE_OVERRIDE="$FAKE_CS" SPEC_LINT_OVERRIDE="$FAKE_SL" \
    VERIFIED_SCALA3_SESSION_ID="$SESSION" \
    "$GATE" --repo "$FX" --event completion --format text --stop-hook-active false
}

@test "completion is refused when a green row has no witness" {
  mk_repo
  put_row "$(row --ring R3 --exit 0)"
  run_completion
  [ "$status" -eq 1 ] || { echo "expected a refusal, got $status: $output" >&2; return 1; }
  assert_contains "$output" "uncorroborated" "refusal reason"
}

@test "completion proceeds when every green row is witnessed" {
  mk_repo
  put_row "$(row --ring R3 --exit 0)"
  put_row "$(row --ring R3 --exit 0 --source ambient)"
  run_completion
  assert_status 0 "$status" "a witnessed ledger must not be refused"
}

# Ambient rows are attributed to an INFERRED change/spec. A test run for one
# spec can land under another, so a witness keyed only on (spec, ring,
# baseline) would corroborate a claim it never watched.
@test "a witness for a different command does not corroborate the claim" {
  mk_repo
  put_row "$(row --ring R3 --exit 0 --command "sbt adk4s-core/test")"
  put_row "$(row --ring R3 --exit 0 --source ambient --command "bats tests/")"
  run_reconcile
  [ "$status" -eq 1 ] || {
    echo "an unrelated command corroborated the claim, got $status" >&2; return 1; }
  assert_contains "$output" "testimony" "reconcile output"
}

@test "the witness must match the claim's baseline" {
  mk_repo
  put_row "$(row --ring R3 --exit 0)"
  # An ambient row at a different baseline is not a witness for this claim.
  put_row "$(jq -c '.baseline = "0000000" | .source = "ambient"' <<<"$(row --ring R3 --exit 0)")"
  run_reconcile
  [ "$status" -eq 1 ] || { echo "a stale witness must not corroborate, got $status" >&2; return 1; }
}
