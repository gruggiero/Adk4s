package org.adk4s.eval

import cats.Applicative
import cats.Functor

/**
 * The scoring interface between a program's output and a labeled gold answer.
 *
 * The optional `Trace` argument toggles evaluation vs optimization behavior
 * (DSPy's "trace is None" idiom): `None` means plain evaluation, `Some`
 * means an optimizer is calling and may read the feedback channel. The
 * harness always passes `trace = None` (R1.5). This signature is FROZEN —
 * it will never change so Phase 2/3 optimizers can depend on it.
 *
 * The `Score.feedback` channel rides along inertly: it is preserved verbatim
 * into rows and exports but never influences the aggregate score. A metric
 * failure (raise) is scored `failureScore` by the harness, never crashing it.
 */
trait Metric[F[_], I, O]:

  /**
   * Score a prediction against the gold example.
   *
   * @param gold  the labeled example
   * @param pred  the program's prediction
   * @param trace `None` in evaluation mode; `Some(trace)` in optimization mode
   * @return      the score as an `F[Score]`
   */
  def apply(gold: Example[I, O], pred: O, trace: Option[Trace]): F[Score]

  /**
   * Transform the score output of this metric.
   *
   * @param f  the score transformation
   * @return   a new metric that applies `f` to the original metric's score
   */
  def map(f: Score => Score)(using F: Functor[F]): Metric[F, I, O] =
    (gold: Example[I, O], pred: O, trace: Option[Trace]) => F.map(this.apply(gold, pred, trace))(f)

object Metric:

  /** Build a metric from a predicate: `Score(1.0)` if true, `Score(0.0)` if false. */
  def fromPredicate[F[_], I, O](
    f: (Example[I, O], O) => Boolean
  )(using F: Applicative[F]): Metric[F, I, O] =
    (gold: Example[I, O], pred: O, trace: Option[Trace]) => F.pure(Score.bool(f(gold, pred)))

  /** Build a metric from a double-valued scoring function. */
  def fromDouble[F[_], I, O](
    f: (Example[I, O], O) => Double
  )(using F: Applicative[F]): Metric[F, I, O] =
    (gold: Example[I, O], pred: O, trace: Option[Trace]) => F.pure(Score(f(gold, pred)))
