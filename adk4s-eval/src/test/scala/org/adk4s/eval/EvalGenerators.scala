package org.adk4s.eval

import hedgehog.Gen
import hedgehog.Range

/**
 * Hedgehog generators for the eval-core test oracle.
 *
 * All generators are constructive — they build values directly, not by
 * shrinking existing ones. `genDevset` generates examples with sequential
 * ids (0..N-1) so the devset-order property can compare row order to id
 * order.
 */
object EvalGenerators:

  /**
   * A devset of N examples with sequential ids "0".."N-1".
   *
   * The input and gold are strings derived from the index so they are
   * deterministic and distinguishable.
   */
  def genDevset: Gen[Vector[Example[String, String]]] =
    for n <- Gen.int(Range.linear(1, 30))
    yield (0 until n).toVector.map(i =>
      Example(
        input = s"input-$i",
        gold = s"gold-$i",
        id = Some(i.toString)
      )
    )

  /** A devset with a configurable size range. */
  def genDevsetSized(min: Int, max: Int): Gen[Vector[Example[String, String]]] =
    for n <- Gen.int(Range.linear(min, max))
    yield (0 until n).toVector.map(i =>
      Example(
        input = s"input-$i",
        gold = s"gold-$i",
        id = Some(i.toString)
      )
    )

  /** Parallelism in 1..16. */
  def genParallelism: Gen[Int] =
    Gen.int(Range.linear(1, 16))

  /** A failure score in 0.0..1.0. */
  def genFailureScore: Gen[Double] =
    Gen.double(Range.linearFrac(0.0, 1.0))

  /** A score value in 0.0..1.0. */
  def genScoreValue: Gen[Double] =
    Gen.double(Range.linearFrac(0.0, 1.0))

  /** A vector of scores, size 1..30. */
  def genScores: Gen[Vector[Double]] =
    genScoreValue.list(Range.linear(1, 30)).map(_.toVector)

  /** A random feedback string. */
  def genFeedback: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(0, 20))

  /** A threshold in 0.1..0.9. */
  def genThreshold: Gen[Double] =
    Gen.double(Range.linearFrac(0.1, 0.9))

  /** A precision/recall pair in 0.0..1.0. */
  def genPrecisionRecall: Gen[(Double, Double)] =
    for
      p <- genScoreValue
      r <- genScoreValue
    yield (p, r)

  /** A completeness/groundedness pair in 0.0..1.0. */
  def genCompletenessGroundedness: Gen[(Double, Double)] =
    for
      c <- genScoreValue
      g <- genScoreValue
    yield (c, g)

  /** A reasoning string for judge outputs. */
  def genReasoning: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(1, 30))

  /** A Double outside [0, 1] (for out-of-range clamping tests). */
  def genOutOfRange: Gen[Double] =
    Gen.choice1(
      Gen.double(Range.linearFrac(-1.0, -0.01)),
      Gen.double(Range.linearFrac(1.01, 2.0))
    )

  /**
   * Generate an EvaluationResult with mixed outcomes for round-trip testing.
   *
   * Uses String for I/O (upickle ReadWriter[String] is built-in).
   * Classify by outcome mix: all-succeeded, all-failed, mixed.
   */
  def genEvaluationResult: Gen[EvaluationResult[String, String]] =
    Gen.choice1(
      genEvaluationResultAllSucceeded,
      genEvaluationResultAllFailed,
      genEvaluationResultMixed
    )

  /** Generate an EvaluationResult where all rows succeeded. */
  def genEvaluationResultAllSucceeded: Gen[EvaluationResult[String, String]] =
    for
      n      <- Gen.int(Range.linear(1, 20))
      scores <- genScoreValue.list(Range.linear(1, 20)).map(_.toVector)
    yield
      val rows: Vector[EvalRow[String, String]] = (0 until n).toVector.map(i =>
        EvalRow(
          Example(s"input-$i", s"gold-$i", Some(i.toString)),
          EvalOutcome.Succeeded(s"pred-$i"),
          Score(scores(i % scores.size), Some(s"feedback-$i"))
        )
      )
      val mean: Double = rows.map(_.score.value).sum / rows.size
      EvaluationResult(mean, rows)

  /** Generate an EvaluationResult where all rows failed. */
  def genEvaluationResultAllFailed: Gen[EvaluationResult[String, String]] =
    for n <- Gen.int(Range.linear(1, 20))
    yield
      val rows: Vector[EvalRow[String, String]] = (0 until n).toVector.map(i =>
        EvalRow(
          Example(s"input-$i", s"gold-$i", Some(i.toString)),
          EvalOutcome.Failed(new RuntimeException(s"error-$i")),
          Score(0.0, Some(s"failure feedback-$i"))
        )
      )
      val mean: Double = rows.map(_.score.value).sum / rows.size
      EvaluationResult(mean, rows)

  /** Generate an EvaluationResult with a mix of succeeded and failed rows. */
  def genEvaluationResultMixed: Gen[EvaluationResult[String, String]] =
    for
      n       <- Gen.int(Range.linear(2, 20))
      scores  <- genScoreValue.list(Range.linear(1, 20)).map(_.toVector)
      failIdx <- Gen.int(Range.linear(0, n - 1))
    yield
      val rows: Vector[EvalRow[String, String]] = (0 until n).toVector.map(i =>
        if i == failIdx then
          EvalRow(
            Example(s"input-$i", s"gold-$i", Some(i.toString)),
            EvalOutcome.Failed(new RuntimeException("boom")),
            Score(0.0, Some("failure feedback"))
          )
        else
          EvalRow(
            Example(s"input-$i", s"gold-$i", Some(i.toString)),
            EvalOutcome.Succeeded(s"pred-$i"),
            Score(scores(i % scores.size), Some(s"feedback-$i"))
          )
      )
      val mean: Double = rows.map(_.score.value).sum / rows.size
      EvaluationResult(mean, rows)

  /**
   * Custom equality for `EvaluationResult[String, String]` that compares
   * `EvalOutcome.Failed` by error class name + message (not reference equality),
   * since JSON round-trip reconstructs a new `RuntimeException` instance.
   *
   * Returns true if the two results are equal by this comparison.
   */
  def resultEqual(a: EvaluationResult[String, String], b: EvaluationResult[String, String]): Boolean =
    a.score == b.score &&
      a.rows.size == b.rows.size &&
      a.rows.zip(b.rows).forall { case (ra, rb) =>
        rowEqual(ra, rb)
      }

  /** Custom row equality — compares outcome by type + message, not reference. */
  private def rowEqual(a: EvalRow[String, String], b: EvalRow[String, String]): Boolean =
    a.example == b.example &&
      outcomeEqual(a.outcome, b.outcome) &&
      a.score == b.score

  /** Custom outcome equality — `Failed` compared by class name + message. */
  private def outcomeEqual(a: EvalOutcome[String], b: EvalOutcome[String]): Boolean =
    (a, b) match
      case (EvalOutcome.Succeeded(va), EvalOutcome.Succeeded(vb)) => va == vb
      case (EvalOutcome.Failed(ea), EvalOutcome.Failed(eb)) =>
        ea.getClass.getName == eb.getClass.getName && ea.getMessage == eb.getMessage
      case _ => false
