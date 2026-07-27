package org.adk4s.optimize

import hedgehog._
import hedgehog.munit.HedgehogSuite
import org.adk4s.verified.PredictorKernel
import org.adk4s.verified.PredictorKernel._
import stainless.collection.{ List as SList, Cons as SCons, Nil as SNil }

/**
 * Ring 6 bridge spec — runs the real `Optimizable` and the `PredictorKernel`
 * model on the SAME generated programs and asserts they agree on the proven
 * invariants:
 *
 *  1. The real `predictors` path sequence, mapped to index chains, equals
 *     `PredictorKernel.paths` element-for-element (order included).
 *  2. After `updateAll`, the real path set equals the model's.
 *  3. The real frozen predictors are bit-identical exactly where the model's
 *     are.
 *
 * The bridge maps real `PredictorPath` segments (field names + index strings)
 * to model index chains (`SList[BigInt]`) by looking up each field name in the
 * product's declaration order.
 */
class PredictorModelBridgeSpec extends HedgehogSuite:

  // ── Scala-List ↔ Stainless-List conversion ──────────────────────────────

  /** Convert a Scala `List` to a Stainless `SList`. */
  private def toSList[A](xs: List[A]): SList[A] =
    xs match
      case Nil    => SNil[A]()
      case h :: t => SCons(h, toSList(t))

  /** Convert a Stainless `SList` to a Scala `List`. */
  private def fromSList[A](xs: SList[A]): List[A] =
    xs match
      case SNil()      => Nil
      case SCons(h, t) => h :: fromSList(t)

  // ── Model-to-real program builders ──────────────────────────────────────

  /** Build a model `Pred` and a real `Predict0` with the same frozen flag. */
  private def buildPred(
    frozen: Boolean,
    seed: Int
  ): (Pred, Predict0[cats.effect.IO, String, String]) =
    val modelPred: Pred = Pred(frozen, BigInt(seed))
    val realPred: Predict0[cats.effect.IO, String, String] =
      ToyPrograms.pred(s"instr-$seed", frozen)
    (modelPred, realPred)

  // ── Bridge invariant 1: path sequence equality ──────────────────────────

  property("bridge-paths-match: TwoPredictors"):
    for
      seedA   <- Gen.int(Range.linear(0, 1000)).forAll
      seedB   <- Gen.int(Range.linear(0, 1000)).forAll
      frozenA <- Gen.boolean.forAll
      frozenB <- Gen.boolean.forAll
    yield
      val (modelA, realA) = buildPred(frozenA, seedA)
      val (modelB, realB) = buildPred(frozenB, seedB)
      val modelProg: Prog = Sub(toSList(List(modelA, modelB, Plain())))
      val realProg: ToyPrograms.TwoPredictors =
        ToyPrograms.TwoPredictors(realA, realB, "extra")

      val modelPaths: SList[SList[BigInt]] = PredictorKernel.paths(modelProg, SNil())
      val realPaths: Vector[PredictorPath] =
        Optimizable[ToyPrograms.TwoPredictors].predictors(realProg).map(_._1)

      // TwoPredictors fields: a (0), b (1), extra (2)
      // Real paths: "a", "b" → model: 0, 1
      val realIndexChains: List[List[BigInt]] =
        realPaths.toList.map { p =>
          p.segments.toList.map {
            case "a"     => BigInt(0)
            case "b"     => BigInt(1)
            case "extra" => BigInt(2)
            case idx     => BigInt(idx.toIntOption.getOrElse(-1))
          }
        }
      val modelIndexChains: List[List[BigInt]] =
        fromSList(modelPaths.map(fromSList))

      realIndexChains ==== modelIndexChains

  property("bridge-paths-match: Outer/Inner nested"):
    for
      seed   <- Gen.int(Range.linear(0, 1000)).forAll
      frozen <- Gen.boolean.forAll
    yield
      val (modelPred, realPred)       = buildPred(frozen, seed)
      val modelProg: Prog             = Sub(toSList(List(Sub(toSList(List(modelPred))))))
      val realProg: ToyPrograms.Outer = ToyPrograms.Outer(ToyPrograms.Inner(realPred))

      val modelPaths: SList[SList[BigInt]] = PredictorKernel.paths(modelProg, SNil())
      val realPaths: Vector[PredictorPath] =
        Optimizable[ToyPrograms.Outer].predictors(realProg).map(_._1)

      // Outer.inner (0) → Inner.leaf (0) → model: List(0, 0)
      val realIndexChains: List[List[BigInt]] =
        realPaths.toList.map { p =>
          p.segments.toList.map {
            case "inner" => BigInt(0)
            case "leaf"  => BigInt(0)
            case idx     => BigInt(idx.toIntOption.getOrElse(-1))
          }
        }
      val modelIndexChains: List[List[BigInt]] =
        fromSList(modelPaths.map(fromSList))

      realIndexChains ==== modelIndexChains

  property("bridge-paths-match: Pipeline collection"):
    for
      n       <- Gen.int(Range.linear(1, 5)).forAll
      seeds   <- Gen.int(Range.linear(0, 1000)).list(Range.linear(1, 5)).forAll
      frozens <- Gen.boolean.list(Range.linear(1, 5)).forAll
    yield
      val count: Int = math.min(n, math.min(seeds.length, frozens.length))
      val modelElems: SList[Prog] =
        toSList((0 until count).map(i => Pred(frozens(i), BigInt(seeds(i)))).toList)
      val modelProg: Prog = Sub(toSList(List(Coll(modelElems))))

      val realSteps: Vector[Predict0[cats.effect.IO, String, String]] =
        (0 until count).map(i => ToyPrograms.pred(s"step-${seeds(i)}", frozens(i))).toVector
      val realProg: ToyPrograms.Pipeline = ToyPrograms.Pipeline(realSteps)

      val modelPaths: SList[SList[BigInt]] = PredictorKernel.paths(modelProg, SNil())
      val realPaths: Vector[PredictorPath] =
        Optimizable[ToyPrograms.Pipeline].predictors(realProg).map(_._1)

      // Pipeline.steps (0) → collection indices
      val realIndexChains: List[List[BigInt]] =
        realPaths.toList.map { p =>
          p.segments.toList.map {
            case "steps" => BigInt(0)
            case idx     => BigInt(idx.toIntOption.getOrElse(-1))
          }
        }
      val modelIndexChains: List[List[BigInt]] =
        fromSList(modelPaths.map(fromSList))

      realIndexChains ==== modelIndexChains

  // ── Bridge invariant 2: path set preserved after updateAll ──────────────

  property("bridge-updateAll-paths-preserved: TwoPredictors"):
    for
      seedA   <- Gen.int(Range.linear(0, 1000)).forAll
      seedB   <- Gen.int(Range.linear(0, 1000)).forAll
      frozenA <- Gen.boolean.forAll
      frozenB <- Gen.boolean.forAll
    yield
      val (modelA, realA) = buildPred(frozenA, seedA)
      val (modelB, realB) = buildPred(frozenB, seedB)
      val modelProg: Prog = Sub(toSList(List(modelA, modelB, Plain())))
      val realProg: ToyPrograms.TwoPredictors =
        ToyPrograms.TwoPredictors(realA, realB, "extra")

      val f: BigInt => BigInt = x => x + BigInt(1)
      val updatedModel: Prog  = PredictorKernel.updateAll(modelProg, f)
      val updatedReal: ToyPrograms.TwoPredictors =
        Optimizable[ToyPrograms.TwoPredictors].updateAll(
          realProg,
          (_, s) => s.copy(instructions = s.instructions + "!")
        )

      val modelPathsAfter: List[List[BigInt]] =
        fromSList(PredictorKernel.paths(updatedModel, SNil()).map(fromSList))
      val modelPathsBefore: List[List[BigInt]] =
        fromSList(PredictorKernel.paths(modelProg, SNil()).map(fromSList))

      val realPathsBefore: Vector[PredictorPath] =
        Optimizable[ToyPrograms.TwoPredictors].predictors(realProg).map(_._1)
      val realPathsAfter: Vector[PredictorPath] =
        Optimizable[ToyPrograms.TwoPredictors].predictors(updatedReal).map(_._1)

      (modelPathsAfter ==== modelPathsBefore).and(
        realPathsAfter ==== realPathsBefore
      )

  // ── Bridge invariant 3: frozen predictors bit-identical ─────────────────

  property("bridge-frozen-preserved: TwoPredictors"):
    for
      seedA   <- Gen.int(Range.linear(0, 1000)).forAll
      seedB   <- Gen.int(Range.linear(0, 1000)).forAll
      frozenA <- Gen.boolean.forAll
      frozenB <- Gen.boolean.forAll
    yield
      val (modelA, realA) = buildPred(frozenA, seedA)
      val (modelB, realB) = buildPred(frozenB, seedB)
      val modelProg: Prog = Sub(toSList(List(modelA, modelB, Plain())))
      val realProg: ToyPrograms.TwoPredictors =
        ToyPrograms.TwoPredictors(realA, realB, "extra")

      val f: BigInt => BigInt = x => x + BigInt(1)
      val updatedModel: Prog  = PredictorKernel.updateAll(modelProg, f)
      val updatedReal: ToyPrograms.TwoPredictors =
        Optimizable[ToyPrograms.TwoPredictors].updateAll(
          realProg,
          (_, s) => s.copy(instructions = s.instructions + "!")
        )

      val modelFrozenBefore: List[BigInt] =
        fromSList(PredictorKernel.frozenStates(modelProg))
      val modelFrozenAfter: List[BigInt] =
        fromSList(PredictorKernel.frozenStates(updatedModel))

      val realFrozenBefore: Vector[PredictorState] =
        Optimizable[ToyPrograms.TwoPredictors]
          .predictors(realProg)
          .filter(_._2.frozen)
          .map(_._2)
      val realFrozenAfter: Vector[PredictorState] =
        Optimizable[ToyPrograms.TwoPredictors]
          .predictors(updatedReal)
          .filter(_._2.frozen)
          .map(_._2)

      (modelFrozenAfter ==== modelFrozenBefore).and(
        realFrozenAfter ==== realFrozenBefore
      )

  // ── Bridge invariant: round-trip identity ───────────────────────────────

  property("bridge-round-trip-identity: TwoPredictors"):
    for
      seedA   <- Gen.int(Range.linear(0, 1000)).forAll
      seedB   <- Gen.int(Range.linear(0, 1000)).forAll
      frozenA <- Gen.boolean.forAll
      frozenB <- Gen.boolean.forAll
    yield
      val (modelA, realA) = buildPred(frozenA, seedA)
      val (modelB, realB) = buildPred(frozenB, seedB)
      val modelProg: Prog = Sub(toSList(List(modelA, modelB, Plain())))
      val realProg: ToyPrograms.TwoPredictors =
        ToyPrograms.TwoPredictors(realA, realB, "extra")

      val modelRoundTrip: Prog = PredictorKernel.updateAll(modelProg, x => x)
      val realRoundTrip: ToyPrograms.TwoPredictors =
        Optimizable[ToyPrograms.TwoPredictors].updateAll(realProg, (_, s) => s)

      val modelEqual: Boolean = modelRoundTrip == modelProg
      val realEqual: Boolean  = realRoundTrip == realProg

      (modelEqual ==== true).and(realEqual ==== true)
