package org.adk4s.eval

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import fs2.Stream
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.eval.EvalGenerators.*
import org.adk4s.structured.core.ParseResult
import org.adk4s.structured.core.Prompt
import org.adk4s.structured.core.PromptTemplate
import org.adk4s.structured.core.Schema
import org.adk4s.structured.core.StructuredLLM
import org.adk4s.structured.core.StructuredLLMError
import org.adk4s.structured.core.ValidationResult
import org.adk4s.structured.sap.SchemaAlignedParser
import org.adk4s.structured.sap.StreamState

/**
 * Test oracle for the LLM judges (spec: llm-judges).
 *
 * Tests are written from the spec BEFORE implementation. The mock
 * StructuredLLM uses the real SAP parser to decode a pre-baked JSON response,
 * so the full parse path is exercised (including the parse-failure → metric
 * -failure path for R1.12).
 *
 * Covers 4 Ring 3 properties:
 *  1. semantic-f1-eval-mode
 *  2. semantic-f1-optimization-mode-binarized
 *  3. out-of-range-clamped
 *  4. complete-and-grounded-eval-mode
 *
 * Plus scenarios from the spec's Proof Obligations table:
 *  - SemanticF1 eval mode (trace = None)
 *  - SemanticF1 optimization mode fail (trace = Some)
 *  - SemanticF1 optimization mode pass (trace = Some)
 *  - CompleteAndGrounded eval mode
 *  - CompleteAndGrounded optimization mode (binarized)
 *  - Unparseable judge completion → metric raises
 *  - Precision above 1.0 → clamped
 *  - Recall below 0.0 → clamped
 */
