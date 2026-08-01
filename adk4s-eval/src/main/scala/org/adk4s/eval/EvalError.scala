package org.adk4s.eval

/**
 * Eval error ADT — stands alone in `org.adk4s.eval` (does NOT extend
 * `AdkError`).
 *
 * Raised via `F.raiseError` (not bare `throw`), satisfying WartRemover's
 * `Throw` wart. A future bridge to `AdkError` can be added if the error
 * hierarchy needs unification.
 */
sealed trait EvalError extends Throwable

object EvalError:

  /**
   * Raised when the number of failed examples exceeds `maxErrors`.
   *
   * @param count  the number of failures at the time of the abort
   * @param max    the configured `maxErrors` cap
   * @param partial the rows collected before the abort (typed with the same
   *                `I` and `O` as the evaluation)
   */
  final case class TooManyErrors[I, O](
    count: Int,
    max: Int,
    partial: Vector[EvalRow[I, O]]
  ) extends EvalError
