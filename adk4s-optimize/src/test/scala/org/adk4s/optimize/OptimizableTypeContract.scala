package org.adk4s.optimize

import munit.FunSuite

/**
 * Typed contract for the optimizable-surface spec (Step 1 — HUMAN GATE 1).
 *
 * Verifies that every signature from design.md compiles with exact types:
 *  - `Demo` case class with `input: ujson.Value, output: ujson.Value`
 *  - `PredictorState` case class with `instructions: String, demos: Vector[Demo], frozen: Boolean`
 *  - `PredictorPath` case class with `segments: Vector[String]` and `def render: String`
 *  - `OptimizeError` enum with `UnknownPath(path)` and `FrozenPath(path)`
 *  - `Optimizable[P]` trait with `predictors`/`update`/`updateEither`/`updateAll`
 *  - `object Optimizable` with `inline def derived` and `def apply`
 *  - `HasPredictorState[Self]` trait with `state`/`withState`
 *  - `Predict0[F, I, O]` case class with `state`/`template`/`schema`/`structured`
 *  - `given HasPredictorState[Predict0[F, I, O]]`
 */
class OptimizableTypeContract extends FunSuite:

  test("Demo has exactly input: ujson.Value, output: ujson.Value"):
    val demo: Demo          = Demo(input = ujson.Str("in"), output = ujson.Str("out"))
    val input: ujson.Value  = demo.input
    val output: ujson.Value = demo.output
    assertEquals(input, ujson.Str("in"))
    assertEquals(output, ujson.Str("out"))

  test("PredictorState has exactly instructions, demos, frozen"):
    val state: PredictorState = PredictorState("instr", Vector.empty, false)
    val instructions: String  = state.instructions
    val demos: Vector[Demo]   = state.demos
    val frozen: Boolean       = state.frozen
    assertEquals(instructions, "instr")
    assertEquals(demos, Vector.empty)
    assertEquals(frozen, false)

  test("PredictorPath has segments and render"):
    val path: PredictorPath      = PredictorPath(Vector("outer", "inner"))
    val segments: Vector[String] = path.segments
    val rendered: String         = path.render
    assertEquals(segments, Vector("outer", "inner"))
    assertEquals(rendered, "outer.inner")

  test("OptimizeError has UnknownPath and FrozenPath variants"):
    val path: PredictorPath    = PredictorPath(Vector("a"))
    val unknown: OptimizeError = OptimizeError.UnknownPath(path)
    val frozen: OptimizeError  = OptimizeError.FrozenPath(path)
    // Exhaustive match — no catch-all (Ring 0 exhaustiveness escalation)
    val msg: String = unknown match
      case OptimizeError.UnknownPath(p) => s"unknown: ${p.render}"
      case OptimizeError.FrozenPath(p)  => s"frozen: ${p.render}"
    assertEquals(msg, "unknown: a")

  test("Optimizable trait has predictors/update/updateEither/updateAll"):
    val opt: Optimizable[ToyPrograms.TwoPredictors] = Optimizable[ToyPrograms.TwoPredictors]
    val p: ToyPrograms.TwoPredictors = ToyPrograms.TwoPredictors(
      ToyPrograms.pred("a", false),
      ToyPrograms.pred("b", false),
      "extra"
    )
    val preds: Vector[(PredictorPath, PredictorState)] = opt.predictors(p)
    val updated: ToyPrograms.TwoPredictors             = opt.update(p, PredictorPath(Vector("a")), s => s)
    val either: Either[OptimizeError, ToyPrograms.TwoPredictors] =
      opt.updateEither(p, PredictorPath(Vector("a")), s => s)
    val all: ToyPrograms.TwoPredictors = opt.updateAll(p, (_, s) => s)
    // Signatures compile — values are exercised in property tests
    assertEquals(preds.size, 2)
    assertEquals(updated, p)
    assertEquals(either, Right(p))
    assertEquals(all, p)

  test("HasPredictorState trait has state/withState"):
    val predictor: Predict0[cats.effect.IO, String, String] = ToyPrograms.pred("instr", false)
    val leaf: HasPredictorState[Predict0[cats.effect.IO, String, String]] =
      summon[HasPredictorState[Predict0[cats.effect.IO, String, String]]]
    val state: PredictorState                             = leaf.state(predictor)
    val newState: PredictorState                          = PredictorState("new", Vector.empty, false)
    val updated: Predict0[cats.effect.IO, String, String] = leaf.withState(predictor, newState)
    assertEquals(state.instructions, "instr")
    assertEquals(leaf.state(updated).instructions, "new")

  test("Predict0 has state/template/schema/structured fields"):
    val predictor: Predict0[cats.effect.IO, String, String] = ToyPrograms.pred("instr", false)
    val state: PredictorState                               = predictor.state
    assertEquals(state.instructions, "instr")

  test("Optimizable.derived produces an instance with no hand-written declaration"):
    // summoning works — the instance is derived via Mirror
    val opt: Optimizable[ToyPrograms.Outer] = summon[Optimizable[ToyPrograms.Outer]]
    // Exercise it to prove it's a real instance, not null
    val p: ToyPrograms.Outer = ToyPrograms.Outer(ToyPrograms.Inner(ToyPrograms.pred("x", false)))
    assertEquals(opt.predictors(p).map(_._1.render), Vector("inner.leaf"))

  test("OptimizeError exhaustiveness: both variants matched, no catch-all needed"):
    val e: OptimizeError = OptimizeError.UnknownPath(PredictorPath(Vector("a")))
    // An exhaustive match over both variants compiles without a catch-all.
    // The -Wconf:name=PatternMatchExhaustivity:e flag (in scala3Options)
    // escalates any non-exhaustive match to a compile error at the project
    // level — this is the Ring 0 enforcement. compileErrors (munit macro)
    // does not capture -Wconf escalations, so the compile-negative obligation
    // is enforced by `sbt adk4s-optimize/compile` failing on any non-exhaustive
    // match in production code, not by a compileErrors assertion here.
    val msg: String = e match
      case OptimizeError.UnknownPath(p) => s"unknown: ${p.render}"
      case OptimizeError.FrozenPath(p)  => s"frozen: ${p.render}"
    assertEquals(msg, "unknown: a")
    // Also verify FrozenPath is constructible and matchable
    val f: OptimizeError = OptimizeError.FrozenPath(PredictorPath(Vector("b")))
    val fmsg: String = f match
      case OptimizeError.UnknownPath(p) => s"unknown: ${p.render}"
      case OptimizeError.FrozenPath(p)  => s"frozen: ${p.render}"
    assertEquals(fmsg, "frozen: b")
