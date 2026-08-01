package org.adk4s.eval

import cats.Applicative

/**
 * Built-in pure string metrics — no LLM call, full determinism.
 *
 * These are the simplest building blocks for regression testing: zero cost,
 * zero nondeterminism. Both return `Metric[F, String, String]` with an
 * `Applicative[F]` bound (no `Async` needed).
 */
object Metrics:

  /** Exact string equality: `Score(1.0)` if prediction equals gold, `Score(0.0)` otherwise. */
  def exactMatch[F[_]](using F: Applicative[F]): Metric[F, String, String] =
    Metric.fromPredicate[F, String, String]((gold, pred) => pred == gold.gold)

  /** Contains-all: `Score(1.0)` if every gold token is present in the prediction, `Score(0.0)` otherwise. */
  def containsAll[F[_]](using F: Applicative[F]): Metric[F, String, String] =
    Metric.fromPredicate[F, String, String]((gold, pred) => gold.gold.split(' ').forall(token => pred.contains(token)))
