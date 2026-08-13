#!/usr/bin/env bats
#
# Oracle for spec:chain-state (change: add-correctness-substratum).
#
# Written from the spec and the approved Step 1 contract ONLY, before
# scanner/chain-state.sh exists. `scanner/ledger.sh` and
# `scanner/spec-lint.sh` are pre-existing, approved dependencies (specs 1-2
# and the base workflow) and are used AS-IS to build fixtures — this suite
# does not fake their formats.
#
# ALGORITHM UNDER TEST (approved at the Step 1 gate):
#   bound      = NOT in spec-lint --artifacts' "FAIL F7" set for that title
#   resolved   = every Proof-Obligations row mapped to this title (via the
#                "Requirement: <title>" preferred Source form, split on " + ")
#                has no artifact token in spec-lint's "FAIL F9" set
#   discharged = every row mapped to this title has >=1 matching ledger row
#                (change + baseline + obligation-text, via ledger.sh read)
#   unmapped_obligations = rows with an F9 hit whose Source does not map to
#                any requirement title — a stated scope limit, never dropped
#                and never misattributed.
# The PASS/FAIL judgment for reachability and artifact existence is ALWAYS
# spec-lint's; this suite (and the tool) only re-reads table STRUCTURE at
# lines spec-lint already flagged, matching titles textually.

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  CS="$SCHEMA/scanner/chain-state.sh"
  LEDGER="$SCHEMA/scanner/ledger.sh"
  CONTRACT="$SCHEMA/scanner/chain-state-report-contract.jq"
  ROOT="$(repo_root)"
  BASE="00d3de1"
  CHG="fixture-change"
  FX="$BATS_TEST_TMPDIR/$CHG"

  # A CODE-SHAPED artifact token (matches F9's */*/ pattern) that IS tracked,
  # so spec-lint resolves it — real, from spec 1's commit.
  RESOLVES='openspec/schemas/verified-scala3/tests/correctness-invariant.bats'
  # A code-shaped token that matches nothing.
  UNRESOLVED='tests/totally-fake-nonexistent-fixture-artifact.bats'
}

# ── fixture construction ─────────────────────────────────────────────────
# Every builder writes ONE spec file under $FX/specs/only/spec.md. Requirement
# rows are minimal but spec-lint-clean apart from the deliberately induced
# defect, so the ONLY F7/F9 findings are the ones each shape intends.

report_of() { # $1=change-dir $2=change-name $3=baseline; prints the report json
  # R8 FIX: set OPENSPEC_ROOT so openspec-graph.py can find the test repo,
  # and suppress stderr so trace lines don't corrupt JSON parsing in bats.
  # Uses bash -c wrapper because bats `run` captures stderr into $output
  # regardless of 2>/dev/null on the command line.
  # change-dir is $FX/openspec/changes/<change>, so OPENSPEC_ROOT is ../../..
  # (the directory containing the openspec/ tree).
  OPENSPEC_ROOT="$(cd "$1/../../.." && pwd)" bash -c \
    '"$1" --change-dir "$2" --change "$3" --baseline "$4" 2>/dev/null' \
    bash "$CS" "$1" "$2" "$3"
}

spec_header() {
  cat <<'EOF'
# Spec: Fixture

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| (none) | fixture spec for chain-state's own test oracle | — |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|

## ADDED Requirements

EOF
}

req_block() { # $1=title
  cat <<EOF
### Requirement: $1

The system SHALL do the thing named $1.

**Given** a precondition
**When** an action
**Then** an observable outcome

#### Scenario: happy path

**Given** a specific setup
**When** a specific action
**Then** a specific assertion

EOF
}

po_header() { printf '## Proof Obligations\n\n| Obligation | Source | Enforcement | Artifact |\n|---|---|---|---|\n'; }

# fully bound + resolved + discharged, N requirements
build_all_satisfied() { # $1=count
  mkdir -p "$FX/specs/only"
  { spec_header; local i; for i in $(seq 1 "$1"); do req_block "Req $i"; done; po_header
    for i in $(seq 1 "$1"); do
      printf '| Text of obligation %s | Requirement: Req %s | manual | `%s` |\n' "$i" "$i" "$RESOLVES"
    done
  } >"$FX/specs/only/spec.md"
  : >"$FX/evidence-ledger.jsonl"
  local i
  for i in $(seq 1 "$1"); do
    "$LEDGER" append --file "$FX/evidence-ledger.jsonl" --change "$CHG" --spec only \
      --ring manual --obligation "Text of obligation $i" --artifact "$RESOLVES" \
      --command true --exit 0 --baseline "$BASE" >/dev/null
  done
}

