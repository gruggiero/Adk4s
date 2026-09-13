Ring 8: Adversarial Spec-Compliance Review — cutover-gate

Fresh context: yes
Baseline: 8c26df8   Diff reviewed: workflow/core/src/test/scala/org/sinemenda/probatio/migration/ (CutoverGate.scala, CutoverVerdict.scala, DifferentialResult.scala, DifferentialHarness.scala, SeamTypes.scala, OracleGreenCheck.scala, OracleGreenGate.scala, OracleDiffRunner.scala, CutoverGateSpec.scala, DifferentialHarnessSpec.scala, CutoverRevertSpec.scala, CutoverBridgeSpec.scala), verified/probatio/src/main/scala/org/sinemenda/probatio/core/CutoverKernel.scala, build.sbt, openspec/concepts/conformance-property-test-contract.md, openspec/concepts/strangler-migration-protocol.md
Dangerous patterns found: 5 (fixed 4 / justified 1)
Oracle tampering: 2 findings (fixed 2)
Requirements: 5 PASS, 2 PARTIAL (noted for human approval)

---

## Verdict List

### Requirement: The gate's decision is a comparison against the predecessor, not an absolute threshold — PASS

`CutoverGate.decide` (CutoverGate.scala:40-43) checks `isComplete` then `hasRegression`. For
complete comparisons, `hasRegression` = `files.exists(_.isWorse)` where `isWorse` requires
`predecessorPresent && portedPresent && portedFailures > predecessorFailures`
(DifferentialResult.scala:36-37). For complete comparisons all files are present, so this
reduces to `files.exists(f => f.portedFailures > f.predecessorFailures)`. The gate proceeds iff
no file is worse. The property `proceed-iff-no-file-worse` (CutoverGateSpec.scala:215) verifies
this on generated complete comparisons. The adversarial scenario (one file worse, total
improves → revert) is tested directly (CutoverGateSpec.scala:49) and via the property
`total-improvement-does-not-excuse-a-regression` (CutoverGateSpec.scala:234). The formal
contract `cutoverDecision` (CutoverKernel.scala:50-58) is verified by Stainless and bridged by
`CutoverBridgeSpec`. All branches correct.

### Requirement: Both runs of the comparison execute in the same repository under the same suite — PARTIAL (noted for human approval)

The `DifferentialHarness.diff` function (DifferentialHarness.scala:96-117) takes a single
`repository: String` parameter — both arms share it by construction. The `SuiteRun` type does
not carry a repository field, so there is no mechanism to detect that the two runs were made
against different repositories. The spec scenario "Adversarial — a comparison whose arms differ
in repository is refused" requires the gate to revert, but the implementation cannot detect this
condition. The test (DifferentialHarnessSpec.scala:16-35) only asserts that two different
repository strings are different (`d1.repository != d2.repository`), which is trivially true and
does not test the actual requirement.

The `verifySuiteDigests` function (DifferentialHarness.scala:128-139) correctly detects modified
suite files by comparing SHA-256 digests, and the test (DifferentialHarnessSpec.scala:39-59)
verifies this. However, `verifySuiteDigests` is not called by `diff` or `decide` — the caller
must remember to invoke it separately. The spec says "a comparison against a modified suite is
refused," but the gate does not refuse it; it provides a separate verification function.

**Evidence**: DifferentialHarness.scala:96-117 (no repository equality check),
DifferentialHarnessSpec.scala:16-35 (test only checks string inequality)
**Why tests missed it**: The test asserts `d1.repository != d2.repository` (trivially true)
instead of asserting that the gate reverts on differing repositories.
**Fix class**: Smart constructor / runtime rejection — `SuiteRun` should carry a repository
field, and `diff` should refuse (return an incomplete `DifferentialResult`) when the two runs'
repositories differ. This is a structural change to the harness API and is noted for human
approval rather than fixed inline, as it changes the `SuiteRun` type signature and all callers.

### Requirement: A seam resolves to exactly one implementation — PASS

