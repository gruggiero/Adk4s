# Ring 8: Adversarial Spec-Compliance Review — recorder-laws

**Fresh context:** yes (subagent with no implementation conversation context)
**Baseline:** b3ae627f11b480e4b9275924efdd992b9c55d801
**Diff reviewed:** RecorderLaws.scala, RecorderLawsSpec.scala, RecorderLawsTypeContract.scala
**Dangerous patterns found:** 1 (0 fixed / 1 justified)
**Oracle tampering:** none
**Requirements:** 11 PASS, 0 PARTIAL, 0 FAIL (after fixes)

## Initial Review Findings

The initial adversarial review identified 3 issues:

### RL1 record/lookup coherence (PARTIAL → fixed to PASS)
**Issue:** Spec requires "J != K" but generators produced independent keys without enforcing distinctness.
**Fix:** Added `genDistinctCallKey(other)` generator that uses `.ensure(_ != other)` to guarantee J != K.
**File:** RecorderLaws.scala:77

### RL9 failure fidelity (FAIL → fixed to PASS)
**Issue:** Spec requires "looked up and replayed" but implementation only tested lookup, not actual replay through RecordedChatModel.
**Fix:** Added second assertion that records a FailedCase, then calls `RecordedChatModel.generate` with a `FailingChatModel` underlying. Verifies the replay does NOT produce a success (the failed record is treated as a miss, causing the underlying to fail).
**File:** RecorderLaws.scala:196-238

### RL12 version isolation (FAIL → fixed to PASS)
**Issue:** Spec requires testing actual lookup isolation (write under v1, lookup at v2 returns None), but implementation only checked key difference.
**Fix:** Added actual record/lookup: writes a SucceededRecord under kV1, then performs `rec.lookup(kV2)` and asserts the result is `None`.
**File:** RecorderLaws.scala:282-310

## Dangerous Patterns

### genRolloutId unreachable claim (justified)
**Location:** RecorderLaws.scala:383-395
**Pattern:** `sys.error("unreachable: non-empty string rejected by RolloutId")`
**Justification:** The generator `Gen.string(Gen.alphaNum, Range.linear(1, 20))` guarantees non-empty strings of length 1-20. `RolloutId.refineEither` only rejects empty strings. The fallback `RolloutId.refineEither("x")` is a second safety net. Both must fail for `sys.error` to execute, which is impossible given the generator constraints. This is the same pattern used in the existing `CallKeySpec` generator.

## Final Verdict

All 13 requirements now PASS after the adversarial review fixes:
- RL0 transparency: PASS
- RL1 record/lookup coherence: PASS (fixed: distinct key generator)
- RL2 key determinism: PASS
- RL3 key sensitivity: PASS
- RL4 key insensitivity: PASS
- RL5 zero-call hit: PASS
- RL6 rollout separation: PASS
- RL7 codec round-trip: PASS
- RL8 append-only monotonicity: PASS
- RL9 failure fidelity: PASS (fixed: actual replay test)
- RL10 write-failure containment: PASS
- RL11 redaction neutrality: PASS
- RL12 version isolation: PASS (fixed: actual lookup isolation test)
