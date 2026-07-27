package org.adk4s.optimize

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.adk4s.optimize.ToyPrograms.*

/**
 * Scenario tests for the two toy optimizers (acceptance test for the surface).
 *
 * Covers the Proof Obligations:
 *  - Two toy optimizers compile same program via one optimizable-surface
 *  - Instruction-rewriting optimizer uppercases non-frozen instructions
 *  - Demo-injecting optimizer appends a demo to non-frozen predictors
 *  - Both toy optimizers leave frozen predictors untouched
 */
class ToyOptimizerSpec extends FunSuite:

  /** Unsafe extraction helper for tests where the Option is known to be defined. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def unwrap[A](opt: Option[A]): A = opt match
    case Some(a) => a
    case None    => throw new RuntimeException("expected Some, got None")

  test("instruction-rewriting optimizer uppercases instructions"):
    val p: TwoPredictors               = TwoPredictors(pred("a", false), pred("b", false), "x")
    val result: TwoPredictors          = UppercaseInstructions.compile(p).unsafeRunSync()
    val states: Vector[PredictorState] = Optimizable[TwoPredictors].predictors(result).map(_._2)
    assertEquals(states.map(_.instructions).toSet, Set("A", "B"))

  test("demo-injecting optimizer appends a demo"):
    val p: TwoPredictors               = TwoPredictors(pred("a", false), pred("b", false), "x")
    val result: TwoPredictors          = StaticDemoInjector.compile(p).unsafeRunSync()
    val states: Vector[PredictorState] = Optimizable[TwoPredictors].predictors(result).map(_._2)
    assert(states.forall(_.demos.size == 1))

  test("both toy optimizers leave frozen predictors untouched"):
    val p: TwoPredictors = TwoPredictors(pred("a", true), pred("b", false), "x")
    val frozenBefore: PredictorState =
      unwrap(Optimizable[TwoPredictors].predictors(p).find(_._1.segments == Vector("a")))._2

    val upperResult: TwoPredictors = UppercaseInstructions.compile(p).unsafeRunSync()
    val frozenAfterUpper: PredictorState =
      unwrap(Optimizable[TwoPredictors].predictors(upperResult).find(_._1.segments == Vector("a")))._2
    assertEquals(frozenAfterUpper, frozenBefore)

    val demoResult: TwoPredictors = StaticDemoInjector.compile(p).unsafeRunSync()
    val frozenAfterDemo: PredictorState =
      unwrap(Optimizable[TwoPredictors].predictors(demoResult).find(_._1.segments == Vector("a")))._2
    assertEquals(frozenAfterDemo, frozenBefore)

  test("two toy optimizers compile same program via one optimizable-surface"):
    val opt: Optimizable[TwoPredictors] = Optimizable[TwoPredictors]
    val p: TwoPredictors                = TwoPredictors(pred("hello", false), pred("world", false), "x")

    val upperResult: TwoPredictors = UppercaseInstructions.compile(p)(using opt).unsafeRunSync()
    val demoResult: TwoPredictors  = StaticDemoInjector.compile(p)(using opt).unsafeRunSync()

    assert(upperResult != p)
    assert(demoResult != p)

    val inputPaths: Set[PredictorPath] = opt.predictors(p).map(_._1).toSet
    assertEquals(opt.predictors(upperResult).map(_._1).toSet, inputPaths)
    assertEquals(opt.predictors(demoResult).map(_._1).toSet, inputPaths)
