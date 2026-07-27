package org.adk4s.optimize

import org.adk4s.structured.core.Prompt
import org.adk4s.structured.core.PromptTemplate
import org.adk4s.structured.core.Schema
import org.adk4s.structured.core.StructuredLLM
import smithy4s.schema.Schema as Smithy4sSchema

/**
 * Test fixtures for the optimizable-surface spec.
 *
 * These toy programs exercise the three derivation shapes:
 *  - `TwoPredictors` — two leaf predictor fields + a plain field (LeafDerivation)
 *  - `Outer` / `Inner` — a nested sub-program (SubtreeDerivation)
 *  - `Pipeline` — an ordered collection of predictors (CollectionDerivation)
 */
object ToyPrograms:

  // ── Dummy StructuredLLM (never invoked in Phase 0) ──────────────────────

  /**
   * A dummy `StructuredLLM[IO]` whose methods throw `NotImplementedError`.
   * Phase 0 carries the capability but never invokes it.
   */
  val dummyStructuredLLM: StructuredLLM[cats.effect.IO] = new StructuredLLM[cats.effect.IO]:
    def complete[A: Schema](prompt: Prompt): cats.effect.IO[A]    = ???
    def completeRaw[A: Schema](prompt: Prompt): cats.effect.IO[A] = ???
    def streamWithResult[A: Schema](
      prompt: Prompt
    ): cats.effect.IO[(fs2.Stream[cats.effect.IO, String], cats.effect.IO[A])] = ???
    def streamWithResultRaw[A: Schema](
      prompt: Prompt
    ): cats.effect.IO[(fs2.Stream[cats.effect.IO, String], cats.effect.IO[A])] = ???
    def completeValidated[A: Schema](prompt: Prompt): cats.effect.IO[org.adk4s.structured.core.ValidationResult[A]] =
      ???
    def streamPartial[A: Schema](prompt: Prompt): fs2.Stream[cats.effect.IO, org.adk4s.structured.sap.StreamState[A]] =
      ???
    def function[I, A: Schema](template: PromptTemplate[I]): I => cats.effect.IO[A] = ???
    def extractor[A: Schema](systemPrompt: String): String => cats.effect.IO[A]     = ???

  // ── Dummy Schema[String] ────────────────────────────────────────────────

  given Smithy4sSchema[String] = Smithy4sSchema.string

  val stringSchema: Schema[String] = Schema.instance(
    "structure S { @required value: String }"
  )

  // ── Dummy PromptTemplate[String] ────────────────────────────────────────

  val dummyTemplate: PromptTemplate[String] = PromptTemplate.userMessage

  // ── Predictor builder ───────────────────────────────────────────────────

  /**
   * Build a `Predict0` leaf predictor with the given instructions and frozen flag,
   * empty demos, and dummy capability fields.
   */
  def pred(instructions: String, frozen: Boolean): Predict0[cats.effect.IO, String, String] =
    Predict0(
      state = PredictorState(instructions, Vector.empty, frozen),
      template = dummyTemplate,
      schema = stringSchema,
      structured = dummyStructuredLLM
    )

  /** Build a `Predict0` leaf predictor with the given state. */
  def predWithState(state: PredictorState): Predict0[cats.effect.IO, String, String] =
    Predict0(
      state = state,
      template = dummyTemplate,
      schema = stringSchema,
      structured = dummyStructuredLLM
    )

  // ── Toy programs ────────────────────────────────────────────────────────

  /** Two predictor fields `a` and `b` + a plain field `extra`. */
  final case class TwoPredictors(
    a: Predict0[cats.effect.IO, String, String],
    b: Predict0[cats.effect.IO, String, String],
    extra: String
  )
  object TwoPredictors:
    given Optimizable[TwoPredictors] = Optimizable.derived[TwoPredictors]

  /** Inner sub-program with one predictor field `leaf`. */
  final case class Inner(
    leaf: Predict0[cats.effect.IO, String, String]
  )
  object Inner:
    given Optimizable[Inner] = Optimizable.derived[Inner]

  /** Outer program with a sub-program field `inner`. */
  final case class Outer(
    inner: Inner
  )
  object Outer:
    given Optimizable[Outer] = Optimizable.derived[Outer]

  /** Pipeline with an ordered collection of predictors `steps`. */
  final case class Pipeline(
    steps: Vector[Predict0[cats.effect.IO, String, String]]
  )
  object Pipeline:
    given Optimizable[Pipeline] = Optimizable.derived[Pipeline]

  /**
   * Mixed program: leaf + sub-program + collection + plain field.
   * Used by the "Derivation for a mixed program" scenario.
   */
  final case class MixedProgram(
    leaf: Predict0[cats.effect.IO, String, String],
    sub: Inner,
    vec: Vector[Predict0[cats.effect.IO, String, String]],
    plain: String
  )
  object MixedProgram:
    given Optimizable[MixedProgram] = Optimizable.derived[MixedProgram]