# fully unbound, N requirements — Proof Obligations section present, empty
build_none_satisfied() { # $1=count
  mkdir -p "$FX/specs/only"
  { spec_header; local i; for i in $(seq 1 "$1"); do req_block "Req $i"; done
    printf '## Proof Obligations\n\n| Obligation | Source | Enforcement | Artifact |\n|---|---|---|---|\n'
    printf '| unrelated obligation | Property: nothing-to-do-with-these | manual | — |\n'
  } >"$FX/specs/only/spec.md"
  : >"$FX/evidence-ledger.jsonl"
}

# ONE requirement, unbound only (no row names it at all)
build_unbound_only() {
  mkdir -p "$FX/specs/only"
  { spec_header; req_block "Solo Req"; po_header
    printf '| unrelated | Property: elsewhere | manual | — |\n'
  } >"$FX/specs/only/spec.md"
  : >"$FX/evidence-ledger.jsonl"
}

# ONE requirement, bound, artifact unresolved
build_unresolved_only() {
  mkdir -p "$FX/specs/only"
  { spec_header; req_block "Solo Req"; po_header
    printf '| Text of obligation | Requirement: Solo Req | manual | `%s` |\n' "$UNRESOLVED"
  } >"$FX/specs/only/spec.md"
  : >"$FX/evidence-ledger.jsonl"
}

# ONE requirement, bound + resolved, no ledger row
build_undischarged_only() {
  mkdir -p "$FX/specs/only"
  { spec_header; req_block "Solo Req"; po_header
    printf '| Text of obligation | Requirement: Solo Req | manual | `%s` |\n' "$RESOLVES"
  } >"$FX/specs/only/spec.md"
  : >"$FX/evidence-ledger.jsonl"
}

build_zero_requirements() {
  mkdir -p "$FX/specs/only"
  { spec_header; po_header; printf '| n/a | n/a | n/a | n/a |\n'; } >"$FX/specs/only/spec.md"
  : >"$FX/evidence-ledger.jsonl"
}

# A requirement bound to an obligation row via a COMBINED source, the norm in
# this project's real specs — "Requirement: X + Scenario: Y".
build_combined_source() {
  mkdir -p "$FX/specs/only"
  { spec_header; req_block "Combo Req"; po_header
    printf '| Text of obligation | Requirement: Combo Req + Scenario: happy path | manual | `%s` |\n' "$RESOLVES"
  } >"$FX/specs/only/spec.md"
  : >"$FX/evidence-ledger.jsonl"
  "$LEDGER" append --file "$FX/evidence-ledger.jsonl" --change "$CHG" --spec only \
    --ring manual --obligation "Text of obligation" --artifact "$RESOLVES" \
    --command true --exit 0 --baseline "$BASE" >/dev/null
}

# A row whose Source is a NON-requirement typed source with an F9 hit — must
# surface in unmapped_obligations, never silently dropped or misattributed.
build_unmapped_obligation() {
  mkdir -p "$FX/specs/only"
  { spec_header; req_block "Solo Req"; po_header
    printf '| Text of obligation | Requirement: Solo Req | manual | `%s` |\n' "$RESOLVES"
    printf '| Orphan obligation | Property: some-property | manual | `%s` |\n' "$UNRESOLVED"
  } >"$FX/specs/only/spec.md"
  : >"$FX/evidence-ledger.jsonl"
  "$LEDGER" append --file "$FX/evidence-ledger.jsonl" --change "$CHG" --spec only \
    --ring manual --obligation "Text of obligation" --artifact "$RESOLVES" \
    --command true --exit 0 --baseline "$BASE" >/dev/null
}

conforms() { printf '%s' "$1" | jq -e -f "$CONTRACT" >/dev/null 2>&1; }

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Chain state reports all three clauses separately
# ═════════════════════════════════════════════════════════════════════════

# spec: chain-state — Scenario: bound but not resolved
@test "bound but not resolved: bound equals total, resolved is lower" {
  build_unresolved_only
  run report_of "$FX" "$CHG" "$BASE"
  assert_status 1 "$status" "an unresolved requirement is a finding"
  conforms "$output" || { printf 'report does not conform:\n%s\n' "$output" >&2; return 1; }
  local total bound resolved
  total="$(jq '.total' <<<"$output")"; bound="$(jq '.bound' <<<"$output")"; resolved="$(jq '.resolved' <<<"$output")"
  [ "$bound" -eq "$total" ] || { printf 'bound (%s) != total (%s)\n' "$bound" "$total" >&2; return 1; }
  [ "$resolved" -lt "$bound" ] || { printf 'resolved (%s) not lower than bound (%s)\n' "$resolved" "$bound" >&2; return 1; }
}

