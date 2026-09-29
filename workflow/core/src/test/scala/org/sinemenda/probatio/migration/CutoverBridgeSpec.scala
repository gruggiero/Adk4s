package org.sinemenda.probatio.migration

import hedgehog.*
import org.sinemenda.probatio.core.ProbatioSuite
import org.sinemenda.probatio.verified.CutoverKernel

import scala.collection.immutable.List as ScalaList

import SeamTypes.*

/**
 * Ring 6 bridge spec — binds the shipped `CutoverGate.decide` to its
 * PureScala mirror model `CutoverKernel.cutoverDecision`.
 *
 * The model uses `BigInt` count vectors because Stainless does not
 * support `String` or `Int` operations in verification. The bridge
 * compares STRUCTURAL properties: the shipped gate's proceed/revert
 * decision must agree with the model's Boolean decision on the same
 * count vectors.
 *
 * Uses MUnit native assertions so that Stryker4s can detect killed
 * mutants.
 *
 * spec: cutover-gate — Formal Contracts (Ring 6)
 * spec: cutover-gate — Contract: cutoverDecision
 */
final class CutoverBridgeSpec extends ProbatioSuite:

  // ── Helpers: Scala ↔ Stainless type conversions ──────────────────────────

  /** Convert a Scala List[Int] to a Stainless List[BigInt]. */
  def toBigIntList(xs: ScalaList[Int]): stainless.collection.List[BigInt] =
    stainless.collection.List.fromScala(xs.map(BigInt(_)))

  /**
   * Extract predecessor failure counts from a DifferentialResult as a
   * Scala List[Int], ordered by file name.
   */
  def predecessorCounts(d: DifferentialResult): ScalaList[Int] =
    d.files.sortBy(_.fileName).map(_.predecessorFailures)

  /**
   * Extract ported failure counts from a DifferentialResult as a
   * Scala List[Int], ordered by file name.
   */
  def portedCounts(d: DifferentialResult): ScalaList[Int] =
    d.files.sortBy(_.fileName).map(_.portedFailures)

  // ── Bridge: shipped gate agrees with model on complete comparisons ───────

  test("bridge-cutover-complete-proceed — both agree on proceed"):
    val d: DifferentialResult = completeDifferential(
      ScalaList(("file1.bats", 10, 5, 3), ("file2.bats", 10, 2, 2))
    )
    val shippedVerdict: CutoverVerdict                = CutoverGate.decide(d)
    val predList: stainless.collection.List[BigInt]   = toBigIntList(predecessorCounts(d))
    val portedList: stainless.collection.List[BigInt] = toBigIntList(portedCounts(d))
    val modelDecision: Boolean                        = CutoverKernel.cutoverDecision(predList, portedList)
    assert(shippedVerdict == CutoverVerdict.Proceed, s"shipped gate must proceed, got $shippedVerdict")
    assert(modelDecision, "model must return true (proceed) when no file is worse")

  test("bridge-cutover-complete-revert — both agree on revert"):
    val d: DifferentialResult = completeDifferential(
      ScalaList(("file1.bats", 10, 3, 5), ("file2.bats", 10, 5, 2))
    )
    val shippedVerdict: CutoverVerdict                = CutoverGate.decide(d)
    val predList: stainless.collection.List[BigInt]   = toBigIntList(predecessorCounts(d))
    val portedList: stainless.collection.List[BigInt] = toBigIntList(portedCounts(d))
    val modelDecision: Boolean                        = CutoverKernel.cutoverDecision(predList, portedList)
    assert(shippedVerdict != CutoverVerdict.Proceed, s"shipped gate must revert (file1 is worse), got $shippedVerdict")
    assert(!modelDecision, "model must return false (revert) when a file is worse")

  test("bridge-cutover-regression-blocks-despite-total-improvement"):
    // file1: pred=10, ported=12 (regression, +2)
    // file2: pred=10, ported=5  (improvement, -5)
    // total: pred=20, ported=17 (improvement), but gate reverts
    val d: DifferentialResult = completeDifferential(
      ScalaList(("file1.bats", 10, 10, 12), ("file2.bats", 10, 10, 5))
    )
    val shippedVerdict: CutoverVerdict                = CutoverGate.decide(d)
    val predList: stainless.collection.List[BigInt]   = toBigIntList(predecessorCounts(d))
    val portedList: stainless.collection.List[BigInt] = toBigIntList(portedCounts(d))
    val modelDecision: Boolean                        = CutoverKernel.cutoverDecision(predList, portedList)
    assert(shippedVerdict != CutoverVerdict.Proceed, "shipped gate must revert despite total improvement")
    assert(!modelDecision, "model must return false (revert) despite total improvement")

  test("bridge-cutover-all-equal — both agree on proceed"):
    val d: DifferentialResult = completeDifferential(
      ScalaList(("file1.bats", 10, 3, 3), ("file2.bats", 10, 5, 5))
    )
    val shippedVerdict: CutoverVerdict                = CutoverGate.decide(d)
    val predList: stainless.collection.List[BigInt]   = toBigIntList(predecessorCounts(d))
    val portedList: stainless.collection.List[BigInt] = toBigIntList(portedCounts(d))
    val modelDecision: Boolean                        = CutoverKernel.cutoverDecision(predList, portedList)
    assert(shippedVerdict == CutoverVerdict.Proceed, "shipped gate must proceed when all files are equal")
    assert(modelDecision, "model must return true (proceed) when all files are equal")

  test("bridge-cutover-empty — both agree on proceed (vacuous)"):
    val d: DifferentialResult          = DifferentialResult(ScalaList.empty, "/repo", Set.empty)
    val shippedVerdict: CutoverVerdict = CutoverGate.decide(d)
    val modelDecision: Boolean =
      CutoverKernel.cutoverDecision(
        stainless.collection.List.empty[BigInt],
        stainless.collection.List.empty[BigInt]
      )
    // An empty comparison is complete (vacuously) and has no regressions.
    // The shipped gate proceeds; the model returns true.
    assert(
      shippedVerdict == CutoverVerdict.Proceed,
      s"shipped gate must proceed on empty complete comparison, got $shippedVerdict"
    )
    assert(modelDecision, "model must return true (proceed) on empty vectors")

  // ── Bridge property: shipped and model agree on generated inputs ─────────

  property("bridge-cutover-property — shipped and model agree"):
    for d <- genBridgeDifferential.forAll
    yield
      if d.isComplete then
        val predList: stainless.collection.List[BigInt]   = toBigIntList(predecessorCounts(d))
        val portedList: stainless.collection.List[BigInt] = toBigIntList(portedCounts(d))
        val modelDecision: Boolean                        = CutoverKernel.cutoverDecision(predList, portedList)
        val shippedProceeds: Boolean                      = CutoverGate.decide(d) == CutoverVerdict.Proceed
        Result
          .assert(modelDecision == shippedProceeds)
          .log(s"model=$modelDecision shipped=$shippedProceeds d=$d")
      else Result.success

  // ── Bridge: shipped divergence agrees with model divergenceDecision ──────
  // spec: differential-harness-integrity — Contract: divergence
  //
  // The model abstracts digests as BigInt tokens; the bridge assigns each
  // distinct shipped digest a token (equal digests → equal tokens), runs the
  // verified `divergenceDecision` on the token vectors, and asserts the
  // shipped verdict agrees: identical ⟺ ArmsIdentical, and a diverged
  // verdict's firstDiff names the shipped first differing seam's position.

  property("bridge-divergence — shipped and model agree on the verdict"):
    for pair <- genBridgeArmPair.forAll
    yield
      val (left, right): (ScalaList[SeamResolution], ScalaList[SeamResolution]) = pair
      val tokenOf: Map[String, Int] =
        (left ++ right)
          .map((r: SeamResolution) => ContentDigest.hex(r.implementationDigest))
          .distinct
          .sorted
          .zipWithIndex
          .toMap
      val leftTokens: stainless.collection.List[BigInt] =
        toBigIntList(left.map((r: SeamResolution) => tokenOf(ContentDigest.hex(r.implementationDigest))))
      val rightTokens: stainless.collection.List[BigInt] =
        toBigIntList(right.map((r: SeamResolution) => tokenOf(ContentDigest.hex(r.implementationDigest))))
      val shipped: ArmDivergence          = DifferentialHarness.divergence(left, right)
      val model: CutoverKernel.ArmVerdict = CutoverKernel.divergenceDecision(leftTokens, rightTokens)
      model match
        case CutoverKernel.ArmsIdentical =>
          Result
            .assert(shipped.isIdentical)
            .log(s"model refused (identical tokens) but shipped diverged: $shipped")
        case CutoverKernel.ArmsDiverged(i) =>
          val idx: Int = i.toInt
          Result
            .assert(!shipped.isIdentical)
            .log(s"model diverged at $idx but shipped refused: $shipped")
            .and(
              Result
                .assert(shipped.divergingSeams.headOption.contains(left(idx).seam))
                .log(
                  s"model firstDiff=$idx (${left(idx).seam}) but shipped first differs at ${shipped.divergingSeams.headOption}"
                )
            )

  // ── Bridge: shipped worseFiles agrees with model worseIndices ────────────
  // spec: differential-harness-integrity — Contract: worseFiles
  //
  // Bridged on COMPLETE comparisons only: the shipped `isWorse` additionally
  // requires both runs present, which the model's plain count vectors cannot
  // express. On complete data the conjuncts are vacuously true and the two
  // folds must name the same positions.

  property("bridge-worse-indices — shipped and model name the same worse files"):
    for d <- genBridgeDifferential.forAll
    yield
      if d.isComplete then
        val sorted: ScalaList[FileComparison]             = d.files.sortBy(_.fileName)
        val predList: stainless.collection.List[BigInt]   = toBigIntList(sorted.map(_.predecessorFailures))
        val portedList: stainless.collection.List[BigInt] = toBigIntList(sorted.map(_.portedFailures))
        val modelIdxs: Set[Int] =
          CutoverKernel.worseIndices(predList, portedList).toScala.map((b: BigInt) => b.toInt).toSet
        val shippedIdxs: Set[Int] =
          sorted.zipWithIndex.collect { case (f, i) if f.isWorse => i }.toSet
        Result
          .assert(modelIdxs == shippedIdxs)
          .log(s"model worse=$modelIdxs shipped worse=$shippedIdxs files=$sorted")
      else Result.success

  // ════════════════════════════════════════════════════════════════════════
  // Helpers
  // ════════════════════════════════════════════════════════════════════════

  def completeDifferential(
    entries: ScalaList[(String, Int, Int, Int)]
  ): DifferentialResult =
    val files: ScalaList[FileComparison] = entries.map { case (name, total, pred, port) =>
      FileComparison(name, total, pred, port, true, true)
    }
    val fileSet: Set[String] = entries.map(_._1).toSet
    DifferentialResult(files, "/repo", fileSet)

  def genBridgeDifferential: Gen[DifferentialResult] =
    for files <- Gen.list(genBridgeFile, Range.linear(1, 10))
    yield
      val fileSet: Set[String] = files.map(_.fileName).toSet
      DifferentialResult(files, "/repo", fileSet)

  def genBridgeFile: Gen[FileComparison] =
    for
      name  <- Gen.string(Gen.alpha, Range.linear(3, 10)).map(s => s"$s.bats")
      total <- Gen.int(Range.linear(1, 40))
      pred  <- Gen.int(Range.linear(0, total))
      port  <- Gen.int(Range.linear(0, total))
    yield FileComparison(name, total, pred, port, true, true)

  /**
   * A pair of seven-seam resolution vectors, content drawn from a small
   * alphabet so identical and diverged outcomes are both frequent.
   */
  def genBridgeArmPair: Gen[(ScalaList[SeamResolution], ScalaList[SeamResolution])] =
    for
      leftContents  <- Gen.list(Gen.int(Range.linear(0, 3)), Range.constant(7, 7))
      rightContents <- Gen.list(Gen.int(Range.linear(0, 3)), Range.constant(7, 7))
    yield
      val left: ScalaList[SeamResolution] =
        ToolId.swapOrder.lazyZip(leftContents).map((s, c) => bridgeResolution(s, c, "/left"))
      val right: ScalaList[SeamResolution] =
        ToolId.swapOrder.lazyZip(rightContents).map((s, c) => bridgeResolution(s, c, "/right"))
      (left, right)

  def bridgeResolution(seam: ToolId, contentIdx: Int, pathPrefix: String): SeamResolution =
    SeamResolution(
      seam,
      ContentDigest.ofBytes(s"bridge-content-$contentIdx".getBytes("UTF-8")),
      os.Path(pathPrefix) / os.RelPath(ToolId.seamPath(seam))
    )
