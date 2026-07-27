package org.adk4s.optimize

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.optimize.ToyPrograms.*
import org.adk4s.optimize.testkit.OptimizerLaws

/**
 * Hedgehog properties + scenario tests for the optimizer-laws testkit.
 *
 * Covers the 3 optimizer-laws properties (Ring 3):
 *  12. optimizer-laws-purity
 *  13. optimizer-laws-frozen-preserved
 *  14. optimizer-laws-path-set-preserved
 */
class OptimizerLawsSpec extends HedgehogSuite:

  // ── Scenario tests ──────────────────────────────────────────────────────

  test("laws pass for the instruction-rewriting optimizer"):
    val laws: OptimizerLaws[TwoPredictors] = OptimizerLaws[TwoPredictors]
    val p: TwoPredictors                   = TwoPredictors(pred("a", false), pred("b", false), "x")
    val result: Boolean                    = laws.all(UppercaseInstructions.compile[TwoPredictors])(p).unsafeRunSync()
    assert(result)

  test("laws pass for the demo-injecting optimizer"):
    val laws: OptimizerLaws[TwoPredictors] = OptimizerLaws[TwoPredictors]
    val p: TwoPredictors                   = TwoPredictors(pred("a", false), pred("b", false), "x")
    val result: Boolean                    = laws.all(StaticDemoInjector.compile[TwoPredictors])(p).unsafeRunSync()
    assert(result)

  test("laws pass for both optimizers with frozen predictors"):
    val laws: OptimizerLaws[TwoPredictors] = OptimizerLaws[TwoPredictors]
    val p: TwoPredictors                   = TwoPredictors(pred("a", true), pred("b", false), "x")
    val upperResult: Boolean               = laws.all(UppercaseInstructions.compile[TwoPredictors])(p).unsafeRunSync()
    val demoResult: Boolean                = laws.all(StaticDemoInjector.compile[TwoPredictors])(p).unsafeRunSync()
    assert(upperResult)
    assert(demoResult)

  // ── Hedgehog properties ─────────────────────────────────────────────────

  property("optimizer-laws-purity"):
    for
      instrA  <- genString.forAll
      instrB  <- genString.forAll
      frozenA <- genBoolean.forAll
      frozenB <- genBoolean.forAll
    yield
      val p: TwoPredictors                   = TwoPredictors(pred(instrA, frozenA), pred(instrB, frozenB), "x")
      val laws: OptimizerLaws[TwoPredictors] = OptimizerLaws[TwoPredictors]
      val upperPurity: Boolean =
        laws.purity(UppercaseInstructions.compile[TwoPredictors])(p).unsafeRunSync()
      val demoPurity: Boolean =
        laws.purity(StaticDemoInjector.compile[TwoPredictors])(p).unsafeRunSync()
      (upperPurity && demoPurity) ==== true

  property("optimizer-laws-frozen-preserved"):
    for
      instrA  <- genString.forAll
      instrB  <- genString.forAll
      frozenA <- genBoolean.forAll
      frozenB <- genBoolean.forAll
    yield
      val p: TwoPredictors                   = TwoPredictors(pred(instrA, frozenA), pred(instrB, frozenB), "x")
      val laws: OptimizerLaws[TwoPredictors] = OptimizerLaws[TwoPredictors]
      val upperFrozen: Boolean =
        laws.frozenPreserved(UppercaseInstructions.compile[TwoPredictors])(p).unsafeRunSync()
      val demoFrozen: Boolean =
        laws.frozenPreserved(StaticDemoInjector.compile[TwoPredictors])(p).unsafeRunSync()
      (upperFrozen && demoFrozen) ==== true

  property("optimizer-laws-path-set-preserved"):
    for
      instrA  <- genString.forAll
      instrB  <- genString.forAll
      frozenA <- genBoolean.forAll
      frozenB <- genBoolean.forAll
    yield
      val p: TwoPredictors                   = TwoPredictors(pred(instrA, frozenA), pred(instrB, frozenB), "x")
      val laws: OptimizerLaws[TwoPredictors] = OptimizerLaws[TwoPredictors]
      val upperPaths: Boolean =
        laws.pathSetPreserved(UppercaseInstructions.compile[TwoPredictors])(p).unsafeRunSync()
      val demoPaths: Boolean =
        laws.pathSetPreserved(StaticDemoInjector.compile[TwoPredictors])(p).unsafeRunSync()
      (upperPaths && demoPaths) ==== true

  // ── Generators ──────────────────────────────────────────────────────────

  private def genString: Gen[String] =
    Gen.string(Gen.alpha, Range.linear(1, 10))

  private def genBoolean: Gen[Boolean] =
    Gen.boolean
