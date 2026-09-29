package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import org.sinemenda.probatio.migration.CutoverGate
import org.sinemenda.probatio.migration.CutoverVerdict
import org.sinemenda.probatio.migration.DifferentialResult
import org.sinemenda.probatio.migration.FileComparison
import org.sinemenda.probatio.migration.GateRecord
import org.sinemenda.probatio.migration.SeamTypes
import org.sinemenda.probatio.verified.LedgerValidatorKernel

import scala.collection.immutable.List as ScalaList

/**
 * Ring 6 bridge spec — binds the shipped `CheckpointEngine.markerDecision`
 * to its PureScala mirror `LedgerValidatorKernel.markerDecision`.
 *
 * The mirror reduces the checkpoint inputs to
 * `(requestedRings: List[BigInt], evidencedRings: List[BigInt],
 * unresolvedCount: BigInt)` — the spec's stated abstraction. The bridge
 * enumerates the input space (the ring set is finite) and asserts the
 * shipped decision equals the verified model on every input.
 *
 * spec: ledger-checkpoint-parity — Formal Contracts (Ring 6 bridge)
 * spec: ledger-checkpoint-parity — Contract: markerDecision
 */
final class CheckpointBridgeSpec extends ProbatioSuite:

  /** The shipped ring domain, indexed for the BigInt abstraction. */
  private val shippedRings: ScalaList[Ring] =
    ScalaList(Ring.R0, Ring.R1, Ring.R2, Ring.R3, Ring.R4, Ring.R5, Ring.R6, Ring.R7, Ring.R8, Ring.R9, Ring.Manual)

  /**
   * Every subset of an 11-element set is enumerable in 2048 cases —
   *  a sampled property over the same space.
   */
  private val requestedChoices: ScalaList[ScalaList[Ring]] =
    ScalaList(
      ScalaList(Ring.R0, Ring.R1, Ring.R2),
      ScalaList(Ring.R8),
      ScalaList(Ring.Manual),
      ScalaList(Ring.R0),
      ScalaList(Ring.R3, Ring.R5, Ring.R8, Ring.R9)
    )

  private val evidencedChoices: ScalaList[ScalaList[Ring]] =
    ScalaList(
      ScalaList.empty,
      ScalaList(Ring.R0),
      ScalaList(Ring.R0, Ring.R1, Ring.R2),
      ScalaList(Ring.R8, Ring.Manual),
      shippedRings
    )

  private val unresolvedChoices: ScalaList[Int] = ScalaList(0, 1, 2, 5)

  private def toBigInts(rings: ScalaList[Ring]): stainless.collection.List[BigInt] =
    stainless.collection.List.fromScala(rings.map((r: Ring) => BigInt(shippedRings.indexOf(r))))

  /**
   * The bridge: for every (requested, evidenced, unresolved) triple the
   * shipped `CheckpointEngine.markerDecision` agrees with the Stainless
   * mirror `LedgerValidatorKernel.markerDecision`.
   */
  test("shipped markerDecision agrees with the verified mirror on the enumerated input space"):
    val cases: ScalaList[(ScalaList[Ring], ScalaList[Ring], Int)] =
      for
        requested  <- requestedChoices
        evidenced  <- evidencedChoices
        unresolved <- unresolvedChoices
      yield (requested, evidenced, unresolved)
    cases.foreach { case (requested: ScalaList[Ring], evidenced: ScalaList[Ring], unresolved: Int) =>
      val shipped: Boolean =
        CheckpointEngine.markerDecision(requested, evidenced, unresolved)
      val model: Boolean =
        LedgerValidatorKernel.markerDecision(
          toBigInts(requested),
          toBigInts(evidenced),
          BigInt(unresolved)
        )
      assertEquals(
        shipped,
        model,
        s"divergence at requested=$requested evidenced=$evidenced unresolved=$unresolved"
      )
    }
    assertEquals(cases.length, requestedChoices.length * evidencedChoices.length * unresolvedChoices.length)

  // ── The mirror's stated laws, spot-checked through the shipped side ──

  test("marker iff every requested ring evidenced and nothing unresolved"):
    // The kernel's postcondition, restated through the shipped engine:
    //   granted == (all requested in evidenced) && unresolved == 0
    val granted: Boolean =
      CheckpointEngine.markerDecision(ScalaList(Ring.R0, Ring.R1), ScalaList(Ring.R0, Ring.R1, Ring.R8), 0)
    assert(granted, "all requested evidenced + zero unresolved must grant")

    assert(
      !CheckpointEngine.markerDecision(ScalaList(Ring.R0, Ring.R8), ScalaList(Ring.R0), 0),
      "a missing requested ring must deny"
    )
    assert(
      !CheckpointEngine.markerDecision(ScalaList(Ring.R0), ScalaList(Ring.R0), 3),
      "unresolved requirements must deny"
    )
    assert(
      !CheckpointEngine.markerDecision(ScalaList(Ring.R0), ScalaList.empty, 0),
      "an empty evidenced set must deny"
    )
    // An empty requested set is vacuously discharged — the mirror's
    // forall over Nil is true; the shipped side must agree.
    assert(
      CheckpointEngine.markerDecision(ScalaList.empty, ScalaList.empty, 0),
      "no requested rings + no unresolved = granted"
    )

  // ── Spec 8: the swap-authorisation decision ─────────────────────────
  // spec: ledger-checkpoint-cutover — Contract: authoriseSwap
  //
  // The bridge binds the shipped `CutoverGate.authoriseSwap` — the
  // seam-scoped decision the swap record rests on — to its PureScala
  // mirror `LedgerValidatorKernel.authoriseSwap`. The mirror abstracts
  // file names away: `namedFiles` carries the positions of justifying
  // files (worse or unmeasured), compared index-for-index against the
  // shipped `justifyingFileNames`.
  //
  // Every generated file exercises the seam, so the shipped record's
  // scoped evidence IS the generated file list. The mirror's `authorised`
  // corresponds to the shipped verdict Proceed (on a non-empty file set
  // that equals `authorisesSwap`).

  property("bridge-authorise-swap — shipped record agrees with the verified mirror"):
    for files <- Gen.list(genBridgeSwapFile, Range.linear(1, 8)).forAll
    yield
      val seam: SeamTypes.ToolId = SeamTypes.ToolId.Ledger
      val d: DifferentialResult  = DifferentialResult(files, "/repo", files.map(_.fileName).toSet)
      val exercising: Map[String, Set[String]] =
        files.map((f: FileComparison) => f.fileName -> Set(SeamTypes.ToolId.seamPath(seam))).toMap
      val record: GateRecord = CutoverGate.authoriseSwap(seam, d, exercising)
      val modelFiles: stainless.collection.List[LedgerValidatorKernel.SwapFileComparison] =
        stainless.collection.List.fromScala(
          files.map((f: FileComparison) =>
            LedgerValidatorKernel.SwapFileComparison(
              f.predecessorPresent,
              f.portedPresent,
              BigInt(f.predecessorFailures),
              BigInt(f.portedFailures)
            )
          )
        )
      val model: LedgerValidatorKernel.SwapDecision = LedgerValidatorKernel.authoriseSwap(modelFiles)
      val shippedProceeds: Boolean                  = record.verdict == CutoverVerdict.Proceed
      val justifyingIdxs: Set[Int] =
        record.evidence.files.zipWithIndex.collect {
          case (f: FileComparison, i: Int) if f.isWorse || !(f.predecessorPresent && f.portedPresent) => i
        }.toSet
      Result
        .assert(model.authorised == shippedProceeds)
        .log(s"model.authorised=${model.authorised} shipped=${record.verdict} files=$files")
        .and(
          Result
            .assert(model.namedFiles.toScala.map((b: BigInt) => b.toInt).toSet == justifyingIdxs)
            .log(s"model named=${model.namedFiles} shipped justifying positions=$justifyingIdxs")
        )

  /** Per-file comparison triples with independent presence flags. */
  def genBridgeSwapFile: Gen[FileComparison] =
    for
      name  <- Gen.string(Gen.alpha, Range.linear(3, 10)).map(s => s"$s.bats")
      total <- Gen.int(Range.linear(1, 40))
      pred  <- Gen.int(Range.linear(0, total))
      port  <- Gen.int(Range.linear(0, total))
      predP <- Gen.frequency1(4 -> Gen.constant(true), 1 -> Gen.constant(false))
      portP <- Gen.frequency1(4 -> Gen.constant(true), 1 -> Gen.constant(false))
    yield FileComparison(name, total, pred, port, predP, portP)