# spec: chain-state — Scenario: resolved but not discharged
@test "resolved but not discharged: resolved equals total, discharged is zero" {
  build_undischarged_only
  run report_of "$FX" "$CHG" "$BASE"
  assert_status 1 "$status" "an undischarged requirement is a finding"
  local total resolved discharged
  total="$(jq '.total' <<<"$output")"; resolved="$(jq '.resolved' <<<"$output")"; discharged="$(jq '.discharged' <<<"$output")"
  [ "$resolved" -eq "$total" ] || { printf 'resolved (%s) != total (%s)\n' "$resolved" "$total" >&2; return 1; }
  [ "$discharged" -eq 0 ] || { printf 'discharged (%s) not zero\n' "$discharged" >&2; return 1; }
}

# spec: chain-state — Scenario: a single verdict is not emitted in place of the counts
@test "the report always carries the three counts, never a lone verdict" {
  build_all_satisfied 2
  run report_of "$FX" "$CHG" "$BASE"
  assert_status 0 "$status" "a fully-satisfied change is not a finding"
  for f in total bound resolved discharged; do
    jq -e "has(\"$f\")" <<<"$output" >/dev/null || {
      printf 'report is missing field: %s\n' "$f" >&2
      return 1
    }
  done
  jq -e 'has("pass") or has("verdict") or has("ok")' <<<"$output" >/dev/null && {
    printf 'report carries a lone verdict field alongside the counts\n' >&2
    return 1
  }
  return 0
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Unresolved entries are named, not just counted
# ═════════════════════════════════════════════════════════════════════════

# spec: chain-state — Scenario: each failing clause produces a distinct reason
@test "unbound, unresolved and undischarged each produce a distinct named reason" {
  mkdir -p "$FX/specs/only"
  { spec_header
    req_block "Unbound Req"
    req_block "Unresolved Req"
    req_block "Undischarged Req"
    po_header
    printf '| o1 | Requirement: Unresolved Req | manual | `%s` |\n' "$UNRESOLVED"
    printf '| o2 | Requirement: Undischarged Req | manual | `%s` |\n' "$RESOLVES"
  } >"$FX/specs/only/spec.md"
  : >"$FX/evidence-ledger.jsonl"
  run report_of "$FX" "$CHG" "$BASE"
  assert_status 1 "$status" "unresolved requirements exist"
  local ub uv ud
  ub="$(jq -r '.unresolved[] | select(.requirement=="Unbound Req") | .reasons[0]' <<<"$output")"
  uv="$(jq -r '.unresolved[] | select(.requirement=="Unresolved Req") | .reasons[0]' <<<"$output")"
  ud="$(jq -r '.unresolved[] | select(.requirement=="Undischarged Req") | .reasons[0]' <<<"$output")"
  [ "$ub" = "unbound" ] || { printf 'Unbound Req reason: got %s\n' "$ub" >&2; return 1; }
  [ "$uv" = "unresolved" ] || { printf 'Unresolved Req reason: got %s\n' "$uv" >&2; return 1; }
  [ "$ud" = "undischarged" ] || { printf 'Undischarged Req reason: got %s\n' "$ud" >&2; return 1; }
}

# spec: chain-state — Scenario: a fully satisfied change lists nothing
@test "a fully satisfied change has an empty unresolved list and equal counts" {
  build_all_satisfied 3
  run report_of "$FX" "$CHG" "$BASE"
  assert_status 0 "$status" "fully satisfied is not a finding"
  [ "$(jq '.unresolved | length' <<<"$output")" -eq 0 ]
  local t b r d
  t="$(jq '.total' <<<"$output")"; b="$(jq '.bound' <<<"$output")"
  r="$(jq '.resolved' <<<"$output")"; d="$(jq '.discharged' <<<"$output")"
  [ "$t" -eq 3 ] && [ "$b" -eq 3 ] && [ "$r" -eq 3 ] && [ "$d" -eq 3 ]
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Chain state derives its first two clauses from the existing lint
# ═════════════════════════════════════════════════════════════════════════

# spec: chain-state — Scenario: chain state and lint agree on an unbound requirement
@test "chain state and spec-lint agree on which requirement is unbound" {
  build_unbound_only
  run report_of "$FX" "$CHG" "$BASE"
  local reported
  reported="$(jq -r '.unresolved[0].requirement' <<<"$output")"
  run bash -c "'$SCHEMA/scanner/spec-lint.sh' --artifacts '$FX'"
  assert_contains "$output" "\"$reported\" is named by NO proof obligation" "spec-lint's own F7 finding"
}

# spec: chain-state — Scenario: chain state and lint agree on an unresolved artifact
@test "chain state and spec-lint agree on which artifact is unresolved" {
  build_unresolved_only
  run bash -c "'$SCHEMA/scanner/spec-lint.sh' --artifacts '$FX'"
  assert_contains "$output" "artifact '$UNRESOLVED' does not resolve" "spec-lint's own F9 finding"
  run report_of "$FX" "$CHG" "$BASE"
  assert_contains "$output" "Solo Req" "chain-state must report the same requirement as unresolved"
}

# Combined sources are the norm in this project's real specs — verified
# earlier against correctness-invariant and evidence-ledger. This is not an
# edge case; it is the common path.
@test "a requirement bound via a combined Source is bound, resolved and discharged" {
  build_combined_source
  run report_of "$FX" "$CHG" "$BASE"
  assert_status 0 "$status" "combined source must not defeat the mapping"
  local b r d
  b="$(jq '.bound' <<<"$output")"; r="$(jq '.resolved' <<<"$output")"; d="$(jq '.discharged' <<<"$output")"
  [ "$b" -eq 1 ] && [ "$r" -eq 1 ] && [ "$d" -eq 1 ] || {
    printf 'combined-source requirement not fully satisfied: bound=%s resolved=%s discharged=%s\n' "$b" "$r" "$d" >&2
    return 1
  }
}

# spec: chain-state — Requirement: Chain state derives its first two clauses from the existing lint
@test "an unmapped obligation with an F9 hit is surfaced, not dropped or misattributed" {
  build_unmapped_obligation
  run report_of "$FX" "$CHG" "$BASE"
  assert_status 1 "$status" "an unresolved artifact anywhere is a finding"
  [ "$(jq '.unmapped_obligations | length' <<<"$output")" -eq 1 ] || {
    printf 'expected 1 unmapped obligation, got: %s\n' "$output" >&2
    return 1
  }
  assert_contains "$output" "$UNRESOLVED" "the unmapped entry names its artifact"
  # It must NOT be misattributed to "Solo Req", which is fully satisfied.
  local solo_reasons
  solo_reasons="$(jq -r '.unresolved[] | select(.requirement=="Solo Req")' <<<"$output")"
  [ -z "$solo_reasons" ] || { printf 'Solo Req wrongly appears in unresolved: %s\n' "$solo_reasons" >&2; return 1; }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Absence of evidence is never reported as satisfaction
# ═════════════════════════════════════════════════════════════════════════

# spec: chain-state — Scenario: an unreadable ledger yields undetermined, not zero
@test "a corrupt ledger makes the whole report undetermined, never zero-discharged" {
  build_all_satisfied 2
  printf 'not json at all\n' >>"$FX/evidence-ledger.jsonl"
  run report_of "$FX" "$CHG" "$BASE"
  assert_status 2 "$status" "a corrupt ledger is UNDETERMINED"
  assert_contains "$output" "undetermined" "the report must say so"
  assert_not_contains "$output" '"discharged":0' "must not present undetermined as a zero measurement"
}

# spec: chain-state — Scenario: a failed lint yields undetermined, not zero
@test "a spec-lint that cannot run at all yields undetermined" {
  build_all_satisfied 1
  # Point chain-state at a spec-lint binary that crashes for a reason OTHER
  # than lint findings (exit 13, not 0 or 1).
  local fake="$BATS_TEST_TMPDIR/fake-spec-lint.sh"
  printf '#!/usr/bin/env bash\nexit 13\n' >"$fake"
  chmod +x "$fake"
  SPEC_LINT_OVERRIDE="$fake" run report_of "$FX" "$CHG" "$BASE"
  assert_status 2 "$status" "a non-0/1 spec-lint exit is UNDETERMINED"
}

# spec: chain-state — Scenario: a genuinely empty ledger is reported as zero discharged
@test "an empty but readable ledger is a successful zero-discharged measurement" {
  build_all_satisfied 1
  : >"$FX/evidence-ledger.jsonl"
  run report_of "$FX" "$CHG" "$BASE"
  assert_status 1 "$status" "bound+resolved but zero discharged is a finding, not undetermined"
  [ "$(jq '.discharged' <<<"$output")" -eq 0 ]
  assert_not_contains "$output" "undetermined" "an empty ledger is a real measurement, not undetermined"
}

# A spec.md with a Proof Obligations section but zero requirement headings is
# a legitimate zero. CORRECTED at Ring 8: the comment here previously claimed
# this covered "no specs/ at all", which this builder does not construct
# (it writes a real spec.md) — verified false by the fresh-context pass. The
# genuinely-missing-directory case is covered by the next test.
@test "a spec with zero requirement headings reports total zero, cleanly" {
  build_zero_requirements
  run report_of "$FX" "$CHG" "$BASE"
  assert_status 0 "$status" "vacuously satisfied"
  [ "$(jq '.total' <<<"$output")" -eq 0 ]
  [ "$(jq '.unresolved | length' <<<"$output")" -eq 0 ]
}

# CORRECTED at Ring 8 (this test's own assertion was wrong, found while
# fixing the findings above). A change directory with no specs/ subdirectory
# AT ALL is spec-lint's own UNDETERMINED signal, not a clean zero: verified
# directly (no pipe involved this time — an earlier verification during
# implementation piped through `tail`, which reports tail's exit code, not
# spec-lint's) — "no specs found under ..." pairs with exit 2. Distinct from
# "specs/ exists but is empty", which pairs with exit 0 and IS a legitimate
# zero (covered by the requirement-heading test above). chain-state.sh's
# existing exit-2 propagation for spec-lint already covers this; no special
# casing was needed, only a correct test.
@test "a change directory with no specs/ subdirectory at all is undetermined, not a clean zero" {
  mkdir -p "$FX"
  : >"$FX/evidence-ledger.jsonl"
  run report_of "$FX" "$CHG" "$BASE"
  assert_status 2 "$status" "spec-lint itself treats a missing specs/ as undetermined (exit 2), not a valid empty change"
  assert_contains "$output" "undetermined" "the report must say so"
}

# Ring 8 (F1, the critical finding): spec-lint's own F7 binding is LOOSER
# than this tool's exact-title matcher (it also accepts ordinals and
# case-insensitive substring Source references). A title spec-lint calls
# bound, for which this tool's narrow matcher finds ZERO rows, was being
# silently reported "ok" (fully discharged, on NO evidence at all) by the
# pre-fix default. Verified directly against the real script before this fix
# landed: total=1 bound=1 resolved=1 discharged=1 unresolved=[] on an EMPTY
# ledger, using an ordinal Source ("Requirement 1").
@test "a bound title with no exactly-matched obligation row is unattributable, never ok" {
  mkdir -p "$FX/specs/only"
  { spec_header; req_block "Solo Req"; po_header
    printf '| An ordinal-sourced obligation | Requirement 1 | manual | `%s` |\n' "$RESOLVES"
  } >"$FX/specs/only/spec.md"
  : >"$FX/evidence-ledger.jsonl"
  run report_of "$FX" "$CHG" "$BASE"
  assert_status 1 "$status" "unattributable is a finding, never a silent pass"
  [ "$(jq '.discharged' <<<"$output")" -eq 0 ] || {
    printf 'an unattributable title was counted discharged: %s\n' "$output" >&2
    return 1
  }
  local reason
  reason="$(jq -r '.unresolved[] | select(.requirement=="Solo Req") | .reasons[0]' <<<"$output")"
  [ "$reason" = "unattributable" ] || {
    printf 'expected reason unattributable, got: %s\n' "$reason" >&2
    return 1
  }
}

# Ring 8 (F2): a title that is a TEXTUAL PREFIX of another title in the same
# spec must not be conflated with it by a non-anchored substring match.
# Verified directly: the pre-fix `grep -qF` (no -x) on "path<TAB>title"
# falsely matched "Alpha" against the stored row for "Alpha Extended".
@test "a title that is a prefix of another title is not conflated with it" {
  mkdir -p "$FX/specs/only"
  { spec_header
    req_block "Alpha"
    req_block "Alpha Extended"
    po_header
    printf '| Text of the alpha obligation | Requirement: Alpha | manual | `%s` |\n' "$RESOLVES"
  } >"$FX/specs/only/spec.md"
  : >"$FX/evidence-ledger.jsonl"
  "$LEDGER" append --file "$FX/evidence-ledger.jsonl" --change "$CHG" --spec only \
    --ring manual --obligation "Text of the alpha obligation" --artifact "$RESOLVES" \
    --command true --exit 0 --baseline "$BASE" >/dev/null
  run report_of "$FX" "$CHG" "$BASE"
  # "Alpha" is fully satisfied (must NOT appear in unresolved); "Alpha
  # Extended" is genuinely unbound (must appear, exactly once).
  local alpha_present extended_reason
  alpha_present="$(jq -r '.unresolved[] | select(.requirement=="Alpha")' <<<"$output")"
  [ -z "$alpha_present" ] || {
    printf 'Alpha was wrongly conflated with Alpha Extended: %s\n' "$output" >&2
    return 1
  }
  extended_reason="$(jq -r '.unresolved[] | select(.requirement=="Alpha Extended") | .reasons[0]' <<<"$output")"
  [ "$extended_reason" = "unbound" ] || {
    printf 'Alpha Extended: expected unbound, got: %s\n' "$extended_reason" >&2
    return 1
  }
}

# Ring 8 (F3): an F9-hit LINE NUMBER must not be matched as a substring of a
# different line number (line 29 matching line 290). Verified directly: the
# pre-fix `grep -qF` (no -x) attributed a distant, unrelated row's F9
# verdict to a fully-resolving requirement 20+ lines away.
@test "an F9-hit line number is not conflated with an unrelated line sharing its digits" {
  mkdir -p "$FX/specs/only"
  {
    spec_header
    req_block "Solo Req" # this requirement's own row resolves cleanly
    po_header
    printf '| Text of the solo obligation | Requirement: Solo Req | manual | `%s` |\n' "$RESOLVES"
    # pad with filler lines so a later row's line number has the earlier
    # row's line number as a literal substring (e.g. 29 inside 290)
    local i
    for i in $(seq 1 260); do printf '<!-- filler line %s -->\n' "$i"; done
    printf '| An orphan obligation elsewhere | Property: unrelated | manual | `%s` |\n' "$UNRESOLVED"
  } >"$FX/specs/only/spec.md"
  : >"$FX/evidence-ledger.jsonl"
  "$LEDGER" append --file "$FX/evidence-ledger.jsonl" --change "$CHG" --spec only \
    --ring manual --obligation "Text of the solo obligation" --artifact "$RESOLVES" \
    --command true --exit 0 --baseline "$BASE" >/dev/null
  run report_of "$FX" "$CHG" "$BASE"
  local solo_present
  solo_present="$(jq -r '.unresolved[] | select(.requirement=="Solo Req")' <<<"$output")"
  [ -z "$solo_present" ] || {
    printf 'Solo Req was wrongly marked unresolved by an unrelated F9 line: %s\n' "$output" >&2
    return 1
  }
  [ "$(jq '.unmapped_obligations | length' <<<"$output")" -eq 1 ]
}

# Ring 8 (F4), REVISED after further investigation: a single-row fixture where
# that one row's Obligation-cell text begins with "Obligation" cannot actually
# distinguish this fix, because spec-lint.sh's OWN F7/F9 detection (which
# chain-state must never reimplement) is gated by the IDENTICAL content-based
# exclusion (`$0 !~ /^\| *Obligation/`, spec-lint.sh's check_source/F9 scans)
# — such a row is invisible to spec-lint TOO, so the requirement is correctly
# reported unbound regardless of anything chain-state's own parser does. That
# is spec-lint's own pre-existing behaviour, out of scope for this spec to
# change, and chain-state is REQUIRED to agree with it.
#
# The fix has real, observable value in a DIFFERENT shape: a requirement with
# TWO mapped rows, where only the SECOND happens to start with "Obligation".
# spec-lint's F7 sees the FIRST row and correctly calls the requirement
# bound; its F9 never flags the second row (F9 skips it too, for the same
# content-match reason), so nothing external ever points at it. Before the
# fix, chain-state's own row-parser ALSO silently dropped that second row
# from its Proof-Obligations table read — not merely from mapping — so
# discharge-checking only ever saw the first (evidenced) row and reported the
# requirement fully "ok" while the second row's evidence was never examined
# at all. That is exactly the class of claim-outrunning-evidence this whole
# project targets, just with a subtler trigger than F1's.
@test "a second mapped row is not silently dropped merely because it starts with the word Obligation" {
  mkdir -p "$FX/specs/only"
  { spec_header; req_block "Solo Req"; po_header
    printf '| Text of a clean obligation | Requirement: Solo Req | manual | `%s` |\n' "$RESOLVES"
    printf '| Obligations of this kind are recorded here | Requirement: Solo Req | manual | `%s` |\n' "$RESOLVES"
  } >"$FX/specs/only/spec.md"
  : >"$FX/evidence-ledger.jsonl"
  # Evidence for the FIRST row only — the second is deliberately left
  # undischarged, and must be seen and reported as such.
  "$LEDGER" append --file "$FX/evidence-ledger.jsonl" --change "$CHG" --spec only \
    --ring manual --obligation "Text of a clean obligation" --artifact "$RESOLVES" \
    --command true --exit 0 --baseline "$BASE" >/dev/null
  run report_of "$FX" "$CHG" "$BASE"
  assert_status 1 "$status" "the second row's missing evidence must surface, not be silently dropped"
  local reason
  reason="$(jq -r '.unresolved[] | select(.requirement=="Solo Req") | .reasons[0]' <<<"$output")"
  [ "$reason" = "undischarged" ] || {
    printf 'expected undischarged (both rows examined, one lacks evidence), got: %s (full: %s)\n' "$reason" "$output" >&2
    return 1
  }
}

# Ring 8 (F8/F9): the contract's cross-consistency check must be able to
# reject a report whose counts disagree with the reasons actually carried in
# `unresolved` — this is what makes the self-check catch MISCLASSIFICATION,
# not merely shape violations.
@test "the contract rejects a report whose counts disagree with its own reasons" {
  local bad='{"change":"c","baseline":"b","total":1,"bound":1,"resolved":1,"discharged":1,"unresolved":[],"unmapped_obligations":[]}'
  # Structurally valid and internally self-consistent (0 unresolved matches
  # discharged==total) — this ALONE cannot prove the cross-check works; it
  # proves the happy path still passes.
  conforms "$bad" || { printf 'a genuinely clean report was rejected\n' >&2; return 1; }
  # Now the same counts, but claiming a title is undischarged without a
  # trailing entry to justify it: discharged=1 but resolved-discharged=0,
  # and there is no unresolved entry — LAW HOLDS here as it must (0==0). Use
  # a shape where the count truly disagrees: total=2 bound=2 resolved=2
  # discharged=2 (nothing unresolved) but 0 entries even though the property
  # requires discharged<total to be reflected. Construct total=2 discharged=1
  # with an EMPTY unresolved list — list-size law alone catches this, so
  # instead misalign the REASON composition specifically:
  local mismatched='{"change":"c","baseline":"b","total":2,"bound":2,"resolved":2,"discharged":1,"unresolved":[{"spec":"s","requirement":"R","reasons":["unbound"]}],"unmapped_obligations":[]}'
  # total-bound=0 but an "unbound" entry exists — the cross-check must catch
  # this even though the size law (unresolved.length==total-discharged==1)
  # alone would NOT catch it.
  ! conforms "$mismatched" || {
    printf 'contract accepted a report whose reason composition disagrees with its counts\n' >&2
    return 1
  }
}

# spec: chain-state — Property: The unresolved list and the counts agree
# (the "listed once" edge case, as its own dedicated test against a
# dedicated contract check — see the correction above)
@test "the contract rejects the same requirement appearing twice in unresolved" {
  local dup='{"change":"c","baseline":"b","total":1,"bound":0,"resolved":0,"discharged":0,"unresolved":[{"spec":"s","requirement":"R","reasons":["unbound"]},{"spec":"s","requirement":"R","reasons":["unresolved"]}],"unmapped_obligations":[]}'
  ! conforms "$dup" || {
    printf 'contract accepted a duplicate (spec, requirement) entry in unresolved\n' >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Properties (Ring 3) — ENUMERATED over the spec's declared construction set.
# Bash has no generator or shrinker; each domain is built, never sampled.
# ═════════════════════════════════════════════════════════════════════════

# spec: chain-state — Property: Counts never exceed the total and are monotone across clauses
@test "PROPERTY counts are monotone and bounded across the declared construction set" {
  # Generator strategy (enumerated): all-satisfied, none-satisfied, each single
  # clause failing in isolation (3), and zero requirements — the exact set the
  # spec declares. Constructive; each builder produces a known expected shape.
  local shape
  for shape in all-satisfied none-satisfied unbound-only unresolved-only undischarged-only zero-requirements; do
    rm -rf "$FX"
    case "$shape" in
      all-satisfied) build_all_satisfied 3 ;;
      none-satisfied) build_none_satisfied 2 ;;
      unbound-only) build_unbound_only ;;
      unresolved-only) build_unresolved_only ;;
      undischarged-only) build_undischarged_only ;;
      zero-requirements) build_zero_requirements ;;
    esac
    run report_of "$FX" "$CHG" "$BASE"
    [ "$status" = 0 ] || [ "$status" = 1 ] || {
      printf 'shape %s: unexpected status %s (expected a real measurement, 0 or 1)\n' "$shape" "$status" >&2
      return 1
    }
    conforms "$output" || {
      printf 'shape %s: report does not conform to the approved contract:\n%s\n' "$shape" "$output" >&2
      return 1
    }
  done
}

