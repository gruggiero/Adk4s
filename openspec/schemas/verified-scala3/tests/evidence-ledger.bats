#!/usr/bin/env bats
#
# Oracle for spec:evidence-ledger (change: add-correctness-substratum).
#
# Written from the spec and the approved Step 1 contract ONLY, before
# scanner/ledger.sh exists. Tests written after an implementation mirror it;
# these are an independent oracle derived from the specification.
#
# The approved contract (scanner/ledger-record-contract.jq) is the single
# statement of the record format. This suite VALIDATES AGAINST IT rather than
# restating the field rules, so the oracle and the implementation cannot drift
# apart by disagreeing about what a row is.

setup() {
  load helpers
  SCHEMA="$(schema_dir)"
  LEDGER="$SCHEMA/scanner/ledger.sh"
  CONTRACT="$SCHEMA/scanner/ledger-record-contract.jq"
  FIXTURE="$SCHEMA/tests/fixtures/evidence-ledger-v1.jsonl"
  LF="$BATS_TEST_TMPDIR/ledger.jsonl"
  BASE="723f22f"
}

# ── local helpers ────────────────────────────────────────────────────────

# Append with every required field defaulted; pass overrides as flag/value.
app() {
  # `${X-default}` NOT `${X:-default}`: the colon form substitutes the default
  # for an EMPTY value as well as an unset one, so `OBL="" app` silently sent
  # "an obligation" and the empty-value case in the declared domain never
  # reached ledger.sh at all. The helper was masking the very input the
  # property exists to exercise.
  "$LEDGER" append --file "$LF" \
    --change "${CHG-add-correctness-substratum}" \
    --spec "${SPC-evidence-ledger}" \
    --ring "${RING-R3}" \
    --obligation "${OBL-an obligation}" \
    --artifact "${ART-tests/evidence-ledger.bats}" \
    --command "${CMD-true}" \
    --exit "${EXT-0}" \
    --baseline "${BSL-$BASE}" "$@"
}

# Every row in a file must satisfy the approved contract.
all_rows_conform() { # $1=file
  local line
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    printf '%s' "$line" | jq -e -f "$CONTRACT" >/dev/null 2>&1 || return 1
  done <"$1"
  return 0
}

rows_in() { grep -c . "$1" 2>/dev/null || echo 0; }

# ═════════════════════════════════════════════════════════════════════════
# Requirement: A ledger row records an observed run, not an intention
# ═════════════════════════════════════════════════════════════════════════

# spec: evidence-ledger — Scenario: a complete row is accepted
@test "a complete row is accepted and conforms to the approved contract" {
  run app
  assert_status 0 "$status" "a complete append must succeed"
  [ "$(rows_in "$LF")" -eq 1 ] || {
    printf 'expected exactly 1 row, got %s\n' "$(rows_in "$LF")" >&2
    return 1
  }
  all_rows_conform "$LF" || {
    printf 'the written row does not satisfy the approved contract\n' >&2
    cat "$LF" >&2
    return 1
  }
}

# spec: evidence-ledger — Scenario: a row missing the exit status is rejected
@test "a row missing the exit status is rejected, naming the field" {
  run "$LEDGER" append --file "$LF" --change c --spec s --ring R3 \
    --obligation o --artifact a --command true --baseline "$BASE"
  assert_status 1 "$status" "a missing required field is a finding, not an undetermined result"
  assert_contains "$output" "exit" "the writer must name the missing field"
  [ ! -s "$LF" ] || {
    printf 'a rejected append must write nothing; file is non-empty\n' >&2
    return 1
  }
}

# spec: evidence-ledger — Scenario: a row missing the command is rejected
@test "a row missing the command is rejected, naming the field" {
  run "$LEDGER" append --file "$LF" --change c --spec s --ring R3 \
    --obligation o --artifact a --exit 0 --baseline "$BASE"
  assert_status 1 "$status" "a missing required field is a finding"
  assert_contains "$output" "command" "the writer must name the missing field"
  [ ! -s "$LF" ] || {
    printf 'a rejected append must write nothing\n' >&2
    return 1
  }
}

