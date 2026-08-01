package org.adk4s.eval

/**
 * Evaluation harness configuration.
 *
 * @param parallelism  maximum concurrency for parallel evaluation (fs2
 *                     `parEvalMap`); values `<= 0` fall back to `1` via the
 *                     smart constructor
 * @param failureScore the score substituted when a program or metric raises
 *                     (default `0.0`)
 * @param maxErrors    `None` = unlimited failures; `Some(n)` = raise
 *                     `EvalError.TooManyErrors` after `n + 1` failures
 * @param seed         reserved for future shuffling features (the harness
 *                     itself is deterministic given a pure program + metric)
 */
final case class EvalConfig(
  parallelism: Int,
  failureScore: Double,
  maxErrors: Option[Int],
  seed: Long
)

object EvalConfig:

  /** Smart constructor — `parallelism <= 0` falls back to `1`. */
  def apply(
    parallelism: Int = 8,
    failureScore: Double = 0.0,
    maxErrors: Option[Int] = None,
    seed: Long = 0L
  ): EvalConfig =
    new EvalConfig(
      parallelism = if parallelism <= 0 then 1 else parallelism,
      failureScore = failureScore,
      maxErrors = maxErrors,
      seed = seed
    )