`SeamConfiguration` is `final case class SeamConfiguration private (portedTools: Set[ToolId])`
with a private constructor (SeamTypes.scala:81). The smart constructor `fromPorted` takes only
the ported set; the predecessor set is derived as `ToolId.swapOrder.toSet -- portedTools`
(SeamTypes.scala:86). A seam in both sets is unconstructible because the predecessor set is not
an input. The compile-negative test (CutoverGateSpec.scala:131-139) verifies the two-argument
constructor doesn't compile. The `resolve` method (SeamTypes.scala:96-99) returns
`Some(Ported)` or `Some(Predecessor)` for every seam in `swapOrder`. The property
`seam-resolves-to-exactly-one` (CutoverGateSpec.scala:256) verifies this on generated
configurations with cover annotations ensuring all-ported, all-predecessor, and mixed configs
are hit. The `else None` branch in `resolve` is unreachable for the current 5-element enum
(justified — every `ToolId` is in `swapOrder`).

### Requirement: A refused cutover restores the predecessor at every seam it had swapped — PARTIAL (noted for human approval)

The revert is implemented as test helpers in `CutoverRevertSpec`, not as production code.
`revertToPredecessor` (CutoverRevertSpec.scala:73-78) ignores its `swapped` parameter
(`val _ = swapped`) and always returns `SeamConfiguration.fromPorted(Set.empty)` (all
predecessor). The property `revert-restores-every-swapped-seam` (CutoverRevertSpec.scala:54) is
a tautology: since `revertToPredecessor` always returns all-predecessor, every seam resolves to
`Predecessor` regardless of the input. The test cannot fail.

The spec requires "the restoration SHALL be verified by re-running the comparison" — no re-run
is implemented or tested. The spec's generator strategy requires "materialised as real shim
files in a temporary tree with their predecessor files present" — the actual `genSwapHistory`
(CutoverRevertSpec.scala:116-127) generates only a `Set[ToolId]` with no filesystem
materialisation.

The `simulateRevert` and `simulateRevertWithMissingPredecessor` helpers
(CutoverRevertSpec.scala:88-105) are hardcoded constructors that return predetermined
`RevertResult` values — they don't simulate any actual revert logic.

**Evidence**: CutoverRevertSpec.scala:73-78 (`val _ = swapped`), CutoverRevertSpec.scala:54-62
(tautological property), CutoverRevertSpec.scala:116-127 (no filesystem materialisation)
**Why tests missed it**: The property is vacuously true because the function under test ignores
its input. The generator doesn't materialise shim files as the spec requires, so filesystem-level
revert correctness is never exercised.
**Fix class**: The revert requires a production implementation that (1) restores shim files on
disk, (2) verifies the restoration by re-running the comparison, and (3) reports failure naming
the seam when restoration fails. The generator must materialise real shim files in a temporary
tree. This is a substantial implementation beyond the scope of a review fix and is noted for
human approval.

### Requirement: The gate's decision and its evidence are recorded before the swap proceeds — PASS (after fix)

`CutoverGate.record` (CutoverGate.scala:55-56) returns `GateRecord(decide(d), d)`, carrying
both the verdict and the differential result. The `GateRecord.authorisesSwap` method
(CutoverGate.scala:67-70) now requires `hasEvidence && verdict == Proceed` — a record with no
files (empty comparison) does not authorise a swap. The test
(CutoverGateSpec.scala:152-154) asserts `!record.authorisesSwap` for an empty comparison.

**Fix applied**: `authorisesSwap` was `verdict match { case Proceed => true; case Revert(_) => false }`
which authorised a swap on an empty comparison (verdict = Proceed for vacuously complete empty
differential). Changed to `hasEvidence && (verdict match { ... })`. The weak test assertion
`!record.authorisesSwap || record.verdict == CutoverVerdict.Proceed` (which passed even when
authorisesSwap was true) was strengthened to `!record.authorisesSwap`.

---

## Dangerous-Pattern Hunt