@test "a row with an out-of-domain ring is rejected" {
  RING="R99" run app
  assert_status 1 "$status" "the ring domain is closed by the approved contract"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The ledger is append-only through its writer
# ═════════════════════════════════════════════════════════════════════════

# spec: evidence-ledger — Scenario: earlier rows survive an append
@test "earlier rows survive an append byte-for-byte" {
  OBL="first" app
  OBL="second" app
  OBL="third" app
  local before after
  before="$(cat "$LF")"
  OBL="fourth" app
  after="$(head -3 "$LF")"
  [ "$before" = "$after" ] || {
    printf 'prior rows changed across an append\n' >&2
    diff <(printf '%s' "$before") <(printf '%s' "$after") >&2 || true
    return 1
  }
  [ "$(rows_in "$LF")" -eq 4 ]
}

# spec: evidence-ledger — Scenario: a field containing a record-framing character cannot corrupt a row
@test "a command containing newline, quote and backslash stays exactly one row" {
  CMD="$(printf 'printf "a\nb" | grep \\"x\\"')" app
  assert_status 0 "$status" "framing characters are legal content"
  [ "$(rows_in "$LF")" -eq 1 ] || {
    printf 'expected 1 row, got %s — framing leaked into the record\n' "$(rows_in "$LF")" >&2
    return 1
  }
  all_rows_conform "$LF"
  local got
  got="$(jq -r '.command' <"$LF")"
  [ "$got" = "$(printf 'printf "a\nb" | grep \\"x\\"')" ] || {
    printf 'command did not round-trip.\n got: %s\n' "$got" >&2
    return 1
  }
}

# spec: evidence-ledger — Scenario: an attempt to modify an existing row is refused
@test "a modifying invocation is refused and leaves the file unchanged" {
  OBL="original" app
  local before
  before="$(cat "$LF")"
  run "$LEDGER" update --file "$LF" --row 1 --obligation "tampered"
  # Exit 1 specifically, not merely "not 0": a refusal is a FINDING the writer
  # made deliberately. Accepting any non-zero would also accept 127 (no such
  # binary) and 2 (undetermined), so the test would pass on a typo'd
  # subcommand name and prove nothing about append-only enforcement.
  assert_status 1 "$status" "a modifying invocation must be refused as a finding"
  assert_contains "$output" "append" "the refusal must say why — append-only"
  [ "$(cat "$LF")" = "$before" ] || {
    printf 'the file changed despite the refusal\n' >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: A ledger row is scoped to one change and one spec
# ═════════════════════════════════════════════════════════════════════════

# spec: evidence-ledger — Scenario: rows from another change are excluded
@test "reading evidence for one change excludes another change's rows" {
  CHG="change-a" OBL="a1" app
  CHG="change-b" OBL="b1" app
  run "$LEDGER" read --file "$LF" --change change-a
  assert_status 0 "$status" "a successful read"
  assert_contains "$output" "a1" "change-a's row must be present"
  assert_not_contains "$output" "b1" "change-b's row must be excluded"
}

# spec: evidence-ledger — Scenario: rows from an earlier spec of the same change are retained
@test "reading a change retains rows from all of its specs, each attributed" {
  SPC="spec-one" OBL="s1" app
  SPC="spec-two" OBL="s2" app
  run "$LEDGER" read --file "$LF" --change add-correctness-substratum
  assert_status 0 "$status" "a successful read"
  assert_contains "$output" "s1" "spec-one's row"
  assert_contains "$output" "s2" "spec-two's row"
  assert_contains "$output" "spec-one" "attribution to its own spec"
  assert_contains "$output" "spec-two" "attribution to its own spec"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: A stale row is distinguishable from a current one
# ═════════════════════════════════════════════════════════════════════════

# spec: evidence-ledger — Scenario: a row from the current baseline discharges
@test "a row at the current baseline discharges its obligation" {
  BSL="$BASE" OBL="current-row" app
  run "$LEDGER" read --file "$LF" --change add-correctness-substratum --baseline "$BASE"
  assert_status 0 "$status" "a successful read"
  assert_contains "$output" "current-row" "the current-baseline row must discharge"
}

# spec: evidence-ledger — Scenario: a row from a superseded baseline does not discharge
@test "a row from a superseded baseline is reported stale and does not discharge" {
  BSL="0000aaa" OBL="stale-row" app
  run "$LEDGER" read --file "$LF" --change add-correctness-substratum --baseline "$BASE"
  assert_status 0 "$status" "reading is successful; the row simply does not discharge"
  assert_not_contains "$output" "stale-row" "a superseded row must not be returned as discharging"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: A malformed ledger fails loudly
# ═════════════════════════════════════════════════════════════════════════

# spec: evidence-ledger — Scenario: a truncated final row is reported, not ignored
@test "a truncated final row is reported with its line number and is undetermined" {
  OBL="good" app
  printf '{"v":1,"ts":"2026-08-08T09:00:0\n' >>"$LF"
  run "$LEDGER" read --file "$LF" --change add-correctness-substratum
  assert_status 2 "$status" "an unreadable ledger is UNDETERMINED, never a finding and never clean"
  assert_contains "$output" "2" "the failure must name the offending line number"
}

# spec: evidence-ledger — Scenario: an absent ledger is distinguished from an empty one
@test "an absent ledger is reported distinctly from one holding zero rows" {
  run "$LEDGER" read --file "$BATS_TEST_TMPDIR/does-not-exist.jsonl" --change c
  local absent_status="$status" absent_out="$output"
  : >"$LF"
  run "$LEDGER" read --file "$LF" --change c
  local empty_status="$status" empty_out="$output"
  [ "$absent_out" != "$empty_out" ] || {
    printf 'absent and empty produced identical output: %s\n' "$absent_out" >&2
    return 1
  }
  assert_contains "$absent_out" "no ledger" "the absent case must say so"
  assert_status 0 "$empty_status" "an existing ledger with zero rows is a successful measurement"
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Every row carries a format version and an unknown one halts the read
# ═════════════════════════════════════════════════════════════════════════

# spec: evidence-ledger — Scenario: a recognised version reads normally
@test "rows at the recognised format version are all read" {
  OBL="r1" app
  OBL="r2" app
  run "$LEDGER" read --file "$LF" --change add-correctness-substratum
  assert_status 0 "$status" "a successful read"
  assert_contains "$output" "r1" "first row"
  assert_contains "$output" "r2" "second row"
}

# spec: evidence-ledger — Scenario: an unrecognised version halts the whole read
@test "one unrecognised version halts the whole read rather than skipping the row" {
  local i
  for i in 1 2 3 4 5 6 7 8 9; do OBL="row$i" app; done
  jq -c '.v = 99 | .obligation = "future-row"' <<<"$(head -1 "$LF")" >>"$LF"
  run "$LEDGER" read --file "$LF" --change add-correctness-substratum
  assert_status 2 "$status" "an unknown version makes the read UNDETERMINED"
  assert_not_contains "$output" "row1" "the other rows must NOT be returned as a complete result"
}

# spec: evidence-ledger — Scenario: a row with no version field is rejected
@test "a stored row with no version field makes the read undetermined" {
  # RESTATED after the polarity run. The first version invoked a `--no-version`
  # flag and asserted `status -ne 0`, which PASSED before any implementation
  # existed — a missing binary exits 127, which is also "not 0". It was
  # proving nothing.
  #
  # It was also unreachable through the approved CLI: the writer stamps `v`
  # itself, so a caller cannot supply an append without one. The reachable
  # form of the same invariant is the READ path, which is what this asserts.
  # (The spec/CLI mismatch is raised at the Step 2 gate, not papered over.)
  OBL="good" app
  jq -c 'del(.v)' <<<"$(head -1 "$LF")" >>"$LF"
  run "$LEDGER" read --file "$LF" --change add-correctness-substratum
  assert_status 2 "$status" "a row with no version is unreadable, hence UNDETERMINED"
}

# ═════════════════════════════════════════════════════════════════════════
# Properties (Ring 3) — ENUMERATED over finite listed domains.
# Bash has no generator or shrinker; each property states its domain, and the
# domain is built, never filtered.
# ═════════════════════════════════════════════════════════════════════════

# The awkward-value set, shared by P1 and P2. Declared once so the two
# properties cannot drift apart about what "awkward" means.
awkward_values() {
  printf '%s\n' \
    'plain' \
    "$(printf 'has\nnewline')" \
    'has "double" quote' \
    'has \back\slash' \
    "$(printf 'has\ttab')" \
    'has ünïcøde ✓' \
    ''
}

# WIDENED at Ring 8. The empty string was absent although BOTH P1 ("empty
# optional field") and P2 ("a field that is the empty string") declare it. The
# approved contract has no optional fields, so an empty value is REJECTED as
# missing — that is the behaviour, and it must be asserted, not skipped.

# spec: evidence-ledger — Property: Append preserves all prior rows
@test "PROPERTY append preserves all prior rows across sizes and awkward values" {
  # Generator strategy (enumerated): starting ledgers of size 0, 1, 2 and 10,
  # crossed with the awkward-value set, and crossed again with the ledger's
  # TRAILING-NEWLINE SHAPE.
  #
  # WIDENED at Ring 8. The first version varied only size, and every starting
  # ledger was writer-produced, so all of them ended in a newline. The shape
  # that breaks the invariant — a final line with no trailing newline, which
  # the READER explicitly supports — was outside the domain, and the append
  # concatenated onto the prior row. Enumerating sizes but not shapes is how a
  # property covers everything except the case that matters.
  local size val before shape
  for shape in trailing-newline no-trailing-newline; do
  for size in 0 1 2 10; do
    : >"$LF"
    local i
    i=0
    while [ "$i" -lt "$size" ]; do
      OBL="seed$i" app
      i=$((i + 1))
    done
    if [ "$shape" = no-trailing-newline ] && [ -s "$LF" ]; then
      printf '%s' "$(cat "$LF")" >"$LF"   # strip the final newline
    fi
    before="$(cat "$LF")"
    while IFS= read -r val; do
      # An empty command is rejected (no optional fields), so the invariant to
      # assert is that a REJECTED append also preserves every prior row and
      # adds none. bats aborts a test on any failing command, so the call is
      # guarded before the assertion can run.
      if [ -z "$val" ]; then
        CMD="$val" OBL="probe" app && {
          printf 'an empty command was accepted at shape=%s size=%s\n' "$shape" "$size" >&2
          return 1
        }
        [ "$(cat "$LF")" = "$before" ] || {
          printf 'a REJECTED append altered the file at shape=%s size=%s\n' "$shape" "$size" >&2
          return 1
        }
        continue
      fi
      CMD="$val" OBL="probe" app
      [ "$(head -"$size" "$LF" 2>/dev/null)" = "$before" ] || {
        printf 'prior rows changed at shape=%s size=%s value=%q\n' "$shape" "$size" "$val" >&2
        return 1
      }
      [ "$(rows_in "$LF")" -eq $((size + 1)) ] || {
        printf 'row count wrong at shape=%s size=%s value=%q: got %s\n' "$shape" "$size" "$val" "$(rows_in "$LF")" >&2
        return 1
      }
      all_rows_conform "$LF" || {
        printf 'a row stopped conforming at shape=%s size=%s value=%q\n' "$shape" "$size" "$val" >&2
        return 1
      }
      # restore for the next value in the cross product
      head -"$size" "$LF" >"$LF.tmp" 2>/dev/null || : >"$LF.tmp"
      mv "$LF.tmp" "$LF"
      if [ "$shape" = no-trailing-newline ] && [ -s "$LF" ]; then
        printf '%s' "$(cat "$LF")" >"$LF"
      fi
    done < <(awkward_values)
  done
  done
}

# spec: evidence-ledger — Property: Write-then-read round-trips every field
@test "PROPERTY write-then-read round-trips every field with every awkward value" {
  # Generator strategy (enumerated): the awkward-value set applied to every
  # free-text field position in turn, so each field is independently exercised.
  local field val got
  for field in obligation artifact command; do
    while IFS= read -r val; do
      : >"$LF"
      if [ -z "$val" ]; then
        case "$field" in
          obligation) OBL="$val" app || true ;;
          artifact) ART="$val" app || true ;;
          command) CMD="$val" app || true ;;
        esac
      else
        case "$field" in
          obligation) OBL="$val" app ;;
          artifact) ART="$val" app ;;
          command) CMD="$val" app ;;
        esac
      fi
      # The empty string is in the declared domain but cannot round-trip: the
      # approved contract has NO optional fields, so an empty value is rejected
      # as missing and nothing is written. Assert that behaviour instead of
      # dropping the value from the enumeration.
      if [ -z "$val" ]; then
        [ ! -s "$LF" ] || {
          printf 'an empty %s was accepted; the contract has no optional fields\n' "$field" >&2
          return 1
        }
        continue
      fi
      got="$(jq -r ".$field" <"$LF")"
      [ "$got" = "$val" ] || {
        printf 'field %s did not round-trip.\n sent: %q\n got:  %q\n' "$field" "$val" "$got" >&2
        return 1
      }
    done < <(awkward_values)
  done
}

