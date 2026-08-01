package org.adk4s.eval

/**
 * One row of the evaluation result matrix: the example, its outcome, and its
 * score.
 *
 * @param example the evaluation datum
 * @param outcome whether the program succeeded or failed
 * @param score   the metric score (or `failureScore` if the program/metric
 *                raised)
 */
final case class EvalRow[I, O](
  example: Example[I, O],
  outcome: EvalOutcome[O],
  score: Score
)