class JudgesSpec extends HedgehogSuite:

  // ═══════════════════════════════════════════════════════════════════════════
  //  Mock StructuredLLM — uses real SAP to parse a pre-baked JSON response
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * A mock `StructuredLLM[IO]` that returns a fixed raw response string.
   * The `complete[A]` method parses the response with the real SAP, so
   * parse failures propagate as `StructuredLLMError.ParseFailed` (R1.12).
   */
  private class MockStructuredLLM(response: String) extends StructuredLLM[IO]:
    def complete[A: Schema](prompt: Prompt): IO[A] =
      SchemaAlignedParser.parse[A](response) match
        case ParseResult.Success(value, _) => IO.pure(value)
        case ParseResult.Failure(errors) =>
          IO.raiseError(StructuredLLMError.ParseFailed(errors, response))

    def completeRaw[A: Schema](prompt: Prompt): IO[A] =
      complete[A](prompt)

    def streamWithResult[A: Schema](
      prompt: Prompt
    ): IO[(Stream[IO, String], IO[A])] =
      IO.pure((Stream.empty, complete[A](prompt)))

    def streamWithResultRaw[A: Schema](
      prompt: Prompt
    ): IO[(Stream[IO, String], IO[A])] =
      streamWithResult[A](prompt)

    def completeValidated[A: Schema](prompt: Prompt): IO[ValidationResult[A]] =
      complete[A](prompt).map(value => ValidationResult(value, Vector.empty))

    def streamPartial[A: Schema](prompt: Prompt): Stream[IO, StreamState[A]] =
      Stream.eval(complete[A](prompt).map(StreamState.complete))

    def function[I, A: Schema](template: PromptTemplate[I]): I => IO[A] =
      (_: I) => complete[A](Prompt.empty)

    def extractor[A: Schema](systemPrompt: String): String => IO[A] =
      (_: String) => complete[A](Prompt.empty)

  /** Build a mock judge that returns the given precision/recall/reasoning. */
  private def mockSemanticF1Judge(precision: Double, recall: Double, reasoning: String): StructuredLLM[IO] =
    val json: String =
      s"""{"precision": $precision, "recall": $recall, "reasoning": ${quote(reasoning)}}"""
    new MockStructuredLLM(json)

  /** Build a mock judge that returns the given completeness/groundedness/reasoning. */
  private def mockCompleteAndGroundedJudge(
    completeness: Double,
    groundedness: Double,
    reasoning: String
  ): StructuredLLM[IO] =
    val json: String =
      s"""{"completeness": $completeness, "groundedness": $groundedness, "reasoning": ${quote(reasoning)}}"""
    new MockStructuredLLM(json)

  /** Build a mock judge that returns garbled text (unparseable). */
  private def mockGarbledJudge: StructuredLLM[IO] =
    new MockStructuredLLM("this is not valid JSON at all <<<")

  /** Quote a string as a JSON string literal. */
  private def quote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  // ── Test helpers ─────────────────────────────────────────────────────────

  /** Run an IO deterministically under TestControl. */
  private def runIO[A](io: IO[A]): A =
    TestControl.executeEmbed(io).unsafeRunSync()

  /** Run an IO that is expected to raise, returning the thrown Throwable. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def runIOFailed[A](io: IO[A]): Throwable =
    io.attempt.unsafeRunSync() match
      case Right(_)  => throw new RuntimeException("expected failure, got success")
      case Left(err) => err

  /** A fixed example for judge tests. */
  private val example: Example[String, String] =
    Example("What is 1+1?", "2", Some("ex-1"))

  /** Compute F1 with the edge-case guard (0.0 when p + r == 0). */
  private def f1(precision: Double, recall: Double): Double =
    if precision + recall == 0.0 then 0.0
    else 2.0 * precision * recall / (precision + recall)

  // ═══════════════════════════════════════════════════════════════════════════
  //  Ring 3 Properties
  // ═══════════════════════════════════════════════════════════════════════════

  // ── Property 1: semantic-f1-eval-mode ────────────────────────────────────

  property("semantic-f1-eval-mode"):
    for
      p         <- genScoreValue.forAll
      r         <- genScoreValue.forAll
      reasoning <- genReasoning.forAll
    yield
      val metric: Metric[IO, String, String] =
        Judges.semanticF1[IO](mockSemanticF1Judge(p, r, reasoning), threshold = 0.66)
      val score: Score       = runIO(metric(example, "prediction", trace = None))
      val expectedF1: Double = f1(p, r)
      (score.value ==== expectedF1)
        .and(score.feedback ==== Some(reasoning))

  // ── Property 2: semantic-f1-optimization-mode-binarized ──────────────────

  property("semantic-f1-optimization-mode-binarized"):
    for
      p         <- genScoreValue.forAll
      r         <- genScoreValue.forAll
      threshold <- genThreshold.forAll
    yield
      val metric: Metric[IO, String, String] =
        Judges.semanticF1[IO](mockSemanticF1Judge(p, r, ""), threshold)
      val score: Score     = runIO(metric(example, "prediction", trace = Some(Trace.empty)))
      val expected: Double = if f1(p, r) >= threshold then 1.0 else 0.0
      (score.value ==== expected)
        .and(score.feedback ==== None)

  // ── Property 3: out-of-range-clamped ─────────────────────────────────────

  property("out-of-range-clamped"):
    for
      rawP <- genOutOfRange.forAll
      rawR <- genOutOfRange.forAll
    yield
      val metric: Metric[IO, String, String] =
        Judges.semanticF1[IO](mockSemanticF1Judge(rawP, rawR, ""), threshold = 0.5)
      val score: Score       = runIO(metric(example, "prediction", trace = None))
      val clampedP: Double   = math.max(0.0, math.min(1.0, rawP))
      val clampedR: Double   = math.max(0.0, math.min(1.0, rawR))
      val expectedF1: Double = f1(clampedP, clampedR)
      (score.value ==== expectedF1)
        .and(score.feedback.exists(_.contains("clamp")).====(true))

  // ── Property 4: complete-and-grounded-eval-mode ──────────────────────────

  property("complete-and-grounded-eval-mode"):
    for
      c         <- genScoreValue.forAll
      g         <- genScoreValue.forAll
      reasoning <- genReasoning.forAll
    yield
      val metric: Metric[IO, String, String] =
        Judges.completeAndGrounded[IO](mockCompleteAndGroundedJudge(c, g, reasoning), threshold = 0.66)
      val score: Score     = runIO(metric(example, "prediction", trace = None))
      val expected: Double = (c + g) / 2.0
      (score.value ==== expected)
        .and(score.feedback ==== Some(reasoning))

  // ═══════════════════════════════════════════════════════════════════════════
  //  Scenario tests
  // ═══════════════════════════════════════════════════════════════════════════

  // ── SemanticF1 scenarios ─────────────────────────────────────────────────

  test("SemanticF1 eval mode: precision=0.9, recall=0.8 → F1 ≈ 0.847 with reasoning"):
    val metric: Metric[IO, String, String] =
      Judges.semanticF1[IO](mockSemanticF1Judge(0.9, 0.8, "good overlap"), threshold = 0.66)
    val score: Score       = runIO(metric(example, "prediction", trace = None))
    val expectedF1: Double = f1(0.9, 0.8)
    assertEquals(score.value, expectedF1)
    assertEquals(score.feedback, Some("good overlap"))

  test("SemanticF1 optimization mode fail: F1 < threshold → 0.0, no feedback"):
    val metric: Metric[IO, String, String] =
      Judges.semanticF1[IO](mockSemanticF1Judge(0.5, 0.5, ""), threshold = 0.66)
    val score: Score = runIO(metric(example, "prediction", trace = Some(Trace.empty)))
    assertEquals(score.value, 0.0)
    assertEquals(score.feedback, None)

  test("SemanticF1 optimization mode pass: F1 >= threshold → 1.0, no feedback"):
    val metric: Metric[IO, String, String] =
      Judges.semanticF1[IO](mockSemanticF1Judge(0.9, 0.9, ""), threshold = 0.66)
    val score: Score = runIO(metric(example, "prediction", trace = Some(Trace.empty)))
    assertEquals(score.value, 1.0)
    assertEquals(score.feedback, None)

  // ── CompleteAndGrounded scenarios ────────────────────────────────────────

  test("CompleteAndGrounded eval mode: (0.8 + 0.6) / 2 = 0.7 with reasoning"):
    val metric: Metric[IO, String, String] =
      Judges.completeAndGrounded[IO](mockCompleteAndGroundedJudge(0.8, 0.6, "partial"), threshold = 0.66)
    val score: Score = runIO(metric(example, "prediction", trace = None))
    assertEquals(score.value, 0.7)
    assertEquals(score.feedback, Some("partial"))

  test("CompleteAndGrounded optimization mode: 0.7 >= 0.66 → 1.0"):
    val metric: Metric[IO, String, String] =
      Judges.completeAndGrounded[IO](mockCompleteAndGroundedJudge(0.8, 0.6, ""), threshold = 0.66)
    val score: Score = runIO(metric(example, "prediction", trace = Some(Trace.empty)))
    assertEquals(score.value, 1.0)
    assertEquals(score.feedback, None)

  test("CompleteAndGrounded optimization mode fail: 0.5 < 0.66 → 0.0"):
    val metric: Metric[IO, String, String] =
      Judges.completeAndGrounded[IO](mockCompleteAndGroundedJudge(0.4, 0.6, ""), threshold = 0.66)
    val score: Score = runIO(metric(example, "prediction", trace = Some(Trace.empty)))
    assertEquals(score.value, 0.0)
    assertEquals(score.feedback, None)

  // ── Parse failure scenario (R1.12) ───────────────────────────────────────

  test("unparseable judge completion → metric raises (not crash)"):
    val metric: Metric[IO, String, String] =
      Judges.semanticF1[IO](mockGarbledJudge, threshold = 0.66)
    val err: Throwable = runIOFailed(metric(example, "prediction", trace = None))
    err match
      case _: StructuredLLMError => () // expected
      case other                 => fail(s"expected StructuredLLMError, got: ${other.getClass.getName}")

  // ── Out-of-range clamping scenarios (R1.13) ──────────────────────────────

  test("precision above 1.0 → clamped to 1.0, feedback notes clamp"):
    val metric: Metric[IO, String, String] =
      Judges.semanticF1[IO](mockSemanticF1Judge(1.2, 0.8, "overlap"), threshold = 0.5)
    val score: Score       = runIO(metric(example, "prediction", trace = None))
    val expectedF1: Double = f1(1.0, 0.8)
    assertEquals(score.value, expectedF1)
    assert(score.feedback.exists(_.contains("clamp")), s"feedback should note clamp: ${score.feedback}")

  test("recall below 0.0 → clamped to 0.0, F1 = 0.0, feedback notes clamp"):
    val metric: Metric[IO, String, String] =
      Judges.semanticF1[IO](mockSemanticF1Judge(0.7, -0.1, "overlap"), threshold = 0.5)
    val score: Score = runIO(metric(example, "prediction", trace = None))
    assertEquals(score.value, 0.0)
    assert(score.feedback.exists(_.contains("clamp")), s"feedback should note clamp: ${score.feedback}")

  // ── CompleteAndGrounded out-of-range clamping ────────────────────────────

  test("CompleteAndGrounded out-of-range → clamped, feedback notes clamp"):
    val metric: Metric[IO, String, String] =
      Judges.completeAndGrounded[IO](mockCompleteAndGroundedJudge(1.5, -0.2, "overlap"), threshold = 0.5)
    val score: Score     = runIO(metric(example, "prediction", trace = None))
    val expected: Double = (1.0 + 0.0) / 2.0
    assertEquals(score.value, expected)
    assert(score.feedback.exists(_.contains("clamp")), s"feedback should note clamp: ${score.feedback}")