# spec: evidence-ledger — Property: Evidence reads are scoped to their change and baseline
@test "PROPERTY evidence reads return exactly the rows matching change and baseline" {
  # Generator strategy (enumerated): the cross product of two change names, two
  # spec names and two baselines, so every combination of match and mismatch on
  # both axes is present. Expected membership is computed from the
  # construction, not read back from the tool.
  local c s b
  : >"$LF"
  for c in change-a change-b; do
    for s in spec-one spec-two; do
      for b in aaaaaaa bbbbbbb; do
        CHG="$c" SPC="$s" BSL="$b" OBL="tag-$c-$s-$b" app
      done
    done
  done
  for c in change-a change-b; do
    for b in aaaaaaa bbbbbbb; do
      run "$LEDGER" read --file "$LF" --change "$c" --baseline "$b"
      assert_status 0 "$status" "read $c/$b"
      local other_c other_b
      other_c=$([ "$c" = change-a ] && echo change-b || echo change-a)
      other_b=$([ "$b" = aaaaaaa ] && echo bbbbbbb || echo aaaaaaa)
      assert_contains "$output" "tag-$c-spec-one-$b" "matching row must be present"
      assert_contains "$output" "tag-$c-spec-two-$b" "matching row must be present"
      assert_not_contains "$output" "tag-$other_c-" "another change's rows must be excluded"
      assert_not_contains "$output" "-$other_b" "another baseline's rows must be excluded"
    done
  done
}

