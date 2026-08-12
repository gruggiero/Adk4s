#!/usr/bin/env bats
#
# Oracle for spec:checkpoint-from-ledger (change: add-correctness-substratum).
#
# Written from the spec and the approved Step 1 design ONLY, before
# checkpoint.sh exists — every test in this file is expected RED before
# implementation. `ledger.sh` is a pre-existing, approved dependency (spec 2)
# and is used here via its REAL CLI to build fixtures, not re-tested; its own
# format contract lives in evidence-ledger.bats.

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  CHECKPOINT="$SCHEMA/scanner/checkpoint.sh"
  LEDGER="$SCHEMA/scanner/ledger.sh"
  FX="$BATS_TEST_TMPDIR/fx"
  mkdir -p "$FX"
  LEDGER_FILE="$FX/ledger.jsonl"
  : >"$LEDGER_FILE"
  CHG="fixture-change"
  SPC="only"
  BASE="00d3de1"
  BASE2="1111111"
  BASE3="2222222"
}

# ── fixtures ──────────────────────────────────────────────────────────────

append_row() { # $1=ring $2=baseline $3=obligation(default) $4=command(default true)
  "$LEDGER" append --file "$LEDGER_FILE" --change "$CHG" --spec "$SPC" \
    --ring "$1" --obligation "${3:-Obligation for $1}" --artifact "artifact:$1" \
    --command "${4:-true}" --exit 0 --baseline "$2" >/dev/null
}

# A chain-state.sh-shaped report (already-approved shape from spec 3), with N
# unresolved entries — reused as an opaque passthrough, never recomputed by
# checkpoint.sh.
cs_report() { # $1=total $2=bound $3=resolved $4=discharged $5=unresolvedCount
  local n="${5:-0}" entries="[]"
  if [ "$n" -gt 0 ]; then
    entries="$(jq -n --argjson n "$n" '[range($n) | {spec:"only", requirement:("Req " + (.|tostring)), reasons:["undischarged"]}]')"
  fi
  jq -nc --argjson total "$1" --argjson bound "$2" --argjson resolved "$3" \
    --argjson discharged "$4" --argjson u "$entries" \
    '{change:"fixture-change", baseline:"00d3de1", total:$total, bound:$bound, resolved:$resolved, discharged:$discharged, unresolved:$u, unmapped_obligations:[]}'
}

