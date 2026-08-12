package org.adk4s.harness

import hedgehog._
import hedgehog.munit.HedgehogSuite
import org.adk4s.verified.SemilatticeKernel

/**
 * Ring 6 bridge spec — runs the real `StateCell.merge` and the
 * `SemilatticeKernel` model on the SAME generated merge functions and
 * values, and asserts they agree on the proven invariants:
 *
 *  1. commutativity: `merge(a, b) == merge(b, a)` — both real and model
 *  2. associativity: `merge(a, merge(b, c)) == merge(merge(a, b), c)` — both
 *  3. idempotence: `merge(a, a) == a` — both
 *
 * The bridge maps real `StateCell[Int]` (with `Shared` visibility and a
 * semilattice merge) to model `BigInt` values and the model's `intMax` /
 * `intMin` merge functions. The model's lemma functions
 * (`intMaxCommutative`, etc.) are also evaluated at runtime to confirm they
 * hold on the generated inputs.
 *
 * spec: middleware-laws — Bridge: how the shipped code is bound to the model
 */
class SemilatticeModelBridgeSpec extends HedgehogSuite:

  property("bridge-commutativity-intMax — real and model agree"):
    for
      a <- Gen.int(Range.linear(-1000, 1000)).forAll
      b <- Gen.int(Range.linear(-1000, 1000)).forAll
    yield
      val cell: StateCell[Int] = StateCell[Int](
        MiddlewareName("m"),
        "max",
        0,
        visibility = CellVisibility.Shared,
        merge = (x: Int, y: Int) => math.max(x, y)
      )
      val realComm: Boolean = cell.merge(a, b) == cell.merge(b, a)
      val modelComm: Boolean = SemilatticeKernel.commutative(
        SemilatticeKernel.intMax,
        BigInt(a),
        BigInt(b)
      )
      val lemmaHolds: Boolean = SemilatticeKernel.intMaxCommutative(BigInt(a), BigInt(b))
      (realComm ==== true).and(modelComm ==== true).and(lemmaHolds ==== true)

  property("bridge-associativity-intMax — real and model agree"):
    for
      a <- Gen.int(Range.linear(-1000, 1000)).forAll
      b <- Gen.int(Range.linear(-1000, 1000)).forAll
      c <- Gen.int(Range.linear(-1000, 1000)).forAll
    yield
      val cell: StateCell[Int] = StateCell[Int](
        MiddlewareName("m"),
        "max",
        0,
        visibility = CellVisibility.Shared,
        merge = (x: Int, y: Int) => math.max(x, y)
      )
      val realAssoc: Boolean =
        cell.merge(a, cell.merge(b, c)) == cell.merge(cell.merge(a, b), c)
      val modelAssoc: Boolean = SemilatticeKernel.associative(
        SemilatticeKernel.intMax,
        BigInt(a),
        BigInt(b),
        BigInt(c)
      )
      val lemmaHolds: Boolean =
        SemilatticeKernel.intMaxAssociative(BigInt(a), BigInt(b), BigInt(c))
      (realAssoc ==== true).and(modelAssoc ==== true).and(lemmaHolds ==== true)

  property("bridge-idempotence-intMax — real and model agree"):
    for a <- Gen.int(Range.linear(-1000, 1000)).forAll
    yield
      val cell: StateCell[Int] = StateCell[Int](
        MiddlewareName("m"),
        "max",
        0,
        visibility = CellVisibility.Shared,
        merge = (x: Int, y: Int) => math.max(x, y)
      )
      val realIdem: Boolean = cell.merge(a, a) == a
      val modelIdem: Boolean =
        SemilatticeKernel.idempotent(SemilatticeKernel.intMax, BigInt(a))
      val lemmaHolds: Boolean = SemilatticeKernel.intMaxIdempotent(BigInt(a))
      (realIdem ==== true).and(modelIdem ==== true).and(lemmaHolds ==== true)

  property("bridge-commutativity-intMin — real and model agree"):
    for
      a <- Gen.int(Range.linear(-1000, 1000)).forAll
      b <- Gen.int(Range.linear(-1000, 1000)).forAll
    yield
      val cell: StateCell[Int] = StateCell[Int](
        MiddlewareName("m"),
        "min",
        0,
        visibility = CellVisibility.Shared,
        merge = (x: Int, y: Int) => math.min(x, y)
      )
      val realComm: Boolean = cell.merge(a, b) == cell.merge(b, a)
      val modelComm: Boolean = SemilatticeKernel.commutative(
        SemilatticeKernel.intMin,
        BigInt(a),
        BigInt(b)
      )
      val lemmaHolds: Boolean = SemilatticeKernel.intMinCommutative(BigInt(a), BigInt(b))
      (realComm ==== true).and(modelComm ==== true).and(lemmaHolds ==== true)

  property("bridge-associativity-intMin — real and model agree"):
    for
      a <- Gen.int(Range.linear(-1000, 1000)).forAll
      b <- Gen.int(Range.linear(-1000, 1000)).forAll
      c <- Gen.int(Range.linear(-1000, 1000)).forAll
    yield
      val cell: StateCell[Int] = StateCell[Int](
        MiddlewareName("m"),
        "min",
        0,
        visibility = CellVisibility.Shared,
        merge = (x: Int, y: Int) => math.min(x, y)
      )
      val realAssoc: Boolean =
        cell.merge(a, cell.merge(b, c)) == cell.merge(cell.merge(a, b), c)
      val modelAssoc: Boolean = SemilatticeKernel.associative(
        SemilatticeKernel.intMin,
        BigInt(a),
        BigInt(b),
        BigInt(c)
      )
      val lemmaHolds: Boolean =
        SemilatticeKernel.intMinAssociative(BigInt(a), BigInt(b), BigInt(c))
      (realAssoc ==== true).and(modelAssoc ==== true).and(lemmaHolds ==== true)

  property("bridge-idempotence-intMin — real and model agree"):
    for a <- Gen.int(Range.linear(-1000, 1000)).forAll
    yield
      val cell: StateCell[Int] = StateCell[Int](
        MiddlewareName("m"),
        "min",
        0,
        visibility = CellVisibility.Shared,
        merge = (x: Int, y: Int) => math.min(x, y)
      )
      val realIdem: Boolean = cell.merge(a, a) == a
      val modelIdem: Boolean =
        SemilatticeKernel.idempotent(SemilatticeKernel.intMin, BigInt(a))
      val lemmaHolds: Boolean = SemilatticeKernel.intMinIdempotent(BigInt(a))
      (realIdem ==== true).and(modelIdem ==== true).and(lemmaHolds ==== true)

  property("bridge-isSemilattice-intMax — all three laws hold"):
    for
      a <- Gen.int(Range.linear(-1000, 1000)).forAll
      b <- Gen.int(Range.linear(-1000, 1000)).forAll
      c <- Gen.int(Range.linear(-1000, 1000)).forAll
    yield
      val modelHolds: Boolean = SemilatticeKernel.isSemilattice(
        SemilatticeKernel.intMax,
        BigInt(a),
        BigInt(b),
        BigInt(c)
      )
      modelHolds ==== true