# spec: chain-state — Property: The unresolved list and the counts agree
@test "PROPERTY unresolved list size equals total minus discharged, across the construction set" {
  # Generator strategy: same 6 shapes, plus zero requirements (list size
  # 0 == 0 - 0). The "listed once" edge case (a requirement failing two
  # clauses) is now covered by its OWN dedicated test below, against a
  # dedicated contract check (duplicate (spec,requirement) rejection) —
  # CORRECTED at Ring 8: the comment here previously claimed the contract's
  # reasons-uniqueness check (uniqueness WITHIN one entry) discharged this
  # edge case; the fresh-context pass showed that check says nothing about
  # the SAME requirement appearing in two separate entries, and that this
  # property's loop never built the two-clause-failure case at all.
  local shape
  for shape in all-satisfied none-satisfied unbound-only unresolved-only undischarged-only zero-requirements; do
    rm -rf "$FX"
    case "$shape" in
      all-satisfied) build_all_satisfied 2 ;;
      none-satisfied) build_none_satisfied 3 ;;
      unbound-only) build_unbound_only ;;
      unresolved-only) build_unresolved_only ;;
      undischarged-only) build_undischarged_only ;;
      zero-requirements) build_zero_requirements ;;
    esac
    run report_of "$FX" "$CHG" "$BASE"
    local total discharged size
    total="$(jq '.total' <<<"$output")"; discharged="$(jq '.discharged' <<<"$output")"
    size="$(jq '.unresolved | length' <<<"$output")"
    [ "$size" -eq $((total - discharged)) ] || {
      printf 'shape %s: unresolved size %s != total(%s)-discharged(%s)\n' "$shape" "$size" "$total" "$discharged" >&2
      return 1
    }
  done
}