# spec: evidence-ledger — Property: A malformed ledger never reads as empty
@test "PROPERTY every corruption yields undetermined, never a clean empty read" {
  # Generator strategy (enumerated): a listed corruption set applied to a
  # known-good ledger. Constructive — each corruption is applied deliberately.
  local corruption
  # WIDENED at Ring 8: the first set held only CONTENT corruptions of a
  # readable file, so the three inputs that actually read as clean —
  # unreadable file, directory, newline-only file — were outside the domain.
  # The spec names "a file of only whitespace"; that was implemented as a
  # whitespace LINE appended to a good ledger, which is strictly easier.
  for corruption in truncated missing-field unparseable binary whitespace \
    unknown-version newline-only blank-line-mid-file unreadable directory; do
    : >"$LF"
    OBL="good1" app
    OBL="good2" app
    case "$corruption" in
      truncated) printf '{"v":1,"ts":"2026-08-08T0\n' >>"$LF" ;;
      missing-field) jq -c 'del(.command)' <<<"$(head -1 "$LF")" >>"$LF" ;;
      unparseable) printf 'this is not json at all\n' >>"$LF" ;;
      binary) printf '\001\002\003\n' >>"$LF" ;;
      whitespace) printf '   \t  \n' >>"$LF" ;;
      unknown-version) jq -c '.v = 99' <<<"$(head -1 "$LF")" >>"$LF" ;;
      newline-only) printf '\n' >"$LF" ;;
      blank-line-mid-file) sed -i '1a\\' "$LF" ;;
      unreadable) chmod 000 "$LF" ;;
      directory) rm -f "$LF" && mkdir -p "$LF" ;;
    esac
    run "$LEDGER" read --file "$LF" --change add-correctness-substratum
    [ "$corruption" != unreadable ] || chmod 644 "$LF"
    [ "$corruption" != directory ] || rmdir "$LF"
    [ "$status" -eq 2 ] || {
      printf 'corruption %q gave status %s; expected 2 (undetermined)\n' "$corruption" "$status" >&2
      return 1
    }
    assert_not_contains "$output" "good1" "a corrupt ledger must not return rows as a complete result"
  done
}

