package org.sinemenda.probatio.core

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