# spec: chain-state — Property: Unreadable evidence never yields a satisfied report
@test "PROPERTY every induced failure yields undetermined, never a satisfied report" {
  # Generator strategy (enumerated): the evidence-ledger corruption set, plus
  # an absent lint executable and a lint returning a non-finding error.
  # Constructive — each condition is induced deliberately.
  local condition
  for condition in truncated-ledger unparseable-ledger unreadable-ledger \
    lint-absent lint-non-finding-error; do
    rm -rf "$FX"
    build_all_satisfied 1
    case "$condition" in
      truncated-ledger) printf '{"v":1,"ts":"2026-08-0\n' >>"$FX/evidence-ledger.jsonl" ;;
      unparseable-ledger) printf 'garbage\n' >>"$FX/evidence-ledger.jsonl" ;;
      unreadable-ledger) chmod 000 "$FX/evidence-ledger.jsonl" ;;
      lint-absent) : ;; # handled via SPEC_LINT_OVERRIDE below
      lint-non-finding-error) : ;;
    esac
    if [ "$condition" = lint-absent ]; then
      SPEC_LINT_OVERRIDE="$BATS_TEST_TMPDIR/does-not-exist.sh" run report_of "$FX" "$CHG" "$BASE"
    elif [ "$condition" = lint-non-finding-error ]; then
      local fake="$BATS_TEST_TMPDIR/fake2.sh"
      printf '#!/usr/bin/env bash\nexit 42\n' >"$fake"
      chmod +x "$fake"
      SPEC_LINT_OVERRIDE="$fake" run report_of "$FX" "$CHG" "$BASE"
    else
      run report_of "$FX" "$CHG" "$BASE"
    fi
    [ "$condition" != unreadable-ledger ] || chmod 644 "$FX/evidence-ledger.jsonl"
    assert_status 2 "$status" "condition=$condition must be UNDETERMINED"
    assert_not_contains "$output" '"discharged":0' "condition=$condition must not present as a zero measurement"
  done
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
