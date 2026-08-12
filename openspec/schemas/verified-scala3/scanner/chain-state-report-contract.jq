# chain-state-report-contract.jq — the CHAIN STATE REPORT CONTRACT.
#
# spec: chain-state (change: add-correctness-substratum), Step 1 typed
# contract. Analogue of "genuinely compiled" for a shell tool: this file IS
# the contract and its own checker. `jq -e -f` it over a report and it returns
# the report on conformance, or fails naming the first violated clause.
#
# scanner/chain-state.sh and tests/chain-state.bats must BOTH validate against
# this file rather than restating the shape, so oracle and implementation
# cannot drift by disagreeing about what a report is.
#
# REVISED AT RING 8 (fresh-context pass): two gaps found.
#   1. The UNDETERMINED report shape (chain-state.sh's die_undetermined)
#      never validated against this file at all — it is now an explicit
#      second branch, so "the single statement of the report shape" is true
#      for BOTH outcomes chain-state.sh can ever print.
#   2. The self-check could not fail on MISCLASSIFICATION, only on shape
#      violations — the counts (bound/resolved/discharged) were computed by
#      the SAME code path that built `unresolved`, so they could never
#      disagree with each other by construction, even when the underlying
#      classification was wrong. A CROSS-CHECK now ties the counts to the
#      actual reasons carried in `unresolved`, so a title silently
#      miscounted as "ok" without a corresponding entry is now a contract
#      violation, not merely an untested possibility.
#   3. A new reason, "unattributable", was added: a title spec-lint's own F7
#      calls bound, for which chain-state's narrower Source-title matcher
#      finds NO obligation row at all. Reporting such a title as vacuously
#      resolved+discharged (the pre-Ring-8 default) was the exact "claim
#      outran evidence" defect this project exists to remove.
#
# Usage: echo '<report>' | jq -e -f chain-state-report-contract.jq

def fail($clause): error("chain-state-report-contract: " + $clause);

def valid_reasons: ["unbound", "unresolved", "undischarged", "unattributable"];