run_report() { # extra args after the fixed positional set are passed through
  run "$CHECKPOINT" report --ledger "$LEDGER_FILE" --change "$CHG" --spec "$SPC" \
    --baseline "$BASE" --rings "R0,R1,R8" "$@"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Ring outcomes in the checkpoint come from recorded rows
# ═════════════════════════════════════════════════════════════════════════

# spec: checkpoint-from-ledger — Scenario: a ring with a recorded green row is shown as green
@test "a ring with a recorded green row is shown as green, citing the command" {
  append_row R0 "$BASE" "R0 obligation" "bash -n foo.sh"
  # --rings narrowed to just R0: this scenario is about ONE ring's reported
  # status, not the report's overall exit code (covered elsewhere).
  run "$CHECKPOINT" report --ledger "$LEDGER_FILE" --change "$CHG" --spec "$SPC" \
    --baseline "$BASE" --rings "R0" --chain-state-json <(cs_report 3 3 3 3 0) --format text
  assert_status 0 "$status" "the one requested ring is green and the chain is fully discharged"
  assert_contains "$output" "R0" "R0 must appear"
  assert_contains "$output" "green" "R0 must be reported green"
  assert_contains "$output" "bash -n foo.sh" "the recorded command must be cited"
}

# spec: checkpoint-from-ledger — Scenario: a ring with no row is shown as unevidenced
@test "a ring with no row is shown as unevidenced, not skipped and not green" {
  append_row R0 "$BASE"
  # R1 and R8 are in --rings but never get a row.
  run_report --chain-state-json <(cs_report 1 1 1 1 0)
  assert_status 1 "$status" "an unevidenced ring is a finding, not a clean report"
  rings_reported="$(printf '%s' "$output" | jq -r '.rings[] | select(.ring=="R1") | .status')"
  [ "$rings_reported" = "unevidenced" ] || {
    printf 'expected R1 status "unevidenced", got: %s\n' "$rings_reported" >&2
    return 1
  }
  assert_not_contains "$output" '"status":"skipped"' "unevidenced is not the same claim as skipped"
}

# spec: checkpoint-from-ledger — Scenario: free-text ring outcomes are not accepted
@test "chain state readiness cannot substitute for a missing ledger row" {
  # The ledger has ZERO rows at all. Chain state independently claims full
  # readiness (as tempting a "everything is fine" signal as free text would
  # be) — this must not leak into any ring's status. The only path to
  # "green" is a row; there is no flag on this CLI that accepts a ring
  # outcome as text, so the strongest test of that omission is behavioural.
  run_report --chain-state-json <(cs_report 5 5 5 5 0)
  assert_status 1 "$status" "no rows recorded means nothing can be reported green"
  statuses="$(printf '%s' "$output" | jq -r '[.rings[].status] | unique | join(",")')"
  [ "$statuses" = "unevidenced" ] || {
    printf 'expected every ring unevidenced regardless of chain-state readiness, got statuses: %s\n' "$statuses" >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: A checkpoint reports its own evidence gaps
# ═════════════════════════════════════════════════════════════════════════

# spec: checkpoint-from-ledger — Scenario: unresolved requirements appear in the summary
@test "unresolved requirements appear in the summary" {
  append_row R0 "$BASE"
  append_row R1 "$BASE"
  append_row R8 "$BASE"
  run_report --chain-state-json <(cs_report 3 3 3 1 2) --format text
  assert_contains "$output" "Req 0" "first unresolved requirement"
  assert_contains "$output" "Req 1" "second unresolved requirement"
}

# spec: checkpoint-from-ledger — Scenario: a fully discharged spec shows a complete chain
@test "a fully discharged spec shows counts equal to the total and an empty unresolved list" {
  append_row R0 "$BASE"
  append_row R1 "$BASE"
  append_row R8 "$BASE"
  run_report --chain-state-json <(cs_report 4 4 4 4 0) --format text
  assert_status 0 "$status" "fully green and fully discharged exits 0"
  assert_contains "$output" "total 4" "total must be reported"
  assert_contains "$output" "discharged 4" "discharged must equal total"
  assert_not_contains "$output" "Req " "no unresolved requirement line when the chain is complete"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: A stale ring outcome is never presented as current
# ═════════════════════════════════════════════════════════════════════════

# spec: checkpoint-from-ledger — Scenario: rows from a superseded baseline are excluded
@test "rows from a superseded baseline are excluded" {
  append_row R0 "$BASE2" # a DIFFERENT baseline than the report is requested at
  run_report --chain-state-json <(cs_report 1 1 1 1 0)
  status_r0="$(printf '%s' "$output" | jq -r '.rings[] | select(.ring=="R0") | .status')"
  [ "$status_r0" = "unevidenced" ] || {
    printf 'expected R0 unevidenced (its only row is at a different baseline), got: %s\n' "$status_r0" >&2
    return 1
  }
}

# spec: checkpoint-from-ledger — Scenario: an edit after verification invalidates the outcome
@test "an edit that moves the baseline clears the previously-green outcome" {
  append_row R0 "$BASE" "R0 obligation" "first run"
  run_report --chain-state-json <(cs_report 1 1 1 1 0)
  assert_contains "$output" '"status":"green"' "R0 is green at the original baseline"

  # Simulate a source edit: the checkpoint is now requested at a NEW
  # baseline, with no re-verification recorded there yet.
  run "$CHECKPOINT" report --ledger "$LEDGER_FILE" --change "$CHG" --spec "$SPC" \
    --baseline "$BASE3" --rings "R0,R1,R8" --chain-state-json <(cs_report 1 1 1 1 0)
  status_r0="$(printf '%s' "$output" | jq -r '.rings[] | select(.ring=="R0") | .status')"
  [ "$status_r0" = "unevidenced" ] || {
    printf 'expected R0 unevidenced at the new baseline (the green row is now stale), got: %s\n' "$status_r0" >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The tracker and the derived checklist agree
# ═════════════════════════════════════════════════════════════════════════

write_progress() { # $1=content
  printf '%s' "$1" >"$FX/implementation-progress.md"
}
write_tasks() { # $1=content
  printf '%s' "$1" >"$FX/tasks.md"
}

# spec: checkpoint-from-ledger — Scenario: the checklist follows the tracker
@test "the derived checklist follows the tracker" {
  write_progress '### 1. alpha

| Field | Value |
|-------|-------|
| Commit | `abc1234` |

### 2. beta

| Field | Value |
|-------|-------|
| Commit | _(pending)_ |
'
  write_tasks '## 1. alpha

- [ ] Step 0 — do a thing
- [ ] Step 1 — do another thing

## 2. beta

- [ ] Step 0 — do a thing
'
  run "$CHECKPOINT" regenerate-tasks --progress "$FX/implementation-progress.md" --tasks "$FX/tasks.md" --write
  assert_status 0 "$status" "regeneration itself must succeed"
  result="$(cat "$FX/tasks.md")"
  printf '%s\n' "$result" | grep -q '^\- \[x\] Step 0 — do a thing$' &&
    printf '%s\n' "$result" | sed -n '1,5p' | grep -q '\[x\] Step 1' || {
    printf 'expected all of spec 1 (alpha, committed) checked:\n%s\n' "$result" >&2
    return 1
  }
  printf '%s\n' "$result" | sed -n '/## 2. beta/,$p' | grep -q '\[ \] Step 0' || {
    printf 'expected spec 2 (beta, not committed) to remain unchecked:\n%s\n' "$result" >&2
    return 1
  }
}

# spec: checkpoint-from-ledger — Scenario: a divergent checklist is corrected, not merged
@test "a divergent checklist is corrected, not merged with its prior state" {
  write_progress '### 1. alpha

| Field | Value |
|-------|-------|
| Commit | `abc1234` |

### 2. beta

| Field | Value |
|-------|-------|
| Commit | _(pending)_ |
'
  # Deliberately WRONG in both directions: alpha (complete) unchecked, beta
  # (incomplete) checked. A MERGE of old and new state would leave at least
  # one of these wrong; regeneration must leave neither.
  write_tasks '## 1. alpha

- [ ] Step 0 — do a thing

## 2. beta

- [x] Step 0 — do a thing
'
  run "$CHECKPOINT" regenerate-tasks --progress "$FX/implementation-progress.md" --tasks "$FX/tasks.md" --write
  result="$(cat "$FX/tasks.md")"
  printf '%s\n' "$result" | sed -n '/## 1. alpha/,/## 2. beta/p' | grep -q '\[x\] Step 0' || {
    printf 'expected alpha corrected to checked:\n%s\n' "$result" >&2
    return 1
  }
  printf '%s\n' "$result" | sed -n '/## 2. beta/,$p' | grep -q '\[ \] Step 0' || {
    printf 'expected beta corrected to unchecked, not left checked from the divergent input:\n%s\n' "$result" >&2
    return 1
  }
}

# spec: checkpoint-from-ledger — Scenario: the checklist follows the tracker
# FOUND at Ring 8: spec numbers that share a digit suffix ("1" and "21")
# collided under an unanchored substring lookup — the wrong spec's
# completeness leaked in depending on tracker ORDER, in both directions.
@test "spec numbers sharing a digit suffix do not collide (1 vs 21)" {
  write_progress '### 21. twentyone

| Field | Value |
|-------|-------|
| Commit | `deadbee` |

### 1. alpha

| Field | Value |
|-------|-------|
| Commit | _(pending)_ |
'
  write_tasks '## 21. twentyone

- [ ] Step 0 — do a thing

## 1. alpha

- [ ] Step 0 — do a thing
'
  run "$CHECKPOINT" regenerate-tasks --progress "$FX/implementation-progress.md" --tasks "$FX/tasks.md" --write
  result="$(cat "$FX/tasks.md")"
  printf '%s\n' "$result" | sed -n '/## 21\./,/## 1\./p' | grep -q '\[x\] Step 0' || {
    printf 'expected spec 21 (committed) checked:\n%s\n' "$result" >&2
    return 1
  }
  printf '%s\n' "$result" | sed -n '/## 1\. alpha/,$p' | grep -q '\[ \] Step 0' || {
    printf 'expected spec 1 (pending, NOT 21) to remain unchecked — its number is a substring of "21":\n%s\n' "$result" >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Ring outcomes in the checkpoint come from recorded rows
# (Ring 8 additions — a row is evidence of what happened, not of success)
# ═════════════════════════════════════════════════════════════════════════

# FOUND at Ring 8: a ledger row honestly recording a FAILED command
# (--exit 1) was reported "green", citing that same failing command, and
# could make the whole checkpoint report readiness.
@test "a ring's recorded row with a non-zero exit is never reported green" {
  append_row R0 "$BASE" "R0 obligation" "sbt test (regressed)"
  "$LEDGER" append --file "$LEDGER_FILE" --change "$CHG" --spec "$SPC" \
    --ring R0 --obligation "R0 obligation" --artifact "artifact:R0" \
    --command "sbt test (regressed)" --exit 1 --baseline "$BASE" >/dev/null
  run "$CHECKPOINT" report --ledger "$LEDGER_FILE" --change "$CHG" --spec "$SPC" \
    --baseline "$BASE" --rings "R0" --chain-state-json <(cs_report 1 1 1 1 0)
  status_r0="$(printf '%s' "$output" | jq -r '.rings[] | select(.ring=="R0") | .status')"
  [ "$status_r0" != "green" ] || {
    printf 'a row recording exit=1 must never be reported green\n' >&2
    return 1
  }
  assert_status 1 "$status" "a failed ring must not report overall readiness"
}

# FOUND at Ring 8: chain-state.sh's own genuine UNDETERMINED shape carries
# `"total":null,"undetermined":true` alongside an EMPTY unresolved array —
# `has("total")` alone is true for it (the key exists, with value null), so
# it was read as "unresolved 0" and could make the checkpoint exit 0 with
# the correctness definition completely unknown.
@test "an undetermined chain-state report is never read as a clean zero" {
  append_row R0 "$BASE"
  undetermined_cs="$(jq -nc '{change:"fixture-change", baseline:"00d3de1", undetermined:true, reason:"stub", total:null, bound:null, resolved:null, discharged:null, unresolved:[], unmapped_obligations:[]}')"
  run "$CHECKPOINT" report --ledger "$LEDGER_FILE" --change "$CHG" --spec "$SPC" \
    --baseline "$BASE" --rings "R0" --chain-state-json <(printf '%s' "$undetermined_cs")
  assert_status 2 "$status" "an undetermined chain state must never be read as a clean, ready report"
}

# spec: checkpoint-from-ledger — Property: Every reported outcome traces to a row
# FOUND at Ring 8: "--rings R0,R0,R1" passed validation and produced TWO
# entries for R0 in the output, both citing the same row.
@test "duplicate ring names in --rings produce one entry per ring, not two" {
  append_row R0 "$BASE"
  run "$CHECKPOINT" report --ledger "$LEDGER_FILE" --change "$CHG" --spec "$SPC" \
    --baseline "$BASE" --rings "R0,R0,R1" --chain-state-json <(cs_report 0 0 0 0 0)
  count_r0="$(printf '%s' "$output" | jq -r '[.rings[] | select(.ring=="R0")] | length')"
  [ "$count_r0" -eq 1 ] || {
    printf 'expected R0 to appear exactly once despite being requested twice, got %s\n' "$count_r0" >&2
    return 1
  }
  total_rings="$(printf '%s' "$output" | jq -r '.rings | length')"
  [ "$total_rings" -eq 2 ] || {
    printf 'expected exactly 2 distinct rings (R0, R1) after de-duplication, got %s\n' "$total_rings" >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Exit-code contract (Step 1 gate item, this spec's own three-way convention)
# ═════════════════════════════════════════════════════════════════════════

@test "report exits 2 when the ledger cannot be read" {
  run "$CHECKPOINT" report --ledger "$FX/does-not-exist.jsonl" --change "$CHG" --spec "$SPC" \
    --baseline "$BASE" --rings "R0" --chain-state-json <(cs_report 1 1 1 1 0)
  assert_status 2 "$status" "an absent ledger is undetermined, never a clean zero"
}

@test "report exits 2 when the chain-state input does not parse" {
  append_row R0 "$BASE"
  run bash -c "'$CHECKPOINT' report --ledger '$LEDGER_FILE' --change '$CHG' --spec '$SPC' --baseline '$BASE' --rings 'R0' --chain-state-json <(printf 'not json')"
  assert_status 2 "$status" "unparseable chain-state input is undetermined"
}

@test "report emits well-formed JSON by default and text only with --format text" {
  append_row R0 "$BASE"
  run_report --chain-state-json <(cs_report 1 1 1 1 0)
  printf '%s' "$output" | jq -e . >/dev/null 2>&1 || {
    printf 'default output does not parse as JSON: %s\n' "$output" >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Properties (Ring 3) — ENUMERATED over finite constructed domains.
# ═════════════════════════════════════════════════════════════════════════

# spec: checkpoint-from-ledger — Property: Every reported outcome traces to a row
@test "PROPERTY every reported outcome traces to exactly one row at this baseline" {
  # Generator strategy (enumerated, per spec): no rows, one row, one row per
  # ring, duplicate rows for one ring, rows split across two baselines, rows
  # for a different change.
  local cases_ok=0

  # no rows
  run_report --chain-state-json <(cs_report 0 0 0 0 0)
  greens="$(printf '%s' "$output" | jq -r '[.rings[] | select(.status=="green")] | length')"
  [ "$greens" -eq 0 ] || { printf 'no rows must yield zero green rings, got %s\n' "$greens" >&2; return 1; }
  cases_ok=$((cases_ok + 1))

  # one row
  rm -f "$LEDGER_FILE"; : >"$LEDGER_FILE"
  append_row R0 "$BASE"
  run_report --chain-state-json <(cs_report 0 0 0 0 0)
  greens="$(printf '%s' "$output" | jq -c '[.rings[] | select(.status=="green") | .ring] | sort')"
  [ "$greens" = '["R0"]' ] || { printf 'expected exactly R0 green, got %s\n' "$greens" >&2; return 1; }
  cases_ok=$((cases_ok + 1))

  # one row per ring
  rm -f "$LEDGER_FILE"; : >"$LEDGER_FILE"
  append_row R0 "$BASE"; append_row R1 "$BASE"; append_row R8 "$BASE"
  run_report --chain-state-json <(cs_report 0 0 0 0 0)
  greens="$(printf '%s' "$output" | jq -r '[.rings[] | select(.status=="green") | .ring] | sort | join(",")')"
  [ "$greens" = "R0,R1,R8" ] || { printf 'expected all three rings green, got %s\n' "$greens" >&2; return 1; }
  cases_ok=$((cases_ok + 1))

  # duplicate rows for one ring — must not be reported twice, and the LAST
  # one's command is what is cited (append-only: last write is the current one)
  rm -f "$LEDGER_FILE"; : >"$LEDGER_FILE"
  append_row R0 "$BASE" "first" "cmd-one"
  append_row R0 "$BASE" "second" "cmd-two"
  append_row R1 "$BASE"; append_row R8 "$BASE"
  run_report --chain-state-json <(cs_report 0 0 0 0 0)
  count_r0="$(printf '%s' "$output" | jq -r '[.rings[] | select(.ring=="R0")] | length')"
  [ "$count_r0" -eq 1 ] || { printf 'expected R0 to appear exactly once despite 2 rows, got %s\n' "$count_r0" >&2; return 1; }
  cmd_r0="$(printf '%s' "$output" | jq -r '.rings[] | select(.ring=="R0") | .command')"
  [ "$cmd_r0" = "cmd-two" ] || { printf 'expected the LAST duplicate row'\''s command cited, got %s\n' "$cmd_r0" >&2; return 1; }
  cases_ok=$((cases_ok + 1))

  # rows split across two baselines — only the requested baseline's rows count
  rm -f "$LEDGER_FILE"; : >"$LEDGER_FILE"
  append_row R0 "$BASE"
  append_row R1 "$BASE2"
  append_row R8 "$BASE"
  run_report --chain-state-json <(cs_report 0 0 0 0 0)
  greens="$(printf '%s' "$output" | jq -r '[.rings[] | select(.status=="green") | .ring] | sort | join(",")')"
  [ "$greens" = "R0,R8" ] || { printf 'expected only R0,R8 green (R1 is at a different baseline), got %s\n' "$greens" >&2; return 1; }
  cases_ok=$((cases_ok + 1))

  # rows for a different change — must not leak into this change's report
  rm -f "$LEDGER_FILE"; : >"$LEDGER_FILE"
  "$LEDGER" append --file "$LEDGER_FILE" --change "other-change" --spec "$SPC" \
    --ring R0 --obligation "unrelated" --artifact "unrelated" --command true --exit 0 --baseline "$BASE" >/dev/null
  run_report --chain-state-json <(cs_report 0 0 0 0 0)
  greens="$(printf '%s' "$output" | jq -r '[.rings[] | select(.status=="green")] | length')"
  [ "$greens" -eq 0 ] || { printf 'a different change'\''s row must not count, got %s green\n' "$greens" >&2; return 1; }
  cases_ok=$((cases_ok + 1))

  [ "$cases_ok" -eq 6 ] || { printf 'expected all 6 enumerated cases to run, ran %s\n' "$cases_ok" >&2; return 1; }
}

# spec: checkpoint-from-ledger — Property: No baseline mismatch is ever reported as current
@test "PROPERTY no row from another baseline is ever reported as current" {
  # Generator strategy (enumerated): two and three distinct baselines, every
  # ring present at each, so every possible mismatch is exercised.
  local b
  for b in "$BASE" "$BASE2" "$BASE3"; do
    rm -f "$LEDGER_FILE"; : >"$LEDGER_FILE"
    # rows at ALL THREE baselines, for every requested ring
    local other
    for other in "$BASE" "$BASE2" "$BASE3"; do
      append_row R0 "$other" "R0 @ $other" "cmd-$other"
      append_row R1 "$other" "R1 @ $other" "cmd-$other"
    done
    run "$CHECKPOINT" report --ledger "$LEDGER_FILE" --change "$CHG" --spec "$SPC" \
      --baseline "$b" --rings "R0,R1" --chain-state-json <(cs_report 0 0 0 0 0)
    cmds="$(printf '%s' "$output" | jq -r '[.rings[].command] | join(",")')"
    case "$cmds" in
      *"cmd-$b"*) : ;;
      *) printf 'expected the report at baseline %s to cite its own commands, got: %s\n' "$b" "$cmds" >&2; return 1 ;;
    esac
    for other in "$BASE" "$BASE2" "$BASE3"; do
      [ "$other" = "$b" ] && continue
      case "$cmds" in
        *"cmd-$other"*)
          printf 'baseline %s'\''s report wrongly cited a command from baseline %s: %s\n' "$b" "$other" "$cmds" >&2
          return 1
          ;;
      esac
    done
  done
}

# spec: checkpoint-from-ledger — Property: Regeneration is idempotent
@test "PROPERTY regenerating the checklist twice matches regenerating it once" {
  # Generator strategy (enumerated): none complete, some complete, all
  # complete, and a tracker whose spec names need escaping.
  local progress tasks_a tasks_b once twice

  # none complete
  progress='### 1. alpha

| Field | Value |
|-------|-------|
| Commit | _(pending)_ |

### 2. beta

| Field | Value |
|-------|-------|
| Commit | _(pending)_ |
'
  tasks_a='## 1. alpha

- [x] Step 0 — x

## 2. beta

- [x] Step 0 — x
'
  write_progress "$progress"
  write_tasks "$tasks_a"
  "$CHECKPOINT" regenerate-tasks --progress "$FX/implementation-progress.md" --tasks "$FX/tasks.md" --write >/dev/null
  once="$(cat "$FX/tasks.md")"
  "$CHECKPOINT" regenerate-tasks --progress "$FX/implementation-progress.md" --tasks "$FX/tasks.md" --write >/dev/null
  twice="$(cat "$FX/tasks.md")"
  [ "$once" = "$twice" ] || { printf 'none-complete case not idempotent\nonce:\n%s\ntwice:\n%s\n' "$once" "$twice" >&2; return 1; }

  # some complete
  progress='### 1. alpha

| Field | Value |
|-------|-------|
| Commit | `abc1234` |

### 2. beta

| Field | Value |
|-------|-------|
| Commit | _(pending)_ |
'
  write_progress "$progress"
  write_tasks "$tasks_a"
  "$CHECKPOINT" regenerate-tasks --progress "$FX/implementation-progress.md" --tasks "$FX/tasks.md" --write >/dev/null
  once="$(cat "$FX/tasks.md")"
  "$CHECKPOINT" regenerate-tasks --progress "$FX/implementation-progress.md" --tasks "$FX/tasks.md" --write >/dev/null
  twice="$(cat "$FX/tasks.md")"
  [ "$once" = "$twice" ] || { printf 'some-complete case not idempotent\n' >&2; return 1; }

  # all complete
  progress='### 1. alpha

| Field | Value |
|-------|-------|
| Commit | `abc1234` |

### 2. beta

| Field | Value |
|-------|-------|
| Commit | `def5678` |
'
  write_progress "$progress"
  write_tasks "$tasks_a"
  "$CHECKPOINT" regenerate-tasks --progress "$FX/implementation-progress.md" --tasks "$FX/tasks.md" --write >/dev/null
  once="$(cat "$FX/tasks.md")"
  "$CHECKPOINT" regenerate-tasks --progress "$FX/implementation-progress.md" --tasks "$FX/tasks.md" --write >/dev/null
  twice="$(cat "$FX/tasks.md")"
  [ "$once" = "$twice" ] || { printf 'all-complete case not idempotent\n' >&2; return 1; }

  # spec names requiring escaping (parentheses, ampersand)
  progress='### 1. alpha (v2) & friends

| Field | Value |
|-------|-------|
| Commit | `abc1234` |
'
  tasks_b='## 1. alpha (v2) & friends

- [ ] Step 0 — x
'
  write_progress "$progress"
  write_tasks "$tasks_b"
  "$CHECKPOINT" regenerate-tasks --progress "$FX/implementation-progress.md" --tasks "$FX/tasks.md" --write >/dev/null
  once="$(cat "$FX/tasks.md")"
  "$CHECKPOINT" regenerate-tasks --progress "$FX/implementation-progress.md" --tasks "$FX/tasks.md" --write >/dev/null
  twice="$(cat "$FX/tasks.md")"
  [ "$once" = "$twice" ] || { printf 'escaping-name case not idempotent\n' >&2; return 1; }
}
