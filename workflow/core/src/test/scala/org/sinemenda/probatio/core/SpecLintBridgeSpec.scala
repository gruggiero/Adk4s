package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.verified.SpecLintKernel

import scala.collection.immutable.List as ScalaList

/**
 * Ring 6 bridge for `SpecLintKernel.reachabilityFold` — the exact-
 * complement contract of spec `spec-lint-engine`.
 *
 * A generated `SpecDocument` is reduced to the kernel's input space:
 * `(numRequirements, rowTargets)` where each evaluated obligation row
 * contributes the requirement indices its source resolved to, or `-1`
 * for a row that resolved to none. The property runs the shipped
 * `SpecLintEngine.reachabilityFold` and the Stainless mirror on the same
 * vectors and asserts the `(unenforced, unresolvableCount)` pairs agree —
 * and that the shipped unenforced set is exactly the requirement indices
 * that would draw F7.
 *
 * spec: spec-lint-engine — Contract: reachabilityFold (Ring 6 bridge)
 */
final class SpecLintBridgeSpec extends ProbatioSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(200))

  private def scalaToStainlessList[A](xs: ScalaList[A]): stainless.collection.List[A] =
    stainless.collection.List.fromScala(xs)

  private def stainlessListToScala[A](xs: stainless.collection.List[A]): ScalaList[A] =
    xs match
      case stainless.collection.Nil()      => ScalaList.empty
      case stainless.collection.Cons(h, t) => h :: stainlessListToScala(t)

  /**
   * The document → `(count, rowTargets)` reduction: per evaluated row,
   * the requirement indices its source resolves to (`ByTitle` /
   * `ByOrdinal`), or a single `-1` when it resolves to none.
   */
  private def rowTargetsOf(doc: SpecDocument): ScalaList[Int] =
    doc.obligationRows.flatMap { (row: ObligationRow) =>
      val covered: ScalaList[Int] = SpecLintEngine.obligationSources(doc, row).collect {
        case ObligationSource.ByTitle(i)   => i
        case ObligationSource.ByOrdinal(i) => i
      }
      if covered.isEmpty then ScalaList(-1) else covered
    }

  // spec: spec-lint-engine — Contract: reachabilityFold (bridge)
  property("bridge-reachabilityFold — mirror equals the shipped fold on generated documents", coverConfig):
    for doc <- SpecLintFixtures.genSpecDocument.forAll
        .cover(
          30,
          "some-unenforced",
          (d: SpecDocument) =>
            val targets: ScalaList[Int] = rowTargetsOf(d).filter(_ >= 0).distinct
            d.requirements.indices.exists(i => !targets.contains(i))
        )
        .cover(20, "has-unresolvable", (d: SpecDocument) => rowTargetsOf(d).contains(-1))
        // The generator gives nReqs=0 a 10% weight — a 10% label would be
        // a coin-flip at 200 samples, so the threshold sits below the mean.
        .cover(5, "no-requirements", (d: SpecDocument) => d.requirements.isEmpty)
    yield
      val nReqs: Int                 = doc.requirements.length
      val rowTargets: ScalaList[Int] = rowTargetsOf(doc)

      val (shipUnenforced, shipUnresolvable): (ScalaList[Int], Int) =
        SpecLintEngine.reachabilityFold(nReqs, rowTargets)

      val kernPair: (stainless.collection.List[BigInt], BigInt) =
        SpecLintKernel.reachabilityFold(
          BigInt(nReqs),
          scalaToStainlessList(rowTargets.map(BigInt.apply))
        )
      val kernUnenforced: ScalaList[Int] = stainlessListToScala(kernPair._1).map(_.toInt)
      val kernUnresolvable: Int          = kernPair._2.toInt

      // The shipped unenforced set is exactly the indices that draw F7.
      val coveredIdx: Set[Int] = rowTargets.filter(_ >= 0).toSet
      val expectedUnenforced: ScalaList[Int] =
        doc.requirements.indices.filterNot(coveredIdx.contains).toList

      Result
        .assert(shipUnenforced == kernUnenforced && shipUnresolvable == kernUnresolvable)
        .log(s"shipped ($shipUnenforced, $shipUnresolvable) != kernel ($kernUnenforced, $kernUnresolvable)")
        .and(
          Result
            .assert(shipUnenforced == expectedUnenforced)
            .log(s"unenforced $shipUnenforced != F7 set $expectedUnenforced")
        )

  /**
   * The fold-level bridge on arbitrary precondition-satisfying vectors —
   * reaches target shapes a generated document may not produce.
   */
  property("bridge-reachabilityFold — mirror equals the shipped fold on arbitrary target vectors"):
    val genVector: Gen[(Int, ScalaList[Int])] =
      for
        n <- Gen.int(Range.linear(0, 8))
        t <-
          // With zero requirements no in-range target exists — only -1.
          if n == 0 then Gen.constant(-1).list(Range.linear(0, 12))
          else
            Gen
              .frequency1(
                60 -> Gen.int(Range.linear(0, n - 1)),
                40 -> Gen.constant(-1)
              )
              .list(Range.linear(0, 12))
      yield (n, t)
    for (n, targets) <- genVector.forAll
        .cover(30, "has-unresolvable", (p: (Int, ScalaList[Int])) => p._2.contains(-1))
        .cover(30, "has-covered", (p: (Int, ScalaList[Int])) => p._2.exists(_ >= 0))
    yield
      val shipped: (ScalaList[Int], Int) = SpecLintEngine.reachabilityFold(n, targets)
      val kern: (stainless.collection.List[BigInt], BigInt) =
        SpecLintKernel.reachabilityFold(BigInt(n), scalaToStainlessList(targets.map(BigInt.apply)))
      Result
        .assert(
          shipped._1 == stainlessListToScala(kern._1).map(_.toInt) && shipped._2 == kern._2.toInt
        )
        .log(s"shipped $shipped != kernel (${kern._1}, ${kern._2}) on n=$n targets=$targets")

end SpecLintBridgeSpec