# ── branch 1: the UNDETERMINED shape ─────────────────────────────────────
if (type == "object" and (.undetermined // false) == true) then
  if (["change","baseline","undetermined","reason"] - keys_unsorted) != [] then
    fail("undetermined report missing required field(s): " +
      ((["change","baseline","undetermined","reason"] - keys_unsorted) | join(", ")))
  elif (.change | type) != "string" or (.change | length) == 0 then
    fail("undetermined report: change must be a non-empty string")
  elif (.baseline | type) != "string" or (.baseline | length) == 0 then
    fail("undetermined report: baseline must be a non-empty string")
  elif (.reason | type) != "string" or (.reason | length) == 0 then
    fail("undetermined report: reason must be a non-empty string — undetermined is a stated fact, never a silent nothing")
  # The measured fields must be explicitly absent-of-value (null), never a
  # number: an undetermined report that also carried real-looking counts
  # would be indistinguishable from a measurement by a careless reader.
  elif ([.total, .bound, .resolved, .discharged] | map(. != null) | any) then
    fail("undetermined report must not carry numeric total/bound/resolved/discharged — use null")
  elif (.unresolved != []) or (.unmapped_obligations != []) then
    fail("undetermined report must carry empty unresolved/unmapped_obligations — nothing was determined, so nothing can be listed")
  else .
  end

elif type != "object" then fail("a report must be a JSON object")

# ── branch 2: the MEASURED shape ─────────────────────────────────────────
elif (["change","baseline","total","bound","resolved","discharged",
       "unresolved","unmapped_obligations"] - keys_unsorted) != [] then
  fail("missing required field(s): " +
    ((["change","baseline","total","bound","resolved","discharged",
       "unresolved","unmapped_obligations"] - keys_unsorted) | join(", ")))

elif (.change | type) != "string" or (.change | length) == 0 then
  fail("change must be a non-empty string")
elif (.baseline | type) != "string" or (.baseline | length) == 0 then
  fail("baseline must be a non-empty string")

# Every count is a non-negative integer. Not merely "a number": a float count
# ("2.5 requirements bound") is meaningless, and a negative one is nonsense
# this contract must reject rather than let a caller discover downstream.
elif ([.total, .bound, .resolved, .discharged]
      | map(type != "number" or floor != . or . < 0) | any) then
  fail("total, bound, resolved and discharged must each be a non-negative integer")

# THE MONOTONICITY LAW — Property: Counts never exceed the total and are
# monotone across clauses. Stated here as a STRUCTURAL constraint on every
# report this tool can ever emit, not only as a property test run over
# constructed inputs: a contract violation is caught the instant it occurs,
# not only when a test happens to construct the offending case.
elif (.discharged > .resolved) then
  fail("discharged (" + (.discharged|tostring) + ") must not exceed resolved (" + (.resolved|tostring) + ")")
elif (.resolved > .bound) then
  fail("resolved (" + (.resolved|tostring) + ") must not exceed bound (" + (.bound|tostring) + ")")
elif (.bound > .total) then
  fail("bound (" + (.bound|tostring) + ") must not exceed total (" + (.total|tostring) + ")")

elif (.unresolved | type) != "array" then fail("unresolved must be an array")
elif (.unresolved | length) != (.total - .discharged) then
  fail("unresolved list has " + (.unresolved|length|tostring) +
    " entries; total - discharged = " + ((.total - .discharged)|tostring) +
    " — the Property 2 law (list size == total - discharged)")

# Every unresolved entry names WHICH requirement and WHY, per Requirement
# "Unresolved entries are named, not just counted" — a count alone is what
# this contract exists to make structurally impossible to emit alone.
elif (.unresolved | map(
        (has("spec") | not) or (has("requirement") | not) or (has("reasons") | not) or
        ((.spec // "") | length) == 0 or
        ((.requirement // "") | length) == 0 or
        ((.reasons | type) != "array") or ((.reasons | length) == 0) or
        (.reasons | map(. as $r | (valid_reasons | index($r)) == null) | any)
      ) | any) then
  fail("every unresolved entry must name a non-empty spec, requirement, and at least one reason from " +
    (valid_reasons | join(", ")))

# No entry names a reason twice — "each of the three appears... with reasons
# distinguishing" implies a set, not a multiset.
elif (.unresolved | map(.reasons | (length) != (unique | length)) | any) then
  fail("an unresolved entry's reasons must not repeat")

# No (spec, requirement) pair appears twice — a requirement is EITHER fully
# satisfied (absent from the list) or has exactly one entry naming why. Two
# entries for the same requirement is not a shape a correct classifier can
# ever produce.
elif ((.unresolved | map({spec, requirement}) | length) !=
      (.unresolved | map({spec, requirement}) | unique | length)) then
  fail("the same (spec, requirement) pair appears more than once in unresolved")

# ── CROSS-CONSISTENCY (Ring 8): counts must agree with the reasons actually
# carried in `unresolved`, not merely with each other. Before this check, the
# self-check could not detect a MISCLASSIFICATION (e.g. a bound-but-
# unattributable title silently counted as "ok"): bound/resolved/discharged
# and the unresolved list were built from the same source in one pass, so
# they agreed by construction regardless of whether the classification
# itself was correct. This ties the reported counts to independently
# recountable evidence within the report.
# NOTE (found while testing this contract): `.reasons | index("x") or
# (.reasons | index("y"))` is NOT what it looks like — jq's `|` binds looser
# than `or`, so the parenthesised right-hand side re-pipes through the
# ALREADY-PIPED `.` (which by then is the reasons array itself), and
# `.reasons` on an array fails with "Cannot index array with string". Binding
# `.reasons` to a variable once avoids the ambiguity entirely.
elif ((.total - .bound) !=
      (.unresolved | map(select(.reasons as $r | $r | index("unbound"))) | length)) then
  fail("total - bound (" + ((.total - .bound)|tostring) +
    ") must equal the number of unresolved entries reasoned \"unbound\" (" +
    ((.unresolved | map(select(.reasons as $r | $r | index("unbound"))) | length)|tostring) + ")")
elif ((.bound - .resolved) !=
      (.unresolved | map(select(.reasons as $r | ($r | index("unresolved")) or ($r | index("unattributable")))) | length)) then
  fail("bound - resolved must equal the number of unresolved entries reasoned \"unresolved\" or \"unattributable\"")
elif ((.resolved - .discharged) !=
      (.unresolved | map(select(.reasons as $r | $r | index("undischarged"))) | length)) then
  fail("resolved - discharged must equal the number of unresolved entries reasoned \"undischarged\"")

# unmapped_obligations: obligations whose Source is not resolvable to a single
# requirement via the "Requirement: <title>" preferred form, but which DO
# carry an F9 (unresolved-artifact) finding. A DELIBERATE, STATED scope limit
# (see design.md) — never silently dropped, never misattributed to a
# requirement chain-state cannot actually identify.
elif (.unmapped_obligations | type) != "array" then
  fail("unmapped_obligations must be an array")
elif (.unmapped_obligations | map(
        (has("spec") | not) or (has("line") | not) or (has("artifact") | not) or
        ((.spec // "") | length) == 0 or ((.artifact // "") | length) == 0 or
        (.line | type) != "number" or (.line | floor) != .line or .line < 1
      ) | any) then
  fail("every unmapped_obligations entry must name a non-empty spec, a positive integer line, and a non-empty artifact")

else .
end