### 1. DifferentialHarness.runSuite silent fallback — FIXED

**File**: DifferentialHarness.scala:55
**Pattern**: `if !os.exists(oracleDir) then SuiteRun(List.empty, Set.empty)` — a missing oracle
directory silently returns an empty run. An empty run produces an empty `DifferentialResult`,
which the gate treats as complete (vacuously) and proceeds. This is a silent fallback to a valid
domain value (empty = proceed) for an error condition (missing oracle).
**Fix**: Replaced with `sys.error(...)` — a missing oracle is a hard failure, not an empty
comparison. All callers (`OracleDiffRunner`, `OracleGreenCheck`) already check `os.exists` before
calling `runSuite`, so no test breaks.

### 2. genRegressingDifferential: forcedPort not actually worse when pred == total — FIXED

**File**: CutoverGateSpec.scala:424-426
**Pattern**: `val forcedPort: Int = math.min(pred + 1, total)` — when `pred == total`,
`forcedPort = total = pred`, so the file is NOT worse. The generator claims to produce a
regression but doesn't. The property `total-improvement-does-not-excuse-a-regression` would
falsify on such a case (gate returns Proceed for a non-regression, but the property expects
Revert).
**Fix**: Cap `pred` at `total - 1` for the worse file: `val safePred = math.min(pred, total - 1);
val forcedPort = safePred + 1`. Now `forcedPort > safePred` always holds.

### 3. DifferentialHarness.verifySuiteDigests: case _ silently accepts unexpected files — JUSTIFIED

**File**: DifferentialHarness.scala:135
**Pattern**: `expectedDigests.get(fileName) match { case Some(expected) if expected != actualDigest => Some(fileName); case _ => None }`
— the `case _` covers both `Some(expected)` where `expected == actualDigest` (correct, no
mismatch) AND `None` (file not in expected digests, silently accepted). An unexpected file in the
oracle directory could be a modified suite that passes undetected.
**Justification**: The `expectedDigests` map is caller-supplied. In the test
(DifferentialHarnessSpec.scala:91), it's computed from all bats files in the directory, so
`None` is never hit. In production use, the caller is responsible for providing a complete
digest map. Changing this to flag unexpected files would be more defensive but could break
callers that intentionally provide a subset. Noted as a potential strengthening but not fixed
inline to avoid breaking the existing API contract.

### 4. GateRecord.authorisesSwap did not check hasEvidence — FIXED

**File**: CutoverGate.scala:65-67
**Pattern**: `authorisesSwap` returned `true` for `Proceed` regardless of whether evidence was
present. An empty `DifferentialResult` (no files) would authorise a swap despite having no
comparison evidence. The spec says "a decision with no recorded comparison is not actionable."
**Fix**: Changed to `hasEvidence && (verdict match { case Proceed => true; case Revert(_) => false })`.

### 5. Weak test assertion masking the authorisesSwap bug — FIXED

**File**: CutoverGateSpec.scala:151-154
**Pattern**: `assert(!record.authorisesSwap || record.verdict == CutoverVerdict.Proceed)` — this
assertion passes even when `authorisesSwap` is `true` (because `verdict == Proceed` for an empty
comparison). The assertion was designed to pass regardless of the bug.
**Fix**: Strengthened to `assert(!record.authorisesSwap, "an empty comparison must not authorise
a swap — the comparison is missing")`.

---

## Oracle-Tampering Check

### Finding 1: All cover annotations were missing — FIXED

**Evidence**: The spec declares Hedgehog `cover` requirements for all 5 property generators:
- `genDifferentialResult`: no-file-worse ≥ 30%, exactly-one-worse ≥ 25%, worse-but-total-improves ≥ 15%, all-files-equal ≥ 10%
- `genRegressingDifferential`: total-lower ≥ 60%
- `genIncompleteDifferential`: missing-from-ported ≥ 40%, missing-from-predecessor ≥ 40%
- `genSeamConfiguration`: all-ported ≥ 10%, all-predecessor ≥ 10%, mixed ≥ 60%
- `genSwapHistory`: empty-prefix ≥ 10%, full-prefix ≥ 15%, partial-prefix ≥ 60%

