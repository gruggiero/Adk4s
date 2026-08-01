package org.adk4s.eval

import cats.effect.Async
import cats.syntax.all.*
import org.adk4s.structured.core.Constraint
import org.adk4s.structured.core.Prompt
import org.adk4s.structured.core.Schema
import org.adk4s.structured.core.StructuredLLM
import org.adk4s.structured.core.ValidationResult
import smithy4s.schema.Schema as Smithy4sSchema

// ═══════════════════════════════════════════════════════════════════════════
//  LLM Judges — SemanticF1 and CompleteAndGrounded judge metrics
//
//  spec: llm-judges — Two LLM-judge metrics backed by StructuredLLM programs
//  over hand-written Smithy schemas. The eval-vs-optimization toggle
//  (binarize on trace.isDefined) matches DSPy's "trace is None" idiom.
//
//  Judge schemas are hand-written Schema.instance definitions (NOT in
//  structured-llm-test-models, which is test-only codegen). Each schema has
//  a Smithy IDL string for prompt injection and a smithy4s schema for JSON
//  decoding. This compiles without the smithy4s-sbt-codegen plugin.
//
//  Prompt text source: DSPy's published SemanticRecallPrecision signature
//  (commit 2974a655, dspy/evaluate/auto_evaluation.py), ported as the
//  starting text. The CompleteAndGrounded prompt is written from scratch
//  following the same structure (DSPy's AnswerCompleteness / AnswerGroundedness
//  signatures as reference). Defaults are NOT battle-calibrated; conservative
//  threshold 0.66 is shipped.
// ═══════════════════════════════════════════════════════════════════════════

// ── Judge schemas (hand-written Schema.instance) ───────────────────────────

/**
 * The structured output of the SemanticF1 judge: precision and recall in
 * [0, 1] plus the judge's reasoning.
 *
 * @param precision fraction (out of 1.0) of system response covered by the ground truth
 * @param recall    fraction (out of 1.0) of ground truth covered by the system response
 * @param reasoning the judge's chain-of-thought reasoning
 */
final case class SemanticF1Judge(
  precision: Double,
  recall: Double,
  reasoning: String
)

object SemanticF1Judge:

  /** smithy4s schema for JSON decoding (derived without codegen plugin). */
  given s4sSchema: Smithy4sSchema[SemanticF1Judge] = smithy4s.Schema.recursive {
    import smithy4s.Schema
    Schema.struct(
      Schema.double.required[SemanticF1Judge]("precision", _.precision),
      Schema.double.required[SemanticF1Judge]("recall", _.recall),
      Schema.string.required[SemanticF1Judge]("reasoning", _.reasoning)
    )(SemanticF1Judge.apply)
  }

  /** structured-llm Schema bridging Smithy IDL (prompt injection) + smithy4s (decoding). */
  given schema: Schema[SemanticF1Judge] = Schema.instance(
    """structure SemanticF1Judge {
      |  @required precision: Double
      |  @required recall: Double
      |  @required reasoning: String
      |}""".stripMargin
  )(using s4sSchema)

/**
 * The structured output of the CompleteAndGrounded judge: completeness and
 * groundedness in [0, 1] plus the judge's reasoning.
 *
 * @param completeness  fraction (out of 1.0) of ground truth covered by the system response
 * @param groundedness  fraction (out of 1.0) of system response supported by the ground truth
 * @param reasoning     the judge's chain-of-thought reasoning
 */
final case class CompleteAndGroundedJudge(
  completeness: Double,
  groundedness: Double,
  reasoning: String
)

object CompleteAndGroundedJudge:

  /** smithy4s schema for JSON decoding (derived without codegen plugin). */
  given s4sSchema: Smithy4sSchema[CompleteAndGroundedJudge] = smithy4s.Schema.recursive {
    import smithy4s.Schema
    Schema.struct(
      Schema.double.required[CompleteAndGroundedJudge]("completeness", _.completeness),
      Schema.double.required[CompleteAndGroundedJudge]("groundedness", _.groundedness),
      Schema.string.required[CompleteAndGroundedJudge]("reasoning", _.reasoning)
    )(CompleteAndGroundedJudge.apply)
  }

  /** structured-llm Schema bridging Smithy IDL (prompt injection) + smithy4s (decoding). */
  given schema: Schema[CompleteAndGroundedJudge] = Schema.instance(
    """structure CompleteAndGroundedJudge {
      |  @required completeness: Double
      |  @required groundedness: Double
      |  @required reasoning: String
      |}""".stripMargin
  )(using s4sSchema)

// ── Judges object — factory methods returning Metric instances ─────────────

