package org.adk4s.eval

/**
 * A metric score: a numeric `value` and an optional `feedback` string.
 *
 * Mirrors DSPy's `Prediction(score, feedback)`. The `feedback` channel rides
 * along inertly — it is preserved verbatim into rows and exports but never
 * influences the aggregate score (the eval-core `feedback-inert` property).
 *
 * @param value    the numeric score (any Double — metrics may return negative
 *                 values, percentages >1.0, etc.)
 * @param feedback optional reasoning/feedback string (read by GEPA-style
 *                 optimizers in Phase 3; inert in evaluation mode)
 */
final case class Score(value: Double, feedback: Option[String] = None)

object Score:

  /** A zero score with no feedback. */
  def zero: Score = Score(0.0)

  /** A binary score: `1.0` if `b` is true, `0.0` otherwise. */
  def bool(b: Boolean): Score = if b then Score(1.0) else Score(0.0)

  /** A score with a feedback string. */
  def withFeedback(value: Double, feedback: String): Score =
    Score(value, Some(feedback))
