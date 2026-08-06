package org.adk4s.optimize

import munit.FunSuite
import smithy4s.Document

/**
 * Scenario tests for predictor-state, predictor-path, and the placeholder
 * predictor (Predict0).
 *
 * Covers the Proof Obligations:
 *  - Predictor-state exposes instructions, demos and frozen flag as read data
 *  - Default predictor-state is empty instructions, no demos, not frozen
 *  - A frozen predictor-state is constructible (freezing is data, not a type)
 *  - Leaf-predictor capability state/withState round-trip
 *  - Placeholder predictor carries state but does not render demos
 *  - Placeholder state is serializable-ready
 */
class Predict0Spec extends FunSuite:

  test("predictor-state exposes instructions, demos, frozen as read data"):
    val demo: Demo            = Demo(Document.DString("q"), Document.DString("a"))
    val state: PredictorState = PredictorState("Answer the question", Vector(demo), false)
    assertEquals(state.instructions, "Answer the question")
    assertEquals(state.demos.size, 1)
    assertEquals(state.frozen, false)

  test("default predictor-state is empty instructions, no demos, not frozen"):
    val state: PredictorState = PredictorState("", Vector.empty, false)
    assertEquals(state.instructions, "")
    assertEquals(state.demos, Vector.empty)
    assertEquals(state.frozen, false)

  test("frozen predictor-state is constructible (freezing is data)"):
    val state: PredictorState = PredictorState("instr", Vector.empty, true)
    assertEquals(state.frozen, true)

  test("leaf-predictor capability reads state from Predict0"):
    val predictor: Predict0[cats.effect.IO, String, String] = ToyPrograms.pred("instr", false)
    val leaf: HasPredictorState[Predict0[cats.effect.IO, String, String]] =
      summon[HasPredictorState[Predict0[cats.effect.IO, String, String]]]
    val state: PredictorState = leaf.state(predictor)
    assertEquals(state.instructions, "instr")
    assertEquals(state.demos, Vector.empty)
    assertEquals(state.frozen, false)

  test("leaf-predictor capability withState produces a new predictor"):
    val predictor: Predict0[cats.effect.IO, String, String] = ToyPrograms.pred("instr", false)
    val leaf: HasPredictorState[Predict0[cats.effect.IO, String, String]] =
      summon[HasPredictorState[Predict0[cats.effect.IO, String, String]]]
    val s1: PredictorState                                = leaf.state(predictor)
    val s2: PredictorState                                = PredictorState("new instructions", Vector.empty, true)
    val updated: Predict0[cats.effect.IO, String, String] = leaf.withState(predictor, s2)
    // The new predictor has the replaced state
    assertEquals(leaf.state(updated).instructions, "new instructions")
    assertEquals(leaf.state(updated).frozen, true)
    // The original predictor is unchanged
    assertEquals(leaf.state(predictor).instructions, "instr")
    assertEquals(leaf.state(predictor).frozen, false)

  test("placeholder predictor carries state but does not render demos"):
    val demo: Demo                                          = Demo(Document.DString("q"), Document.DString("a"))
    val state: PredictorState                               = PredictorState("instr", Vector(demo), false)
    val predictor: Predict0[cats.effect.IO, String, String] = ToyPrograms.predWithState(state)
    // The demo is present in the predictor-state
    assertEquals(predictor.state.demos.size, 1)
    // Phase 0: Predict0 exposes no run/render/complete method — there is no
    // code path that could inject a demo into a Prompt. (Structurally enforced:
    // the Predict0 case class has no such method.)

  test("placeholder state is serializable-ready (plain immutable values)"):
    val demo: Demo            = Demo(Document.DString("q"), Document.DString("a"))
    val state: PredictorState = PredictorState("instr", Vector(demo), true)
    // All fields are plain immutable values: String, Vector[Demo], Boolean,
    // smithy4s.Document — no closures, no effect types, no live references.
    val instructions: String = state.instructions
    val demos: Vector[Demo]  = state.demos
    val frozen: Boolean      = state.frozen
    assertEquals(instructions, "instr")
    assertEquals(demos.size, 1)
    assertEquals(frozen, true)
