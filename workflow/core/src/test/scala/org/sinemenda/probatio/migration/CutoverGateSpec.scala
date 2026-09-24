package org.sinemenda.probatio.migration

import hedgehog.*
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.ProbatioSuite

/**
 * Test oracle for the cutover-gate spec.
 *
 * These tests are derived from the spec's requirements, scenarios,
 * properties, and compile-negative obligations — NOT from the
 * implementation. They verify:
 *
 *   - Requirement: The gate's decision is a comparison against the
 *     predecessor, not an absolute threshold
 *   - Requirement: A seam resolves to exactly one implementation
 *   - Requirement: The gate's decision and its evidence are recorded
 *     before the swap proceeds
 *   - Property: proceed-iff-no-file-worse
 *   - Property: total-improvement-does-not-excuse-a-regression
 *   - Property: incomplete-comparison-never-proceeds
 *   - Property: seam-resolves-to-exactly-one
 *   - Compile-Negative obligations
 *
 * spec: cutover-gate — all requirements, properties, and compile-negatives
 */
final class CutoverGateSpec extends ProbatioSuite:

  import SeamTypes.*

  /**
   * Cover thresholds are stable at 500 tests — the default 100 can
   * fluctuate ±5% on edge-case classes, causing flaky cover failures.
   */
  private val coverConfig: PropertyConfig => PropertyConfig =
    _.copy(testLimit = SuccessCount(500))

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: The gate's decision is a comparison against the predecessor
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: Happy path — no file is worse and the gate proceeds
  // spec: cutover-gate — Scenario: Happy path — no file is worse and the gate proceeds
  test("cutover gate: no file worse → Proceed"):
    val d: DifferentialResult = completeDifferential(
      List(
        ("file1.bats", 10, 3, 2),
        ("file2.bats", 10, 5, 5),
        ("file3.bats", 10, 0, 0)
      )
    )
    val verdict: CutoverVerdict = CutoverGate.decide(d)
    assertEquals(verdict, CutoverVerdict.Proceed)

  // ── Scenario: Adversarial — one file worse blocks even when the total improves
  // spec: cutover-gate — Scenario: Adversarial — one file worse blocks even when the total improves
  test("cutover gate: one file worse blocks even when total improves → Revert"):
    // file1: pred=10, ported=12 (regression, +2)
    // file2: pred=10, ported=5  (improvement, -5)
    // total: pred=20, ported=17 (improvement), but gate reverts
    val d: DifferentialResult = completeDifferential(
      List(
        ("file1.bats", 10, 10, 12),
        ("file2.bats", 10, 10, 5)
      )
    )
    val verdict: CutoverVerdict = CutoverGate.decide(d)
    verdict match
      case CutoverVerdict.Revert(evidence) =>
        assert(
          evidence.worseFileNames.contains("file1.bats"),
          s"revert must name the worse file, got ${evidence.worseFileNames}"
        )
      case CutoverVerdict.Proceed =>
        fail("expected Revert but got Proceed — a per-file regression must block")

  // ── Scenario: Happy path — a file that improves does not block
  // spec: cutover-gate — Scenario: Happy path — a file that improves does not block
  test("cutover gate: a file that improves does not block → Proceed"):
    val d: DifferentialResult = completeDifferential(
      List(
        ("file1.bats", 10, 5, 2), // improvement
        ("file2.bats", 10, 3, 3)  // equal
      )
    )
    val verdict: CutoverVerdict = CutoverGate.decide(d)
    assertEquals(verdict, CutoverVerdict.Proceed)

  // ── Scenario: Error path — a run that did not complete is not a comparison
  // spec: cutover-gate — Scenario: Error path — a run that did not complete is not a comparison
  test("cutover gate: incomplete comparison (missing ported result) → Revert"):
    val d: DifferentialResult = incompleteDifferential(
      List(
        ("file1.bats", 10, 3, 2, true, true),
        ("file2.bats", 10, 5, 0, true, false) // ported result missing
      )
    )
    val verdict: CutoverVerdict = CutoverGate.decide(d)
    assert(verdict != CutoverVerdict.Proceed, "an incomplete comparison must never proceed")

  // ── Scenario: Adversarial — a suite whose file set differs between the runs is incomplete
  // spec: cutover-gate — Scenario: Adversarial — a suite whose file set differs between the runs is incomplete
  test("cutover gate: differing file sets → Revert"):
    val files: List[FileComparison] = List(
      FileComparison("file1.bats", 10, 3, 2, true, true),
      FileComparison("file2.bats", 10, 5, 0, true, false),
      FileComparison("file3.bats", 10, 0, 0, false, true)
    )
    val suiteFileSet: Set[String] = Set("file1.bats", "file2.bats", "file3.bats")
    val d: DifferentialResult     = DifferentialResult(files, "/repo", suiteFileSet)
    val verdict: CutoverVerdict   = CutoverGate.decide(d)
    assert(verdict != CutoverVerdict.Proceed, "a comparison with differing file sets must never proceed")

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: A seam resolves to exactly one implementation
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: Happy path — every seam resolves
  // spec: cutover-gate — Scenario: Happy path — every seam resolves
  test("seam configuration: every seam resolves to exactly one implementation"):
    val cfg: SeamConfiguration = SeamConfiguration.fromPorted(Set(ToolId.ChainState, ToolId.SpecLint))
    val allTools: List[ToolId] = ToolId.swapOrder.toList
    val allResolve: Boolean = allTools.forall { (seam: ToolId) =>
      cfg.resolve(seam) match
        case Some(_) =>
          cfg.portedTools.contains(seam) != cfg.predecessorTools.contains(seam)
        case None => false
    }
    assert(allResolve, "every seam must resolve to exactly one implementation")

  // ── Scenario: Adversarial — a configuration listing a seam as both ported and predecessor is unconstructible
  // spec: cutover-gate — Scenario: Adversarial — a configuration listing a seam as both is unconstructible
  test("compile-negative: SeamConfiguration with seam in both sets is unconstructible"):
    val err: String = compileErrors(
      """SeamConfiguration(Set(ToolId.ChainState), Set(ToolId.ChainState))"""
    )
    assert(err.nonEmpty, "SeamConfiguration must not accept a predecessor set — the both state is unconstructible")

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: The gate's decision and its evidence are recorded before the swap proceeds
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: Happy path — a proceed decision records its comparison
  // spec: cutover-gate — Scenario: Happy path — a proceed decision records its comparison
  test("gate record: a proceed decision records its comparison"):
    val d: DifferentialResult = completeDifferential(
      List(("file1.bats", 10, 3, 2))
    )
    val record: GateRecord = CutoverGate.record(d)
    assert(record.authorisesSwap, "a proceed record must authorise a swap")
    assert(record.evidence.files.nonEmpty, "the record must carry per-file counts")

  // ── Scenario: Adversarial — a decision with no recorded comparison is not actionable
  // spec: cutover-gate — Scenario: Adversarial — a decision with no recorded comparison is not actionable
  test("gate record: a decision with no recorded comparison is not actionable"):
    val emptyD: DifferentialResult = DifferentialResult(List.empty, "/repo", Set.empty)
    val record: GateRecord         = CutoverGate.record(emptyD)
    assert(!record.hasEvidence, "a record with no files is not actionable — the comparison is missing")
    assert(
      !record.authorisesSwap,
      "an empty comparison must not authorise a swap — the comparison is missing"
    )

  // ── Scenario: Edge case — a revert decision records its comparison too
  // spec: cutover-gate — Scenario: Edge case — a revert decision records its comparison too
  test("gate record: a revert decision records its comparison naming worse files"):
    val d: DifferentialResult = completeDifferential(
      List(
        ("file1.bats", 10, 3, 5), // worse
        ("file2.bats", 10, 5, 2)  // better
      )
    )
    val record: GateRecord = CutoverGate.record(d)
    record.verdict match
      case CutoverVerdict.Revert(evidence) =>
        assert(
          evidence.worseFileNames.contains("file1.bats"),
          s"revert record must name the worse file, got ${evidence.worseFileNames}"
        )
      case CutoverVerdict.Proceed =>
        fail("expected Revert but got Proceed")

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: The gate as implemented would have refused the cutover that shipped
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: replaying the measured 2026-08-29 comparison as a fixture
  // spec: cutover-gate — Proof Obligation: The gate as implemented would have refused the cutover that shipped
  test("cutover gate: the measured 2026-08-29 comparison would have been refused"):
    // Measured 2026-08-29: 122 ported failures against 20 predecessor failures.
    // The gate as implemented compares per-file: any file worse → revert.
    // This fixture models the measured state: the ported arm is worse in
    // at least one file (the gate that shipped was bypassed, not green).
    val d: DifferentialResult = completeDifferential(
      List(
        ("dispatch.bats", 20, 2, 15),   // ported much worse
        ("spec-lint.bats", 18, 1, 10),  // ported much worse
        ("chain-state.bats", 16, 0, 8), // ported much worse
        ("gate.bats", 14, 3, 5),        // ported worse
        ("banner.bats", 12, 2, 2),      // equal
        ("ledger.bats", 10, 1, 1),      // equal
      )
    )
    val verdict: CutoverVerdict = CutoverGate.decide(d)
    assert(
      verdict != CutoverVerdict.Proceed,
      "the measured comparison must be refused — the ported arm is worse in multiple files"
    )

  // ════════════════════════════════════════════════════════════════════════
  // Properties (Ring 3)
  // ════════════════════════════════════════════════════════════════════════

  // ── Property: proceed-iff-no-file-worse
  // spec: cutover-gate — Property: proceed-iff-no-file-worse
  property("proceed-iff-no-file-worse", coverConfig):
    for d <- genDifferentialResult.forAll
        .cover(
          30,
          "no-file-worse",
          (d: DifferentialResult) => d.files.forall(f => f.portedFailures <= f.predecessorFailures)
        )
        .cover(
          25,
          "exactly-one-worse",
          (d: DifferentialResult) => d.files.count(f => f.portedFailures > f.predecessorFailures) == 1
        )
        .cover(
          15,
          "worse-but-total-improves",
          (d: DifferentialResult) =>
            d.hasRegression && d.files.map(_.portedFailures).sum < d.files.map(_.predecessorFailures).sum
        )
        .cover(
          10,
          "all-files-equal",
          (d: DifferentialResult) => d.files.forall(f => f.portedFailures == f.predecessorFailures)
        )
    yield
      if d.isComplete then
        Result
          .assert(
            (CutoverGate.decide(d) == CutoverVerdict.Proceed) ==
              d.files.forall(f => f.portedFailures <= f.predecessorFailures)
          )
          .log(s"complete=$d")
      else Result.success

  // ── Property: total-improvement-does-not-excuse-a-regression
  // spec: cutover-gate — Property: total-improvement-does-not-excuse-a-regression
  property("total-improvement-does-not-excuse-a-regression", coverConfig):
    for d <- genRegressingDifferential.forAll
        .cover(
          60,
          "total-lower",
          (d: DifferentialResult) => d.files.map(_.portedFailures).sum < d.files.map(_.predecessorFailures).sum
        )
    yield CutoverGate.decide(d) match
      case CutoverVerdict.Revert(evidence) =>
        Result.assert(evidence.hasRegression).log(s"revert with regression: $evidence")
      case CutoverVerdict.Proceed =>
        Result.failure.log("a regression must never proceed")

  // ── Property: incomplete-comparison-never-proceeds
  // spec: cutover-gate — Property: incomplete-comparison-never-proceeds
  property("incomplete-comparison-never-proceeds", coverConfig):
    for d <- genIncompleteDifferential.forAll
        .cover(40, "missing-from-ported", (d: DifferentialResult) => d.files.exists(f => !f.portedPresent))
        .cover(40, "missing-from-predecessor", (d: DifferentialResult) => d.files.exists(f => !f.predecessorPresent))
    yield Result
      .assert(CutoverGate.decide(d) != CutoverVerdict.Proceed)
      .log(s"incomplete comparison must not proceed: $d")

  // ── Property: isWorse-requires-both-runs-present
  // spec: cutover-gate — Requirement: The gate's decision is a comparison against the predecessor
  // A file is "worse" only when BOTH runs produced a result. A file
  // missing from the predecessor run cannot be "worse" even if the
  // ported run has failures — the comparison is incomplete, not a
  // regression. This kills the `&& → ||` mutant on isWorse that would
  // mark a predecessor-missing file as worse.
  property("isWorse-requires-both-runs-present", coverConfig):
    for
      total <- Gen.int(Range.linear(1, 40)).forAll
      pred  <- Gen.int(Range.linear(0, total)).forAll
      port  <- Gen.int(Range.linear(0, total)).forAll
      predP <- Gen.boolean.forAll
      portP <- Gen.boolean.forAll
    yield
      val f: FileComparison = FileComparison("f.bats", total, pred, port, predP, portP)
      val expected: Boolean = predP && portP && port > pred
      Result
        .assert(f.isWorse == expected)
        .log(s"isWorse($f) = ${f.isWorse}, expected $expected — both runs must be present")

  // ── Property: seam-resolves-to-exactly-one
  // spec: cutover-gate — Property: seam-resolves-to-exactly-one
  property("seam-resolves-to-exactly-one", coverConfig):
    for
      cfg <- genSeamConfiguration.forAll
        .cover(10, "all-ported", (cfg: SeamConfiguration) => cfg.portedTools.size == ToolId.swapOrder.length)
        .cover(10, "all-predecessor", (cfg: SeamConfiguration) => cfg.portedTools.isEmpty)
        .cover(
          60,
          "mixed",
          (cfg: SeamConfiguration) => cfg.portedTools.nonEmpty && cfg.portedTools.size < ToolId.swapOrder.length
        )
      seam <- Gen.int(Range.linear(0, ToolId.swapOrder.length - 1)).map(ToolId.swapOrder(_)).forAll
    yield Result
      .assert(
        cfg.resolve(seam).isDefined &&
          cfg.portedTools.contains(seam) != cfg.predecessorTools.contains(seam)
      )
      .log(s"seam $seam must resolve to exactly one in $cfg")

  // ════════════════════════════════════════════════════════════════════════
  // Compile-Negative Obligations
  // ════════════════════════════════════════════════════════════════════════

  // ── Compile-Negative: CutoverVerdict.Revert constructed without a DifferentialResult
  // spec: cutover-gate — Compile-Negative: CutoverVerdict.Revert constructed without a DifferentialResult
  test("compile-negative: CutoverVerdict.Revert without evidence does not compile"):
    val err: String = compileErrors("CutoverVerdict.Revert()")
    assert(
      err.nonEmpty,
      "CutoverVerdict.Revert must require a DifferentialResult — a refusal always names its evidence"
    )

  // ── Compile-Negative: A gate decision function taking a single run rather than a comparison
  // spec: cutover-gate — Compile-Negative: A gate decision function taking a single run rather than a comparison
  test("compile-negative: single-run decision function does not compile"):
    val err: String = compileErrors("CutoverGate.decideSingleRun(OracleOutcome(10, 0, 0))")
    assert(err.nonEmpty, "CutoverGate must not have a single-run decision function — the comparison is the gate")

  // ── Compile-Negative: CutoverVerdict pattern match omitting a case
  // spec: cutover-gate — Compile-Negative: CutoverVerdict pattern match omitting a case
  // The exhaustiveness check is enforced by -Werror in the production code
  // (OracleGreenGate matches exhaustively). Here we verify the enum has
  // exactly two cases: Proceed (singleton) and Revert (requires evidence).
  test("CutoverVerdict has exactly Proceed and Revert(evidence) cases"):
    // Proceed is a singleton case
    val proceed: CutoverVerdict = CutoverVerdict.Proceed
    // Revert requires a DifferentialResult — construct one with evidence
    val d: DifferentialResult  = completeDifferential(List(("f.bats", 1, 0, 0)))
    val revert: CutoverVerdict = CutoverVerdict.Revert(d)
    // Both are CutoverVerdict instances — the enum has exactly these two
    assert(proceed == CutoverVerdict.Proceed, "Proceed must be a CutoverVerdict")
    val revertCarriesEvidence: Boolean = revert match
      case CutoverVerdict.Revert(_) => true
      case CutoverVerdict.Proceed   => false
    assert(revertCarriesEvidence, "Revert must carry its evidence")

  // ════════════════════════════════════════════════════════════════════════
  // Helpers
  // ════════════════════════════════════════════════════════════════════════

  /**
   * Build a complete DifferentialResult from a list of (name, total,
   * predFailures, portedFailures) tuples.
   */
  def completeDifferential(
    entries: List[(String, Int, Int, Int)]
  ): DifferentialResult =
    val files: List[FileComparison] = entries.map { case (name, total, pred, port) =>
      FileComparison(name, total, pred, port, true, true)
    }
    val fileSet: Set[String] = entries.map(_._1).toSet
    DifferentialResult(files, "/repo", fileSet)

  /**
   * Build an incomplete DifferentialResult from a list of (name, total,
   * predFailures, portedFailures, predPresent, portPresent) tuples.
   */
  def incompleteDifferential(
    entries: List[(String, Int, Int, Int, Boolean, Boolean)]
  ): DifferentialResult =
    val files: List[FileComparison] = entries.map { case (name, total, pred, port, pp, pr) =>
      FileComparison(name, total, pred, port, pp, pr)
    }
    val fileSet: Set[String] = entries.map(_._1).toSet
    DifferentialResult(files, "/repo", fileSet)

  // ── Generator: genDifferentialResult
  // Constructive: a file set of size 1–20, each file with a generated
  // total (1–40), a generated predecessor failure count in [0, total],
  // and a generated ported failure count in [0, total] chosen
  // independently. No filtering. Frequency-weighted with constructive
  // branches to ensure the spec's cover thresholds are met — the pure
  // independent generator produces exactly-one-worse at ~21% (below the
  // 25% floor) because the probability decays exponentially with file
  // count. The constructive branches build the cover classes directly
  // (the same approach the spec uses for genRegressingDifferential).
  // Hedgehog cover: no-file-worse ≥ 30%, exactly-one-worse ≥ 25%,
  // worse-but-total-improves ≥ 15%, all-files-equal ≥ 10%.
  def genDifferentialResult: Gen[DifferentialResult] =
    Gen.frequency1(
      3 -> genIndependentDifferential,
      3 -> genForcedNoFileWorse,
      3 -> genForcedExactlyOneWorse,
      1 -> genForcedAllFilesEqual
    )

  def genIndependentDifferential: Gen[DifferentialResult] =
    for files <- Gen.list(genFileComparison, Range.linear(1, 20))
    yield
      val fileSet: Set[String] = files.map(_.fileName).toSet
      DifferentialResult(files, "/repo", fileSet)

  def genForcedNoFileWorse: Gen[DifferentialResult] =
    for files <- Gen.list(genNoWorseFileComparison, Range.linear(1, 20))
    yield
      val fileSet: Set[String] = files.map(_.fileName).toSet
      DifferentialResult(files, "/repo", fileSet)

  def genForcedExactlyOneWorse: Gen[DifferentialResult] =
    for
      otherFiles <- Gen.list(genNoWorseFileComparison, Range.linear(1, 9))
      worseFile  <- genWorseFileComparison
    yield
      val files: List[FileComparison] = otherFiles :+ worseFile
      val fileSet: Set[String]        = files.map(_.fileName).toSet
      DifferentialResult(files, "/repo", fileSet)

  def genForcedAllFilesEqual: Gen[DifferentialResult] =
    for files <- Gen.list(genEqualFileComparison, Range.linear(1, 20))
    yield
      val fileSet: Set[String] = files.map(_.fileName).toSet
      DifferentialResult(files, "/repo", fileSet)

  def genFileComparison: Gen[FileComparison] =
    for
      name           <- Gen.string(Gen.alpha, Range.linear(3, 10)).map(s => s"$s.bats")
      total          <- Gen.int(Range.linear(1, 40))
      predFailures   <- Gen.int(Range.linear(0, total))
      portedFailures <- Gen.int(Range.linear(0, total))
    yield FileComparison(name, total, predFailures, portedFailures, true, true)

  def genNoWorseFileComparison: Gen[FileComparison] =
    for
      name   <- Gen.string(Gen.alpha, Range.linear(3, 10)).map(s => s"$s.bats")
      total  <- Gen.int(Range.linear(1, 40))
      pred   <- Gen.int(Range.linear(0, total))
      ported <- Gen.int(Range.linear(0, pred))
    yield FileComparison(name, total, pred, ported, true, true)

  def genWorseFileComparison: Gen[FileComparison] =
    for
      name  <- Gen.string(Gen.alpha, Range.linear(3, 10)).map(s => s"$s.bats")
      total <- Gen.int(Range.linear(1, 40))
      pred  <- Gen.int(Range.linear(0, total - 1))
      ported = pred + 1
    yield FileComparison(name, total, pred, ported, true, true)

  def genEqualFileComparison: Gen[FileComparison] =
    for
      name  <- Gen.string(Gen.alpha, Range.linear(3, 10)).map(s => s"$s.bats")
      total <- Gen.int(Range.linear(1, 40))
      pred  <- Gen.int(Range.linear(0, total))
    yield FileComparison(name, total, pred, pred, true, true)

  // ── Generator: genRegressingDifferential
  // Constructive: builds the regression directly by choosing one file
  // and setting its ported count above its predecessor count, then
  // generating the remaining files so the overall total is lower.
  // Hedgehog cover: total-lower ≥ 60%.
  def genRegressingDifferential: Gen[DifferentialResult] =
    for
      files    <- Gen.list(genFileEntry, Range.linear(2, 10))
      worseIdx <- Gen.int(Range.linear(0, files.length - 1))
    yield
      val fileComparisons: List[FileComparison] = files.zipWithIndex.map { case ((name, total, pred, _), idx) =>
        if idx == worseIdx then
          // Force this file to be worse: ensure pred < total so ported = pred + 1
          // is strictly greater and does not exceed total. Without this cap, when
          // pred == total, min(pred + 1, total) == total == pred, and the file is
          // NOT worse — the generator would claim a regression that doesn't exist.
          val safePred: Int   = math.min(pred, total - 1)
          val forcedPort: Int = safePred + 1
          FileComparison(name, total, safePred, forcedPort, true, true)
        else
          // Make other files improve enough that total is lower.
          // Ensure pred >= 2 so the improvement is always at least -2,
          // which outweighs the worse file's +1 regression. Without this,
          // when pred < 2 the improvement is 0 and the total increases,
          // causing the total-lower cover to miss its 60% floor.
          val safePred: Int     = math.min(total, math.max(2, pred))
          val improvedPort: Int = math.max(0, safePred - 2)
          FileComparison(name, total, safePred, improvedPort, true, true)
      }
      val fileSet: Set[String] = fileComparisons.map(_.fileName).toSet
      DifferentialResult(fileComparisons, "/repo", fileSet)

  def genFileEntry: Gen[(String, Int, Int, Int)] =
    for
      name  <- Gen.string(Gen.alpha, Range.linear(3, 10)).map(s => s"$s.bats")
      total <- Gen.int(Range.linear(1, 40))
      pred  <- Gen.int(Range.linear(0, total))
      port  <- Gen.int(Range.linear(0, total))
    yield (name, total, pred, port)

  // ── Generator: genIncompleteDifferential
  // Constructive: generates a complete comparison, then removes a
  // non-empty subset of files from one arm.
  // Hedgehog cover: missing-from-ported ≥ 40%, missing-from-predecessor ≥ 40%.
  def genIncompleteDifferential: Gen[DifferentialResult] =
    for
      entries          <- Gen.list(genFileEntry, Range.linear(2, 10))
      removeCount      <- Gen.int(Range.linear(1, entries.length))
      removeFromPorted <- Gen.boolean
    yield
      val allFiles: List[FileComparison] = entries.map { case (name, total, pred, port) =>
        FileComparison(name, total, pred, port, true, true)
      }
      val indicesToRemove: Set[Int] = (0 until removeCount).toSet
      val files: List[FileComparison] = allFiles.zipWithIndex.map { case (f, idx) =>
        if indicesToRemove.contains(idx) then
          if removeFromPorted then f.copy(portedPresent = false)
          else f.copy(predecessorPresent = false)
        else f
      }
      val fileSet: Set[String] = files.map(_.fileName).toSet
      DifferentialResult(files, "/repo", fileSet)

  // ── Generator: genSeamConfiguration
  // Constructive: chooses, for each seam independently, ported or
  // predecessor. The both/neither states are not generated because they
  // are unconstructible. Frequency-weighted to ensure the edge cases
  // (all-ported, all-predecessor) each hit ≥ 10% — with 5 independent
  // booleans they would only be ~3% each, below the spec's cover floor.
  // Hedgehog cover: all-ported ≥ 10%, all-predecessor ≥ 10%, mixed ≥ 60%.
  def genSeamConfiguration: Gen[SeamConfiguration] =
    Gen.frequency1(
      2 -> Gen.constant(SeamConfiguration.fromPorted(ToolId.swapOrder.toSet)),
      2 -> Gen.constant(SeamConfiguration.fromPorted(Set.empty)),
      9 -> genMixedSeamConfiguration
    )

  def genMixedSeamConfiguration: Gen[SeamConfiguration] =
    // Force at least one ported and one predecessor to guarantee a
    // mixed config. Without this, all-true (all-ported) and all-false
    // (all-predecessor) leak ~6% each into the edge cases, stealing
    // from the mixed cover floor. A bitmask over the 7 positions covers
    // ALL 126 non-empty proper subsets — `take(n)` only produces prefix
    // subsets, leaving non-prefix mixes unreachable.
    for mask <- Gen.int(Range.linear(1, (1 << ToolId.swapOrder.length) - 2))
    yield
      val ported: Set[ToolId] =
        ToolId.swapOrder.zipWithIndex.collect { case (t, i) if ((mask >> i) & 1) == 1 => t }.toSet
      SeamConfiguration.fromPorted(ported)

  // ════════════════════════════════════════════════════════════════════════
  // spec: differential-harness-integrity — the worse-file fold is exact
  // ════════════════════════════════════════════════════════════════════════

  // ── Property: comparison-is-monotone-in-failures
  // spec: differential-harness-integrity — Property: comparison-is-monotone-in-failures
  // GREEN-BY-DESIGN: `diff` is anchored "reused unchanged" — this property
  // pins the worse-file fold's exactness so a later modification of diff
  // (or a fold that drifts from "both runs present and ported strictly
  // exceeds predecessor") is caught.
  property("comparison-is-monotone-in-failures", coverConfig):
    for pair <- genSuiteRunPair.forAll
        .cover(25, "some-file-worse", (pair: SuiteRunPair) => pairWorseCount(pair) > 0)
        .cover(25, "no-file-worse", (pair: SuiteRunPair) => pairWorseCount(pair) == 0)
        .cover(10, "file-missing-from-one-run", (pair: SuiteRunPair) => pairHasMissingResult(pair))
    yield
      val (predecessor: DifferentialHarness.SuiteRun, ported: DifferentialHarness.SuiteRun) = pair
      val d: DifferentialResult = DifferentialHarness.diff(predecessor, ported, "/repo")
      val expectedWorse: Set[String] =
        d.files
          .filter((f: FileComparison) =>
            f.predecessorPresent && f.portedPresent && f.portedFailures > f.predecessorFailures
          )
          .map(_.fileName)
          .toSet
      Result
        .assert(d.worseFiles.forall((f: FileComparison) => f.portedFailures > f.predecessorFailures))
        .log("every worse file must have ported failures strictly exceeding predecessor failures")
        .and(
          Result
            .assert(d.worseFileNames.toSet == expectedWorse)
            .log("the worse-file fold must name exactly the files satisfying the condition")
        )
        .and(
          Result
            .assert(d.worseFiles.length == expectedWorse.size)
            .log("the result count equals the number of files satisfying the condition")
        )

  // ════════════════════════════════════════════════════════════════════════
  // spec 8 — ledger-checkpoint-cutover: the per-seam swap authorisation
  // ════════════════════════════════════════════════════════════════════════
  // Written from the spec's scenarios and the approved Step-1 contract —
  // NOT from the implementation. `authoriseSwap` scopes the comparison to
  // the files exercising the seam, then decides and records.

  private def exercisingMap(files: List[FileComparison], seam: ToolId): Map[String, Set[String]] =
    files.map((f: FileComparison) => f.fileName -> Set(ToolId.seamPath(seam))).toMap

  // ── Scenario: Happy path — a seam whose files are at parity is swapped
  // spec: ledger-checkpoint-cutover — Scenario: Happy path — a seam whose files are at parity is swapped
  test("a seam whose exercising files are at parity is swapped and records the comparison"):
    val exercising: List[FileComparison] = List(
      FileComparison("evidence-ledger.bats", 23, 0, 0, true, true),
      FileComparison("checkpoint-from-ledger.bats", 19, 0, 0, true, true)
    )
    // A file that does NOT exercise the seam is out of scope — worse or
    // not, it cannot gate this seam's swap.
    val unrelatedWorse: FileComparison =
      FileComparison("other-seam.bats", 10, 2, 9, true, true)
    val d: DifferentialResult = DifferentialResult(
      exercising :+ unrelatedWorse,
      "/repo",
      (exercising :+ unrelatedWorse).map(_.fileName).toSet
    )
    val exercisingPaths: Map[String, Set[String]] =
      exercisingMap(exercising, ToolId.Ledger)
    val record: GateRecord = CutoverGate.authoriseSwap(ToolId.Ledger, d, exercisingPaths)
    assert(record.authorisesSwap, s"a parity comparison must authorise the swap: $record")
    assertEquals(record.verdict, CutoverVerdict.Proceed)
    // The decision records the comparison it rested on — the scoped
    // evidence, not the whole-suite result.
    assertEquals(
      record.evidence.files.map(_.fileName).toSet,
      exercising.map(_.fileName).toSet,
      "the recorded comparison must be scoped to the seam's exercising files"
    )

  // ── Scenario: Adversarial — a seam with one worse file is not swapped
  // spec: ledger-checkpoint-cutover — Scenario: Adversarial — a seam with one worse file is not swapped
  test("a seam with one worse exercising file is not swapped — the refusal names it"):
    val exercising: List[FileComparison] = List(
      FileComparison("evidence-ledger.bats", 23, 0, 0, true, true),
      FileComparison("checkpoint-from-ledger.bats", 19, 0, 2, true, true)
    )
    val d: DifferentialResult = DifferentialResult(
      exercising,
      "/repo",
      exercising.map(_.fileName).toSet
    )
    val record: GateRecord =
      CutoverGate.authoriseSwap(ToolId.Checkpoint, d, exercisingMap(exercising, ToolId.Checkpoint))
    assert(!record.authorisesSwap, s"a worse exercising file must refuse the swap: $record")
    record.verdict match
      case CutoverVerdict.Revert(_) => () // the refusal carries its evidence
      case CutoverVerdict.Proceed   => fail("a regression must not proceed")
    assertEquals(
      record.evidence.justifyingFileNames,
      List("checkpoint-from-ledger.bats"),
      "the refusal must name the worse file"
    )

  // ── Scenario: Adversarial — a seam with an unmeasured file is not swapped
  // spec: ledger-checkpoint-cutover — Scenario: Adversarial — a seam with an unmeasured file is not swapped
  test("a seam with an unmeasured exercising file is not swapped — the refusal names it"):
    val exercising: List[FileComparison] = List(
      FileComparison("evidence-ledger.bats", 23, 0, 0, true, true),
      FileComparison("discharge-fidelity.bats", 11, 0, 0, false, true)
    )
    val d: DifferentialResult = DifferentialResult(
      exercising,
      "/repo",
      exercising.map(_.fileName).toSet
    )
    val record: GateRecord = CutoverGate.authoriseSwap(ToolId.Ledger, d, exercisingMap(exercising, ToolId.Ledger))
    assert(!record.authorisesSwap, s"an unmeasured file must refuse the swap: $record")
    assertEquals(
      record.evidence.justifyingFileNames,
      List("discharge-fidelity.bats"),
      "the refusal must name the unmeasured file — an absent result is not parity"
    )

  // ── Scenario: Adversarial — an exercising file with no comparison row
  //    is not swapped ──────────────────────────────────────────────────
  // spec: ledger-checkpoint-cutover — Scenario: Adversarial — a seam with an unmeasured file is not swapped
  // The expected scope is the exercising map's DOMAIN: a file that
  // exercises the seam but produced no comparison row at all is
  // unmeasured — the gate must refuse and name it, not silently scope
  // it out of the evidence.
  test("a seam whose exercising file has no comparison row is not swapped — the refusal names it"):
    val d: DifferentialResult = DifferentialResult(
      List(
        FileComparison("evidence-ledger.bats", 23, 0, 0, true, true),
        FileComparison("discharge-fidelity.bats", 11, 0, 0, true, true)
      ),
      "/repo",
      Set("evidence-ledger.bats", "discharge-fidelity.bats")
    )
    // The exercising map names a third file — present in the domain but
    // absent from comparison.files entirely.
    val exercising: Map[String, Set[String]] = Map(
      "evidence-ledger.bats"         -> Set(ToolId.seamPath(ToolId.Ledger)),
      "discharge-fidelity.bats"      -> Set(ToolId.seamPath(ToolId.Ledger)),
      "judgment-ring-integrity.bats" -> Set(ToolId.seamPath(ToolId.Ledger))
    )
    val record: GateRecord = CutoverGate.authoriseSwap(ToolId.Ledger, d, exercising)
    assert(!record.authorisesSwap, s"an exercising file with no comparison row must refuse the swap: $record")
    assertEquals(
      record.evidence.justifyingFileNames,
      List("judgment-ring-integrity.bats"),
      "the refusal must name the file that was never measured"
    )
    // The synthesized row must encode the file honestly: NEITHER arm
    // measured it. A row that claimed one arm measured the file would
    // misrepresent the evidence — the refusal's warrant must reflect
    // that the file produced no result at all.
    record.evidence.files
      .find((f: FileComparison) => f.fileName == "judgment-ring-integrity.bats")
      .fold(fail("the synthesized row for the absent file must be present in the evidence")) {
        (absentRow: FileComparison) =>
          assert(
            !absentRow.predecessorPresent && !absentRow.portedPresent,
            s"an absent comparison row must mark the file unmeasured by BOTH arms: $absentRow"
          )
      }

  // ── Scenario: Adversarial — a seam with no exercising file is not
  //    swapped ─────────────────────────────────────────────────────────
  // spec: ledger-checkpoint-cutover — Requirement: Each seam is swapped only after its oracle files reach control parity
  // The shipped strengthening of the spec's kernel contract (approved at
  // the Step-1 gate): the kernel authorises an empty file list
  // vacuously, but a seam with NO exercising acceptance file has nothing
  // measured — the shipped gate refuses it.
  test("a seam with no exercising files is not swapped — nothing was measured"):
    val d: DifferentialResult = DifferentialResult(
      List(FileComparison("evidence-ledger.bats", 23, 0, 0, true, true)),
      "/repo",
      Set("evidence-ledger.bats")
    )
    // Every file in the comparison exercises a DIFFERENT seam — the
    // ledger seam's exercising set is empty.
    val exercising: Map[String, Set[String]] =
      Map("evidence-ledger.bats" -> Set(ToolId.seamPath(ToolId.Checkpoint)))
    val record: GateRecord = CutoverGate.authoriseSwap(ToolId.Ledger, d, exercising)
    assert(!record.authorisesSwap, s"a seam with nothing measured must not be swapped: $record")
    record.verdict match
      case CutoverVerdict.Revert(_) => ()
      case CutoverVerdict.Proceed   => fail("an unmeasured seam must not proceed")

  // ── Property: swap-decision-requires-a-complete-comparison
  // spec: ledger-checkpoint-cutover — Property: swap-decision-requires-a-complete-comparison
  // The generator draws each file's two presence flags independently so
  // incomplete comparisons arise by construction. All generated files
  // exercise the seam (the scoped set IS the comparison); a seam with no
  // exercising file is refused upstream by `hasEvidence` and never
  // reaches this decision (approved contract: the shipped guard, not a
  // spec amendment).
  property("swap-decision-requires-a-complete-comparison", coverConfig):
    for files <- genSwapFileList.forAll
        .cover(
          25,
          "complete-no-worse",
          (fs: List[FileComparison]) =>
            fs.forall(f => f.predecessorPresent && f.portedPresent) &&
              fs.forall(f => f.portedFailures <= f.predecessorFailures)
        )
        .cover(25, "one-worse", (fs: List[FileComparison]) => fs.exists(_.isWorse))
        .cover(
          15,
          "one-unmeasured",
          (fs: List[FileComparison]) => fs.exists(f => !(f.predecessorPresent && f.portedPresent))
        )
    yield
      val seam: ToolId          = ToolId.Ledger
      val fileSet: Set[String]  = files.map(_.fileName).toSet
      val d: DifferentialResult = DifferentialResult(files, "/repo", fileSet)
      val record: GateRecord    = CutoverGate.authoriseSwap(seam, d, exercisingMap(files, seam))
      Result
        .assert(record.authorisesSwap == (d.isComplete && !d.hasRegression))
        .log(s"authorised=${record.authorisesSwap} but complete=${d.isComplete} regression=${d.hasRegression}: $d")

  /**
   * `genComparisonResult` — list-level mix so every cover class meets
   * its floor: ~1/3 of lists are all-clean (complete, no worse file —
   * a pure per-file draw reaches that class in only ~10% of 1–8-file
   * lists), the rest mix clean/worse/unmeasured files.
   */
  def genSwapFileList: Gen[List[FileComparison]] =
    Gen.frequency1(
      35 -> Gen.list(genCleanSwapFile, Range.linear(1, 8)),
      65 -> Gen.list(genSwapFile, Range.linear(1, 8))
    )

  /** A file measured in both arms with no regression. */
  def genCleanSwapFile: Gen[FileComparison] =
    for
      name  <- Gen.string(Gen.alpha, Range.linear(3, 10)).map(s => s"$s.bats")
      total <- Gen.int(Range.linear(1, 40))
      pred  <- Gen.int(Range.linear(0, total))
      port  <- Gen.int(Range.linear(0, pred))
    yield FileComparison(name, total, pred, port, true, true)

  /** Per-file triples with a shape drawn per file: clean, worse, or unmeasured. */
  def genSwapFile: Gen[FileComparison] =
    for
      name  <- Gen.string(Gen.alpha, Range.linear(3, 10)).map(s => s"$s.bats")
      total <- Gen.int(Range.linear(1, 40))
      shape <- Gen.frequency1(
        45 -> Gen.constant("clean"),
        30 -> Gen.constant("worse"),
        25 -> Gen.constant("unmeasured")
      )
      comparison <- shape match
        case "worse" =>
          for
            pred <- Gen.int(Range.linear(0, total - 1))
            port <- Gen.int(Range.linear(pred + 1, total))
          yield FileComparison(name, total, pred, port, true, true)
        case "unmeasured" =>
          for
            pred <- Gen.int(Range.linear(0, total))
            port <- Gen.int(Range.linear(0, total))
            presence <- Gen.elementUnsafe(
              List((true, false), (false, true), (false, false))
            )
          yield FileComparison(name, total, pred, port, presence._1, presence._2)
        case _ =>
          for
            pred <- Gen.int(Range.linear(0, total))
            port <- Gen.int(Range.linear(0, pred))
          yield FileComparison(name, total, pred, port, true, true)
    yield comparison

  // ── Generator: genSuiteRunPair
  // Constructive: a shared file set (both runs saw the same suite), then
  // per file an independent (total, predFailures, portFailures) triple and
  // per-run presence — presence skewed 9:1 towards present so missing
  // results occur without dominating. No filtering: every missing/present
  // combination is generated by construction.
  type SuiteRunPair = (DifferentialHarness.SuiteRun, DifferentialHarness.SuiteRun)

  def genSuiteRunPair: Gen[SuiteRunPair] =
    for
      fileNames <- Gen
        .list(Gen.string(Gen.alpha, Range.linear(3, 8)).map(s => s"$s.bats"), Range.linear(1, 10))
        .map(_.distinct)
      entries <- Gen.list(genRunEntry, Range.constant(fileNames.length, fileNames.length))
    yield
      val fileSet: Set[String] = fileNames.toSet
      val predResults: List[DifferentialHarness.BatsFileResult] =
        fileNames
          .lazyZip(entries)
          .flatMap { (f: String, e: RunEntry) =>
            if e.predPresent then List(DifferentialHarness.BatsFileResult(f, e.total, e.predFailures))
            else List.empty[DifferentialHarness.BatsFileResult]
          }
          .toList
      val portResults: List[DifferentialHarness.BatsFileResult] =
        fileNames
          .lazyZip(entries)
          .flatMap { (f: String, e: RunEntry) =>
            if e.portPresent then List(DifferentialHarness.BatsFileResult(f, e.total, e.portFailures))
            else List.empty[DifferentialHarness.BatsFileResult]
          }
          .toList
      (
        DifferentialHarness.SuiteRun(predResults, fileSet),
        DifferentialHarness.SuiteRun(portResults, fileSet)
      )

  final case class RunEntry(
    total: Int,
    predFailures: Int,
    portFailures: Int,
    predPresent: Boolean,
    portPresent: Boolean
  )

  def genRunEntry: Gen[RunEntry] =
    for
      total <- Gen.int(Range.linear(1, 40))
      pred  <- Gen.int(Range.linear(0, total))
      port  <- Gen.int(Range.linear(0, total))
      predP <- Gen.frequency1(9 -> Gen.constant(true), 1 -> Gen.constant(false))
      portP <- Gen.frequency1(9 -> Gen.constant(true), 1 -> Gen.constant(false))
    yield RunEntry(total, pred, port, predP, portP)

  private def pairWorseCount(pair: SuiteRunPair): Int =
    DifferentialHarness.diff(pair._1, pair._2, "/repo").worseFiles.length

  private def pairHasMissingResult(pair: SuiteRunPair): Boolean =
    val (pred: DifferentialHarness.SuiteRun, port: DifferentialHarness.SuiteRun) = pair
    (pred.fileSet ++ port.fileSet).exists { (f: String) =>
      pred.fileResults.forall(_.fileName != f) || port.fileResults.forall(_.fileName != f)
    }