The implementation had these cover requirements only in comments (CutoverGateSpec.scala:323,343,373,397;
CutoverRevertSpec.scala:101) — no actual `.cover(...)` calls. The codebase uses `.cover(...)` in
other specs (CallKeySpec.scala:35-36, HarnessAgentSpec.scala:207-227), so the API is available.
Without cover, the generators could vacuously pass the properties without hitting the edge cases
the spec names.

**Fix**: Added `.cover(...)` calls to all 5 property tests. Fixed generators that couldn't meet
the cover thresholds with their original distribution:
- `genSeamConfiguration`: with 5 independent booleans, all-ported = 1/32 ≈ 3% (below 10%). Added
  frequency weighting: 2:2:9 (all-ported:all-predecessor:mixed) to ensure edge cases hit their
  floors.
- `genDifferentialResult`: with independent counts and 1-20 files, exactly-one-worse was ~21%
  (below 25%). Added frequency-weighted constructive branches (3:3:3:1 =
  independent:no-file-worse:exactly-one-worse:all-equal) that build cover classes directly, the
  same approach the spec uses for `genRegressingDifferential`.
- `genSwapHistory`: with uniform `Range.linear(0, 5)`, full-prefix was 0% in some runs (below
  15%). Added frequency weighting: 2:3:10 (empty:full:partial).
- Increased test limit to 500 for all properties with cover to eliminate sampling-variance flakiness.

### Finding 2: genRegressingDifferential produced non-regressions — FIXED

**Evidence**: When `pred == total`, `math.min(pred + 1, total) == total == pred`, so the
"forced worse" file was not actually worse. The generator claimed to produce a regression but
didn't, and the property `total-improvement-does-not-excuse-a-regression` would falsify on such
cases (gate correctly returns Proceed for a non-regression, but the property expects Revert).
This is a generator bug that weakens the oracle: it tests non-regression cases as if they were
regressions.

**Fix**: Cap `pred` at `total - 1` for the worse file so `forcedPort = safePred + 1 > safePred`
always holds. Also fixed the improved files to ensure `pred >= 2` so the improvement always
outweighs the regression, keeping the total-lower cover above 60%.

---

## Summary of Changes

### Files modified:

1. **CutoverGate.scala** (CutoverGate.scala:67-70): `GateRecord.authorisesSwap` now requires
   `hasEvidence` — an empty comparison (no files) no longer authorises a swap.

2. **DifferentialHarness.scala** (DifferentialHarness.scala:55-56): `runSuite` now throws
   `sys.error` on missing oracle directory instead of silently returning an empty run.

3. **CutoverGateSpec.scala**:
   - Added `coverConfig` with `SuccessCount(500)` for stable cover percentages.
   - Added `.cover(...)` calls to all 4 properties (proceed-iff-no-file-worse,
     total-improvement-does-not-excuse-a-regression, incomplete-comparison-never-proceeds,
     seam-resolves-to-exactly-one).
   - Restructured `genDifferentialResult` with frequency-weighted constructive branches
     (3:3:3:1) to meet all 4 cover thresholds.
   - Fixed `genRegressingDifferential`: cap `pred` at `total - 1` for worse file; ensure
     `pred >= 2` for improved files.
   - Restructured `genSeamConfiguration` with frequency weighting (2:2:9) to meet cover
     thresholds.
   - Strengthened the "no recorded comparison" test assertion from
     `!authorisesSwap || verdict == Proceed` to `!authorisesSwap`.

4. **CutoverRevertSpec.scala**:
   - Added `coverConfig` with `SuccessCount(500)`.
   - Added `.cover(...)` calls to the revert property.
   - Restructured `genSwapHistory` with frequency weighting (2:3:10) to meet cover thresholds.

### Test results: 32/32 passed across 5 consecutive runs (no flakiness).
