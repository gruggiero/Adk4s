package org.adk4s.harness

import hedgehog._
import hedgehog.munit.HedgehogSuite
import upickle.default.*
import org.adk4s.verified.HarnessStateKernel

/**
 * Ring 6 bridge spec — runs the real `HarnessState` and the
 * `HarnessStateKernel` model on the SAME generated cell ids and values,
 * and asserts they agree on the proven invariants:
 *
 *  1. get-set-coherence: `get(c)(set(c)(v)(s)) == v` — both real and model
 *  2. set-preserves-other-cells: `get(d)(set(c)(v)(s)) == get(d)(s)` for `c != d`
 *  3. get-absent-reads-initial: `get(c)(empty) == initial` (model: `get(c, empty) == 0`)
 *  4. update-coherence: `get(c)(update(c)(f)(s)) == f(get(c)(s))`
 *
 * The bridge maps real `StateCell[Int]` (with `initial = 0`) to model
 * `BigInt` keys (the cell's position in the declared list) and `BigInt`
 * values. The model's lemma functions (`getSetCoherence`, etc.) are also
 * evaluated at runtime to confirm they hold on the generated inputs.
 */
class HarnessStateKernelBridgeSpec extends HedgehogSuite:

  property("bridge-get-set-coherence — real and model agree"):
    for v <- Gen.int(Range.linear(-1000, 1000)).forAll
    yield
      given ReadWriter[Int]    = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
      val realS0: HarnessState = HarnessState.initial(List(cell))
      val realS1: HarnessState = realS0.set(cell)(v)
      val realGet: Int         = realS1.get(cell)

      val modelKey: BigInt                  = BigInt(0)
      val modelS0: HarnessStateKernel.State = HarnessStateKernel.empty
      val modelS1: HarnessStateKernel.State = HarnessStateKernel.set(modelKey, BigInt(v), modelS0)
      val modelGet: BigInt                  = HarnessStateKernel.get(modelKey, modelS1)
      val lemmaHolds: Boolean               = HarnessStateKernel.getSetCoherence(modelKey, BigInt(v), modelS0)

      (realGet ==== v).and(modelGet ==== BigInt(v)).and(lemmaHolds ==== true)

  property("bridge-set-preserves-other-cells — real and model agree"):
    for
      v1 <- Gen.int(Range.linear(-1000, 1000)).forAll
      v2 <- Gen.int(Range.linear(-1000, 1000)).forAll
    yield
      given ReadWriter[Int]    = readwriter[Int]
      val c1: StateCell[Int]   = StateCell[Int](MiddlewareName("m"), "c1", 0)
      val c2: StateCell[Int]   = StateCell[Int](MiddlewareName("m"), "c2", 0)
      val realS0: HarnessState = HarnessState.initial(List(c1, c2)).set(c1)(v1).set(c2)(v2)
      val realS1: HarnessState = realS0.set(c1)(999)
      val realGetC2: Int       = realS1.get(c2)

      val modelKey1: BigInt = BigInt(0)
      val modelKey2: BigInt = BigInt(1)
      val modelS0: HarnessStateKernel.State =
        HarnessStateKernel.set(
          modelKey1,
          BigInt(v1),
          HarnessStateKernel.set(modelKey2, BigInt(v2), HarnessStateKernel.empty)
        )
      val modelS1: HarnessStateKernel.State = HarnessStateKernel.set(modelKey1, BigInt(999), modelS0)
      val modelGetC2: BigInt                = HarnessStateKernel.get(modelKey2, modelS1)
      val lemmaHolds: Boolean = HarnessStateKernel.setPreservesOtherCells(modelKey1, modelKey2, BigInt(999), modelS0)

      (realGetC2 ==== v2).and(modelGetC2 ==== BigInt(v2)).and(lemmaHolds ==== true)

  property("bridge-get-absent-reads-initial — real and model agree"):
    for initial <- Gen.int(Range.linear(-1000, 1000)).forAll
    yield
      given ReadWriter[Int]       = readwriter[Int]
      val cell: StateCell[Int]    = StateCell[Int](MiddlewareName("m"), "c", initial)
      val realState: HarnessState = HarnessState.empty
      val realGet: Int            = realState.get(cell)

      // Model: absent reads as 0 (the model's abstraction of "initial")
      // For the bridge to agree, we use initial=0 in the model
      val modelKey: BigInt    = BigInt(0)
      val modelGet: BigInt    = HarnessStateKernel.get(modelKey, HarnessStateKernel.empty)
      val lemmaHolds: Boolean = HarnessStateKernel.getAbsentReadsInitial(modelKey)

      // When initial == 0, both should return 0 and the lemma holds
      if initial == 0 then (realGet ==== 0).and(modelGet ==== BigInt(0)).and(lemmaHolds ==== true)
      else
        // Real reads `initial`, model reads `0` — the abstraction differs,
        // so we only check the real side here
        realGet ==== initial

  property("bridge-update-coherence — real and model agree"):
    for v <- Gen.int(Range.linear(-1000, 1000)).forAll
    yield
      given ReadWriter[Int]    = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
      val realS0: HarnessState = HarnessState.initial(List(cell)).set(cell)(v)
      val realS1: HarnessState = realS0.update(cell)(_ + 1)
      val realGet: Int         = realS1.get(cell)

      val modelKey: BigInt                  = BigInt(0)
      val modelS0: HarnessStateKernel.State = HarnessStateKernel.set(modelKey, BigInt(v), HarnessStateKernel.empty)
      val modelS1: HarnessStateKernel.State = HarnessStateKernel.update(modelKey, _ + BigInt(1), modelS0)
      val modelGet: BigInt                  = HarnessStateKernel.get(modelKey, modelS1)
      val lemmaHolds: Boolean               = HarnessStateKernel.updateCoherence(modelKey, _ + BigInt(1), modelS0)

      (realGet ==== (v + 1)).and(modelGet ==== BigInt(v + 1)).and(lemmaHolds ==== true)
