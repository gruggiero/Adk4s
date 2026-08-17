# ledger-record-contract.jq — the EVIDENCE LEDGER RECORD CONTRACT.
#
# spec: evidence-ledger (change: add-correctness-substratum), Step 1 typed
# contract. This spec ships shell, so there is nothing to compile; the analogue
# of "genuinely compiled" is GENUINELY EXECUTABLE. This file is the contract
# and also the checker for it: `jq -e -f` it over a record and it returns the
# record on conformance, or fails naming the first violated clause.
#
# The implementation (scanner/ledger.sh) and the oracle
# (tests/evidence-ledger.bats) must BOTH conform to this file. It is the single
# statement of the format; a second copy in either would be free to disagree.
#
# Usage:
#   echo '<record>' | jq -e -f ledger-record-contract.jq   # exit 0 = conforms
#
# Emits the offending clause on stderr via `error`, so a failure says WHICH
# clause, not merely that something failed.

def fail($clause): error("ledger-record-contract: " + $clause);

# ── required fields ──────────────────────────────────────────────────────
# Every field is required. There are no optional fields by design: a row that
# omits a field would be a row whose evidence is partially unrecorded, which is
# the state the ledger exists to make impossible.
def required: [
  "v", "ts", "change", "spec", "ring", "obligation", "artifact", "command",
  "exit", "baseline"
];

# ── the closed ring domain ───────────────────────────────────────────────
# Closed on purpose. An unrecognised ring must be REJECTED at write time, not
# recorded and later rendered as an unknown row: a checkpoint generated from
# rows it cannot interpret would report evidence it does not have.
def rings: ["R0","R1","R2","R3","R4","R5","R6","R7","R8","R9","manual"];

# ── the contract ─────────────────────────────────────────────────────────
if type != "object" then fail("a record must be a JSON object")

elif (required - (keys_unsorted)) != [] then
  fail("missing required field(s): " + ((required - keys_unsorted) | join(", ")))

# v — the format version. Ring 4's forward-compatibility hinge. A reader that
# does not recognise it must report UNDETERMINED, never skip the row.
elif (.v | type) != "number" or (.v | floor) != .v then
  fail("v must be an integer")
elif .v < 1 then fail("v must be >= 1")

# ts — ISO-8601 UTC. Fixed shape so ordering is lexicographic.
elif (.ts | type) != "string" then fail("ts must be a string")
elif (.ts | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$") | not) then
  fail("ts must be ISO-8601 UTC, e.g. 2026-08-08T12:34:56Z")

# change / spec — the scoping fields. A path separator would let a row escape
# its own change when evidence is read, so it is rejected structurally.
elif (.change | type) != "string" or (.change | length) == 0 then
  fail("change must be a non-empty string")
elif (.change | test("[/\\\\]")) then fail("change must not contain a path separator")
elif (.spec | type) != "string" or (.spec | length) == 0 then
  fail("spec must be a non-empty string")
elif (.spec | test("[/\\\\]")) then fail("spec must not contain a path separator")

# ring — closed domain.
elif (.ring | type) != "string" then fail("ring must be a string")
# `.ring as $r` first: piping into `rings` rebinds `.` to the array, so a bare
# `rings | index(.ring)` indexes the ARRAY by a string and errors out — and
# because this sits mid-elif-chain, every clause below it inherits the error.
elif (.ring as $r | rings | index($r)) == null then
  fail("ring must be one of: " + (rings | join(", ")))

# obligation / artifact — free text, but present and non-empty: a row that
# names no obligation discharges nothing.
elif (.obligation | type) != "string" or (.obligation | length) == 0 then
  fail("obligation must be a non-empty string")
elif (.artifact | type) != "string" or (.artifact | length) == 0 then
  fail("artifact must be a non-empty string")

# command — what makes the row RE-CHECKABLE. Arbitrary content (quotes,
# newlines and backslashes are expected), but never empty: a row without the
# command it ran is a self-report, which is what the ledger replaces.
elif (.command | type) != "string" or (.command | length) == 0 then
  fail("command must be a non-empty string")

# exit — the status the command returned. Integer, so comparison is total; a
# string "0" would compare unequal to 0 forever.
elif (.exit | type) != "number" or (.exit | floor) != .exit then
  fail("exit must be an integer")

# baseline — the revision the run applies to. Staleness is decided by exact
# comparison against the spec's Step 0 baseline, so a loose shape would let a
# superseded row read as current.
elif (.baseline | type) != "string" then fail("baseline must be a string")
elif (.baseline | test("^[0-9a-f]{7,40}$") | not) then
  fail("baseline must be a lowercase hex revision of 7-40 chars")

# ── optional fields (D3: evidence-capture) ──────────────────────────────
# These are present in rows written by `run` mode and absent in legacy
# `append` rows. Both shapes are valid; the fields are checked only if
# present, so a heterogeneous ledger (mixed legacy + capture) reads cleanly.
elif (has("sha256") and (.sha256 | type) != "string") then
  fail("sha256 must be a string when present")
elif (has("digest") and (.digest | type) != "string") then
  fail("digest must be a string when present")
elif (has("wallTime") and ((.wallTime | type) != "number" or (.wallTime | floor) != .wallTime)) then
  fail("wallTime must be an integer when present")

# ── observer provenance (ambient capture wiring) ─────────────────────────
# WHO OBSERVED the exit this row records. Absent means a bare `append`: the
# agent typed the exit code, so the row is testimony. "ambient" means the
# gate wrote it from the harness's own post-tool payload, so the row is a
# witness. `run` rows are told apart by their `digest` (the script observed
# its own exit), not by this field.
#
# Closed domain, for the same reason `rings` is closed: an unrecognised
# provenance must be REJECTED at write time. Accepting one would let a row
# claim an observer that nothing defines, and reconcile.sh would then either
# count it as a witness it is not, or silently ignore it — both of which
# turn an unverifiable claim back into a clean-looking result.
elif (has("source") and ((.source | type) != "string" or .source != "ambient")) then
  fail("source must be \"ambient\" when present")

# ── session provenance (spec:judgment-ring-provenance) ───────────────────
# R8 (adversarial-review) rows MUST carry a `session` field identifying the
# producing session — the checkpoint compares it to the implementing session
# to enforce the fresh-context mandate. Non-R8 rows MAY carry `session`
# (optional); when present it must be a non-empty string.
elif (.ring == "R8" and (has("session") | not)) then
  fail("session is required for R8 (adversarial-review) rows — the checkpoint needs it to enforce the fresh-context mandate")
elif (.ring == "R8" and has("session") and ((.session | type) != "string" or (.session | length) == 0)) then
  fail("session must be a non-empty string for R8 rows")
elif (has("session") and ((.session | type) != "string" or (.session | length) == 0)) then
  fail("session must be a non-empty string when present")

else .
end