# ═════════════════════════════════════════════════════════════════════════
# Ring 4 — compatibility. The committed fixture is the baseline; it may NEVER
# be regenerated to make a test pass.
# ═════════════════════════════════════════════════════════════════════════

# spec: evidence-ledger — Requirement: A ledger row records an observed run, not an intention
@test "RING4 the committed v1 fixture still parses and reads" {
  [ -f "$FIXTURE" ] || {
    printf 'Ring 4 fixture missing: %s\n' "$FIXTURE" >&2
    return 1
  }
  run "$LEDGER" read --file "$FIXTURE" --change add-correctness-substratum --baseline 291eb22
  assert_status 0 "$status" "a fixture written by this version must still read"
  assert_contains "$output" "shellcheck clean" "a known row from the fixture"
  assert_not_contains "$output" "Scoping guard" "the fixture's other-change row must be excluded"
}

# spec: evidence-ledger — Requirement: A ledger row records an observed run, not an intention
@test "RING4 GREEN-BY-DESIGN every fixture row satisfies the approved contract" {
  # Green before implementation and must stay green: this guards the FIXTURE
  # and the CONTRACT against drift, neither of which depends on ledger.sh.
  [ -f "$FIXTURE" ] || return 1
  all_rows_conform "$FIXTURE" || {
    printf 'a committed fixture row no longer satisfies the contract\n' >&2
    return 1
  }
  [ "$(rows_in "$FIXTURE")" -eq 5 ]
}

# spec: evidence-ledger — Requirement: A ledger row records an observed run, not an intention
@test "RING4 GREEN-BY-DESIGN the approved contract rejects a row missing a required field" {
  # Polarity guard for the contract itself: proves it can fail, so the two
  # tests above are not passing because the contract accepts anything.
  local bad
  bad="$(jq -c 'del(.command)' <<<"$(head -1 "$FIXTURE")")"
  printf '%s' "$bad" | jq -e -f "$CONTRACT" >/dev/null 2>&1 && {
    printf 'the contract accepted a row with no command\n' >&2
    return 1
  }
  return 0
}
