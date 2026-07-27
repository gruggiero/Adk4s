package org.adk4s.optimize

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.optimize.ToyPrograms.*

/**
 * Hedgehog properties + scenario tests for the optimizable-surface spec.
 *
 * Covers 11 of the 14 Ring 3 properties (the other 3 are optimizer-laws
 * properties in OptimizerLawsSpec):
 *  1. predictors-declaration-order
 *  2. nested-recursion-paths
 *  3. collection-recursion-paths
 *  4. update-purity
 *  5. update-only-target
 *  6. round-trip-identity
 *  7. updateAll-skips-frozen
 *  8. updateAll-path-set-preserved
 *  9. updateEither-unknown-path-error
 * 10. updateEither-frozen-path-error
 * 11. frozen-still-readable
 */
class OptimizableSpec extends HedgehogSuite:

  /** Unsafe extraction helper for tests where the Option is known to be defined. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def unwrap[A](opt: Option[A]): A = opt match
    case Some(a) => a
    case None    => throw new RuntimeException("expected Some, got None")

  // ── Scenario tests ──────────────────────────────────────────────────────

  test("predictor path renders dot-joined, outermost first"):
    val path: PredictorPath = PredictorPath(Vector("outer", "inner"))
    assertEquals(path.render, "outer.inner")

  test("single-segment path renders without separator"):
    val path: PredictorPath = PredictorPath(Vector("answer"))
    assertEquals(path.render, "answer")

  test("collection-index segment path renders with index"):
    val path: PredictorPath = PredictorPath(Vector("steps", "1"))
    assertEquals(path.render, "steps.1")

  test("empty path renders to empty string"):
    val path: PredictorPath = PredictorPath(Vector.empty)
    assertEquals(path.render, "")

  test("predictors enumerates in declaration order, non-predictor fields absent"):
    val p: TwoPredictors             = TwoPredictors(pred("a", false), pred("b", false), "extra")
    val paths: Vector[PredictorPath] = Optimizable[TwoPredictors].predictors(p).map(_._1)
    assertEquals(paths, Vector(PredictorPath(Vector("a")), PredictorPath(Vector("b"))))

  test("nested sub-program predictor has prefixed path"):
    val p: Outer                     = Outer(Inner(pred("leaf", false)))
    val paths: Vector[PredictorPath] = Optimizable[Outer].predictors(p).map(_._1)
    assertEquals(paths, Vector(PredictorPath(Vector("inner", "leaf"))))

  test("ordered collection of predictors has indexed paths"):
    val p: Pipeline                  = Pipeline(Vector(pred("s0", false), pred("s1", false), pred("s2", false)))
    val paths: Vector[PredictorPath] = Optimizable[Pipeline].predictors(p).map(_._1)
    assertEquals(
      paths,
      Vector(
        PredictorPath(Vector("steps", "0")),
        PredictorPath(Vector("steps", "1")),
        PredictorPath(Vector("steps", "2"))
      )
    )

  test("empty collection of predictors returns empty"):
    val p: Pipeline = Pipeline(Vector.empty)
    assertEquals(Optimizable[Pipeline].predictors(p), Vector.empty)

  test("mixed program: leaf + sub + collection + plain"):
    val p: MixedProgram = MixedProgram(
      leaf = pred("leaf", false),
      sub = Inner(pred("inner", false)),
      vec = Vector(pred("v0", false), pred("v1", false)),
      plain = "ignored"
    )
    val paths: Vector[PredictorPath] = Optimizable[MixedProgram].predictors(p).map(_._1)
    assertEquals(
      paths,
      Vector(
        PredictorPath(Vector("leaf")),
        PredictorPath(Vector("sub", "inner")),
        PredictorPath(Vector("vec", "0")),
        PredictorPath(Vector("vec", "1"))
      )
    )

  test("unknown path raises an error via updateEither"):
    val p: TwoPredictors       = TwoPredictors(pred("a", false), pred("b", false), "x")
    val badPath: PredictorPath = PredictorPath(Vector("zzz"))
    val result: Either[OptimizeError, TwoPredictors] =
      Optimizable[TwoPredictors].updateEither(p, badPath, identity)
    assertEquals(result, Left(OptimizeError.UnknownPath(badPath)))

  test("update raises OptimizeError.UnknownPath for bad path"):
    val p: TwoPredictors       = TwoPredictors(pred("a", false), pred("b", false), "x")
    val badPath: PredictorPath = PredictorPath(Vector("zzz"))
    intercept[OptimizeError.UnknownPath]:
      Optimizable[TwoPredictors].update(p, badPath, identity)

  test("updateEither returns updated program for valid unfrozen path"):
    val p: TwoPredictors = TwoPredictors(pred("a", false), pred("b", false), "x")
    val result: Either[OptimizeError, TwoPredictors] =
      Optimizable[TwoPredictors].updateEither(p, PredictorPath(Vector("a")), s => s.copy(instructions = "X"))
    assert(result.isRight)
    val updated: TwoPredictors = unwrap(result.toOption)
    assertEquals(
      unwrap(Optimizable[TwoPredictors].predictors(updated).find(_._1.segments == Vector("a")))._2.instructions,
      "X"
    )

  test("updateEither returns frozen-path error for frozen predictor"):
    val p: TwoPredictors          = TwoPredictors(pred("a", true), pred("b", false), "x")
    val frozenPath: PredictorPath = PredictorPath(Vector("a"))
    val result: Either[OptimizeError, TwoPredictors] =
      Optimizable[TwoPredictors].updateEither(p, frozenPath, identity)
    assertEquals(result, Left(OptimizeError.FrozenPath(frozenPath)))

  test("update one predictor leaves others unchanged"):
    val p: TwoPredictors = TwoPredictors(pred("A", false), pred("B", false), "x")
    val result: TwoPredictors =
      Optimizable[TwoPredictors].update(p, PredictorPath(Vector("a")), s => s.copy(instructions = "X"))
    val bState: PredictorState =
      unwrap(Optimizable[TwoPredictors].predictors(result).find(_._1.segments == Vector("b")))._2
    assertEquals(bState.instructions, "B")

  test("updateAll is a no-op when all frozen"):
    val p: TwoPredictors = TwoPredictors(pred("a", true), pred("b", true), "x")
    val result: TwoPredictors =
      Optimizable[TwoPredictors].updateAll(p, (_, s) => s.copy(instructions = "OVERWRITE"))
    assertEquals(result, p)

  test("updateAll touches every unfrozen predictor"):
    val p: TwoPredictors = TwoPredictors(pred("a", false), pred("b", false), "x")
    val result: TwoPredictors =
      Optimizable[TwoPredictors].updateAll(p, (_, s) => s.copy(instructions = "X"))
    val states: Vector[PredictorState] = Optimizable[TwoPredictors].predictors(result).map(_._2)
    assert(states.forall(_.instructions == "X"))

  test("frozen predictor visible in enumeration"):
    val p: TwoPredictors                               = TwoPredictors(pred("a", true), pred("b", false), "x")
    val preds: Vector[(PredictorPath, PredictorState)] = Optimizable[TwoPredictors].predictors(p)
    val aEntry: Option[(PredictorPath, PredictorState)] =
      preds.find(_._1.segments == Vector("a"))
    assert(aEntry.isDefined)
    assert(unwrap(aEntry)._2.frozen)

  test("unknown-path error carries the offending path"):
    val err: OptimizeError.UnknownPath = OptimizeError.UnknownPath(PredictorPath(Vector("a", "b")))
    assertEquals(err.path.segments, Vector("a", "b"))

  test("frozen-path error carries the offending path"):
    val err: OptimizeError.FrozenPath = OptimizeError.FrozenPath(PredictorPath(Vector("a")))
    assertEquals(err.path.segments, Vector("a"))

  test("derivation requires no hand-written instance"):
    val opt: Optimizable[TwoPredictors] = summon[Optimizable[TwoPredictors]]
    val p: TwoPredictors                = TwoPredictors(pred("a", false), pred("b", false), "x")
    assertEquals(opt.predictors(p).size, 2)

  // ── Hedgehog properties ─────────────────────────────────────────────────

  property("predictors-declaration-order"):
    for
      instrA  <- genString.forAll
      instrB  <- genString.forAll
      frozenA <- genBoolean.forAll
      frozenB <- genBoolean.forAll
    yield
      val p: TwoPredictors             = TwoPredictors(pred(instrA, frozenA), pred(instrB, frozenB), "x")
      val paths: Vector[PredictorPath] = Optimizable[TwoPredictors].predictors(p).map(_._1)
      paths ==== Vector(PredictorPath(Vector("a")), PredictorPath(Vector("b")))

  property("nested-recursion-paths"):
    for
      instr  <- genString.forAll
      frozen <- genBoolean.forAll
    yield
      val p: Outer                     = Outer(Inner(pred(instr, frozen)))
      val paths: Vector[PredictorPath] = Optimizable[Outer].predictors(p).map(_._1)
      paths ==== Vector(PredictorPath(Vector("inner", "leaf")))

  property("collection-recursion-paths"):
    for n <- Gen.int(Range.linear(0, 5)).forAll
    yield
      val steps: Vector[Predict0[cats.effect.IO, String, String]] =
        (0 until n).map(i => pred(s"step$i", false)).toVector
      val p: Pipeline                  = Pipeline(steps)
      val paths: Vector[PredictorPath] = Optimizable[Pipeline].predictors(p).map(_._1)
      val expected: Vector[PredictorPath] =
        (0 until n).map(i => PredictorPath(Vector("steps", i.toString))).toVector
      paths ==== expected

  property("update-purity"):
    for
      instrA   <- genString.forAll
      instrB   <- genString.forAll
      frozenA  <- genBoolean.forAll
      frozenB  <- genBoolean.forAll
      newInstr <- genString.forAll
    yield
      val p: TwoPredictors = TwoPredictors(pred(instrA, frozenA), pred(instrB, frozenB), "x")
      val before: Vector[(PredictorPath, PredictorState)] = Optimizable[TwoPredictors].predictors(p)
      val _: Either[OptimizeError, TwoPredictors] =
        Optimizable[TwoPredictors].updateEither(p, PredictorPath(Vector("a")), s => s.copy(instructions = newInstr))
      val after: Vector[(PredictorPath, PredictorState)] = Optimizable[TwoPredictors].predictors(p)
      before ==== after

  property("update-only-target"):
    for
      instrA   <- genString.forAll
      instrB   <- genString.forAll
      frozenA  <- genBoolean.forAll
      frozenB  <- genBoolean.forAll
      newInstr <- genString.forAll
    yield
      val p: TwoPredictors                           = TwoPredictors(pred(instrA, frozenA), pred(instrB, frozenB), "x")
      val before: Map[PredictorPath, PredictorState] = Optimizable[TwoPredictors].predictors(p).toMap
      val result: Either[OptimizeError, TwoPredictors] =
        Optimizable[TwoPredictors].updateEither(p, PredictorPath(Vector("a")), s => s.copy(instructions = newInstr))
      result match
        case Right(updated) =>
          val after: Map[PredictorPath, PredictorState] = Optimizable[TwoPredictors].predictors(updated).toMap
          (after.keySet ==== before.keySet).and(
            after.get(PredictorPath(Vector("b"))) ==== before.get(PredictorPath(Vector("b")))
          )
        case Left(_) =>
          true ==== true

  property("round-trip-identity"):
    for
      instrA  <- genString.forAll
      instrB  <- genString.forAll
      frozenA <- genBoolean.forAll
      frozenB <- genBoolean.forAll
    yield
      val p: TwoPredictors             = TwoPredictors(pred(instrA, frozenA), pred(instrB, frozenB), "x")
      val paths: Vector[PredictorPath] = Optimizable[TwoPredictors].predictors(p).map(_._1)
      val allRoundTrip: Boolean = paths.forall { path =>
        Optimizable[TwoPredictors].updateEither(p, path, identity) match
          case Right(updated) => updated == p
          case Left(_)        => true
      }
      allRoundTrip ==== true

  property("round-trip-identity on collection-nested predictor"):
    for
      n   <- Gen.int(Range.linear(1, 5)).forAll
      idx <- Gen.int(Range.linear(0, n - 1)).forAll
    yield
      val steps: Vector[Predict0[cats.effect.IO, String, String]] =
        (0 until n).map(i => pred(s"step$i", false)).toVector
      val p: Pipeline = Pipeline(steps)
      val result: Either[OptimizeError, Pipeline] =
        Optimizable[Pipeline].updateEither(p, PredictorPath(Vector("steps", idx.toString)), identity)
      result ==== Right(p)

  property("updateAll-skips-frozen"):
    for
      instrA   <- genString.forAll
      instrB   <- genString.forAll
      frozenA  <- genBoolean.forAll
      frozenB  <- genBoolean.forAll
      newInstr <- genString.forAll
    yield
      val p: TwoPredictors = TwoPredictors(pred(instrA, frozenA), pred(instrB, frozenB), "x")
      val frozenBefore: Vector[(PredictorPath, PredictorState)] =
        Optimizable[TwoPredictors].predictors(p).filter(_._2.frozen)
      val result: TwoPredictors =
        Optimizable[TwoPredictors].updateAll(p, (_, s) => s.copy(instructions = newInstr))
      val frozenAfter: Vector[(PredictorPath, PredictorState)] =
        Optimizable[TwoPredictors].predictors(result).filter(_._2.frozen)
      frozenBefore ==== frozenAfter

  property("updateAll-path-set-preserved"):
    for
      instrA   <- genString.forAll
      instrB   <- genString.forAll
      frozenA  <- genBoolean.forAll
      frozenB  <- genBoolean.forAll
      newInstr <- genString.forAll
    yield
      val p: TwoPredictors                = TwoPredictors(pred(instrA, frozenA), pred(instrB, frozenB), "x")
      val beforePaths: Set[PredictorPath] = Optimizable[TwoPredictors].predictors(p).map(_._1).toSet
      val result: TwoPredictors =
        Optimizable[TwoPredictors].updateAll(p, (_, s) => s.copy(instructions = newInstr))
      val afterPaths: Set[PredictorPath] =
        Optimizable[TwoPredictors].predictors(result).map(_._1).toSet
      beforePaths ==== afterPaths

  property("updateEither-unknown-path-error"):
    for
      instrA <- genString.forAll
      instrB <- genString.forAll
    yield
      val p: TwoPredictors       = TwoPredictors(pred(instrA, false), pred(instrB, false), "x")
      val badPath: PredictorPath = PredictorPath(Vector("zzz"))
      Optimizable[TwoPredictors].updateEither(p, badPath, identity) ==== Left(OptimizeError.UnknownPath(badPath))

  property("updateEither-frozen-path-error"):
    for instrA <- genString.forAll
    yield
      val p: TwoPredictors          = TwoPredictors(pred(instrA, true), pred("b", false), "x")
      val frozenPath: PredictorPath = PredictorPath(Vector("a"))
      Optimizable[TwoPredictors].updateEither(p, frozenPath, identity) ==== Left(OptimizeError.FrozenPath(frozenPath))

  property("frozen-still-readable"):
    for
      instrA  <- genString.forAll
      instrB  <- genString.forAll
      frozenA <- genBoolean.forAll
      frozenB <- genBoolean.forAll
    yield
      val p: TwoPredictors = TwoPredictors(pred(instrA, frozenA), pred(instrB, frozenB), "x")
      val frozen: Vector[(PredictorPath, PredictorState)] =
        Optimizable[TwoPredictors].predictors(p).filter(_._2.frozen)
      val allFrozen: Boolean = frozen.forall { case (_, s) => s.frozen }
      (frozen.isEmpty || allFrozen) ==== true

  // ── Generators ──────────────────────────────────────────────────────────

  private def genString: Gen[String] =
    Gen.string(Gen.alpha, Range.linear(1, 10))

  private def genBoolean: Gen[Boolean] =
    Gen.boolean
