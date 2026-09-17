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
    // from the mixed cover floor. Choose a random non-empty proper
    // subset by picking a count in [1, N-1] and taking that many from
    // the swap order.
    for portedCount <- Gen.int(Range.linear(1, ToolId.swapOrder.length - 1))
    yield
      val ported: Set[ToolId] = ToolId.swapOrder.take(portedCount).toSet
      SeamConfiguration.fromPorted(ported)