/**
 * LLM-judge metric factories.
 *
 * `semanticF1` returns a `Metric` that obtains precision/recall from a
 * structured judge completion and returns the F1 as `Score.value` (eval mode)
 * or the binarized pass/fail (optimization mode). `completeAndGrounded`
 * returns the average of completeness/groundedness (eval mode) or the
 * binarized score (optimization mode).
 *
 * The eval-vs-optimization toggle is driven by `trace.isDefined`:
 *  - `trace = None`  → evaluation mode: raw score + reasoning as feedback
 *  - `trace = Some`  → optimization mode: binarized `Score(1.0)` if score >=
 *    threshold, `Score(0.0)` otherwise, no feedback
 *
 * Out-of-range precision/recall/completeness/groundedness are clamped to
 * [0, 1] via `Constraint.check` and flagged in the feedback string. A judge
 * parse failure (after structured-llm retries) propagates as a raise — the
 * harness catches it as a metric failure (`EvalOutcome.Failed` +
 * `Score(failureScore)`), never crashing the run.
 */
object Judges:

  /** Default binarization threshold (conservative — not battle-calibrated). */
  val defaultThreshold: Double = 0.66

  // ── Prompt text (ported from DSPy commit 2974a655) ───────────────────────

  /**
   * System prompt for the SemanticF1 judge — ported from DSPy's
   * `SemanticRecallPrecision` signature docstring (commit 2974a655,
   * dspy/evaluate/auto_evaluation.py).
   */
  private val semanticF1System: String =
    """Compare a system's response to the ground truth to compute its recall and precision.
      |If asked to reason, enumerate key ideas in each response, and whether they are present in the other response.""".stripMargin

  /**
   * System prompt for the CompleteAndGrounded judge — written from scratch
   * following the structure of DSPy's `AnswerCompleteness` and
   * `AnswerGroundedness` signatures (commit 2974a655). Asks for both
   * completeness and groundedness in a single structured completion.
   */
  private val completeAndGroundedSystem: String =
    """Estimate the completeness and groundedness of a system's response against the ground truth.
      |Completeness is the fraction of the ground truth covered by the system response.
      |Groundedness is the fraction of the system response supported by the ground truth.
      |Enumerate key ideas in each response, discuss their overlap, and then report completeness and groundedness.""".stripMargin

  /** Build the user message carrying the question, ground truth, and system response. */
  private def judgeUserMessage(question: String, groundTruth: String, systemResponse: String): String =
    s"""Question: $question
       |Ground truth: $groundTruth
       |System response: $systemResponse""".stripMargin

  // ── Clamping helpers (use Constraint.check per R1.13) ────────────────────

  /**
   * Range constraints for a two-field judge (e.g. precision/recall or
   * completeness/groundedness). Each field is checked to be in [0, 1] via
   * `Constraint.check` — a non-failing check that records whether the value
   * was in range. The check results drive the clamp-note feedback.
   */
  private def rangeConstraints[A](
    firstLabel: String,
    firstGet: A => Double,
    secondLabel: String,
    secondGet: A => Double
  ): Vector[Constraint[A]] =
    Vector(
      Constraint.check[A](s"$firstLabel in [0,1]")(v => firstGet(v) >= 0.0 && firstGet(v) <= 1.0),
      Constraint.check[A](s"$secondLabel in [0,1]")(v => secondGet(v) >= 0.0 && secondGet(v) <= 1.0)
    )

  /** Clamp a Double to [0, 1]. */
  private def clamp01(x: Double): Double =
    math.max(0.0, math.min(1.0, x))

  /**
   * Build a clamp-note string by comparing original vs clamped values.
   * Only called when `Constraint.evaluateAll` reported failed checks (i.e.
   * at least one field was out of range). Returns `Some(note)` if any value
   * was clamped, `None` otherwise.
   */
  private def clampNote(
    firstLabel: String,
    firstRaw: Double,
    firstClamped: Double,
    secondLabel: String,
    secondRaw: Double,
    secondClamped: Double
  ): Option[String] =
    val firstNote: Option[String] =
      if firstRaw != firstClamped then Some(s"clamped $firstLabel $firstRaw→$firstClamped")
      else None
    val secondNote: Option[String] =
      if secondRaw != secondClamped then Some(s"clamped $secondLabel $secondRaw→$secondClamped")
      else None
    val allNotes: List[String] = List(firstNote, secondNote).flatten
    if allNotes.nonEmpty then Some(allNotes.mkString(", ")) else None

  /** Compute F1 from precision and recall (0.0 when both are 0). */
  private def f1(precision: Double, recall: Double): Double =
    if precision + recall == 0.0 then 0.0
    else 2.0 * precision * recall / (precision + recall)

  /**
   * Build the eval-mode feedback: the judge's reasoning, optionally appended
   * with a clamp note if values were out of range.
   */
  private def evalFeedback(reasoning: String, clampNoteMsg: Option[String]): Option[String] =
    clampNoteMsg match
      case Some(note) => Some(s"$reasoning\n$note")
      case None       => Some(reasoning)

  // ── Factory methods ──────────────────────────────────────────────────────

  /**
   * Build a SemanticF1 judge metric.
   *
   * The judge calls `structured.complete[SemanticF1Judge]` with a prompt
   * carrying the question, ground truth, and system response. The F1 is
   * `2 * precision * recall / (precision + recall)` (0.0 when both are 0).
   *
   * Out-of-range precision/recall are clamped to [0, 1] via `Constraint.check`
   * and flagged in the feedback string (R1.13). A judge parse failure (after
   * structured-llm retries) propagates as a raise — the harness catches it as
   * a metric failure (R1.12).
   *
   * @param structured the StructuredLLM used to obtain the judge completion
   * @param threshold  the binarization threshold for optimization mode
   * @return           a `Metric[F, String, String]` scoring prediction vs gold
   */
  def semanticF1[F[_]](
    structured: StructuredLLM[F],
    threshold: Double = defaultThreshold
  )(using F: Async[F]): Metric[F, String, String] =
    (gold: Example[String, String], pred: String, trace: Option[Trace]) =>
      val prompt: Prompt = Prompt.simple(
        semanticF1System,
        judgeUserMessage(gold.input, gold.gold, pred)
      )
      for
        judge <- structured.complete[SemanticF1Judge](prompt)
        // R1.13: detect out-of-range via Constraint.check
        constraints: Vector[Constraint[SemanticF1Judge]] =
          rangeConstraints[SemanticF1Judge]("precision", _.precision, "recall", _.recall)
        checked: ValidationResult[SemanticF1Judge] =
          Constraint.evaluateAll(judge, constraints)
        // Clamp to [0, 1] (transformation — detection was via Constraint.check)
        clampedP: Double = clamp01(judge.precision)
        clampedR: Double = clamp01(judge.recall)
        note: Option[String] =
          if checked.allChecksPassed then None
          else clampNote("precision", judge.precision, clampedP, "recall", judge.recall, clampedR)
        f1Value: Double = f1(clampedP, clampedR)
        score: Score = trace match
          case None =>
            Score(f1Value, evalFeedback(judge.reasoning, note))
          case Some(_) =>
            Score.bool(f1Value >= threshold)
      yield score

  /**
   * Build a CompleteAndGrounded judge metric.
   *
   * The judge calls `structured.complete[CompleteAndGroundedJudge]` with a
   * prompt carrying the question, ground truth, and system response. The
   * score is the average `(completeness + groundedness) / 2`.
   *
   * Out-of-range completeness/groundedness are clamped to [0, 1] via
   * `Constraint.check` and flagged in the feedback string (R1.13). A judge
   * parse failure propagates as a raise (R1.12).
   *
   * @param structured the StructuredLLM used to obtain the judge completion
   * @param threshold  the binarization threshold for optimization mode
   * @return           a `Metric[F, String, String]` scoring prediction vs gold
   */
  def completeAndGrounded[F[_]](
    structured: StructuredLLM[F],
    threshold: Double = defaultThreshold
  )(using F: Async[F]): Metric[F, String, String] =
    (gold: Example[String, String], pred: String, trace: Option[Trace]) =>
      val prompt: Prompt = Prompt.simple(
        completeAndGroundedSystem,
        judgeUserMessage(gold.input, gold.gold, pred)
      )
      for
        judge <- structured.complete[CompleteAndGroundedJudge](prompt)
        // R1.13: detect out-of-range via Constraint.check
        constraints: Vector[Constraint[CompleteAndGroundedJudge]] =
          rangeConstraints[CompleteAndGroundedJudge](
            "completeness",
            _.completeness,
            "groundedness",
            _.groundedness
          )
        checked: ValidationResult[CompleteAndGroundedJudge] =
          Constraint.evaluateAll(judge, constraints)
        // Clamp to [0, 1]
        clampedC: Double = clamp01(judge.completeness)
        clampedG: Double = clamp01(judge.groundedness)
        note: Option[String] =
          if checked.allChecksPassed then None
          else clampNote("completeness", judge.completeness, clampedC, "groundedness", judge.groundedness, clampedG)
        avgValue: Double = (clampedC + clampedG) / 2.0
        score: Score = trace match
          case None =>
            Score(avgValue, evalFeedback(judge.reasoning, note))
          case Some(_) =>
            Score.bool(avgValue >= threshold)
      yield score
