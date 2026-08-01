package org.adk4s.eval

import cats.effect.Async
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fs2.Stream

/**
 * The evaluation harness — runs a program over a labeled dataset in
 * parallel, scores each result with a metric, and aggregates into a single
 * mean score with per-example rows.
 *
 * Semantics (see `openspec/concepts/eval-harness.md`):
 *  - Rows are returned in devset declaration order regardless of completion
 *    order (fs2 `parEvalMap` — ordered).
 *  - Program and metric failures are caught per-example: the row gets
 *    `EvalOutcome.Failed` and `Score(config.failureScore)`, and the run
 *    continues.
 *  - When failures exceed `maxErrors`, the harness raises
 *    `EvalError.TooManyErrors` carrying the partial rows and cancels all
 *    in-flight work.
 *  - The aggregate score is the arithmetic mean of all row scores (including
 *    substituted failure scores); the empty devset yields `score = 0.0`.
 *  - The harness always passes `trace = None` to the metric.
 */
object Evaluate:

  /**
   * Run `program` over `devset` with `metric` and `config`.
   *
   * @param program the program to evaluate: `I => F[O]`
   * @param devset  the labeled dataset
   * @param metric  the scoring metric
   * @param config  the evaluation configuration (parallelism, failureScore,
   *                maxErrors, seed)
   * @return        the evaluation result as `F[EvaluationResult[I, O]]`, or
   *                `F.raiseError(EvalError.TooManyErrors(...))` if the failure
   *                cap is exceeded
   */
  def apply[F[_], I, O](
    program: I => F[O],
    devset: Vector[Example[I, O]],
    metric: Metric[F, I, O],
    config: EvalConfig = EvalConfig()
  )(using F: Async[F]): F[EvaluationResult[I, O]] =
    if devset.isEmpty then F.pure(EvaluationResult(0.0, Vector.empty[EvalRow[I, O]]))
    else
      config.maxErrors match
        case None      => evalUncapped(program, devset, metric, config)
        case Some(cap) => evalCapped(program, devset, metric, config, cap)

  /** Uncapped evaluation — all failures recorded, no TooManyErrors. */
  private def evalUncapped[F[_], I, O](
    program: I => F[O],
    devset: Vector[Example[I, O]],
    metric: Metric[F, I, O],
    config: EvalConfig
  )(using F: Async[F]): F[EvaluationResult[I, O]] =
    Stream
      .emits(devset)
      .covary[F]
      .parEvalMap(config.parallelism)(example => evalOne(program, example, metric, config))
      .compile
      .toVector
      .map(rows => EvaluationResult(mean(rows), rows))

  /** Capped evaluation — raises TooManyErrors when failures exceed cap. */
  private def evalCapped[F[_], I, O](
    program: I => F[O],
    devset: Vector[Example[I, O]],
    metric: Metric[F, I, O],
    config: EvalConfig,
    cap: Int
  )(using F: Async[F]): F[EvaluationResult[I, O]] =
    for
      failCountRef <- Ref.of[F, Int](0)
      rowsRef      <- Ref.of[F, Vector[EvalRow[I, O]]](Vector.empty)
      result <-
        Stream
          .emits(devset)
          .covary[F]
          .parEvalMap(config.parallelism)(example =>
            for
              row      <- evalOne(program, example, metric, config)
              _        <- rowsRef.update(_ :+ row)
              newCount <- if row.outcome.isFailed then failCountRef.updateAndGet(_ + 1) else F.pure(0)
              _ <-
                if newCount > cap then
                  F.raiseError(EvalError.TooManyErrors[I, O](newCount, cap, Vector.empty[EvalRow[I, O]]))
                else F.unit
            yield row
          )
          .compile
          .toVector
          .map(rows => EvaluationResult(mean(rows), rows))
          .handleErrorWith {
            case _: EvalError.TooManyErrors[?, ?] =>
              rowsRef.get.flatMap(partial =>
                failCountRef.get.flatMap(count => F.raiseError(EvalError.TooManyErrors[I, O](count, cap, partial)))
              )
            case other => F.raiseError(other)
          }
    yield result

  /** Evaluate a single example — catches program and metric failures. */
  private def evalOne[F[_], I, O](
    program: I => F[O],
    example: Example[I, O],
    metric: Metric[F, I, O],
    config: EvalConfig
  )(using F: Async[F]): F[EvalRow[I, O]] =
    val runProgram: F[EvalRow[I, O]] =
      program(example.input).attempt.flatMap {
        case Right(pred) =>
          metric(example, pred, trace = None).attempt.flatMap {
            case Right(score) => F.pure(EvalRow(example, EvalOutcome.Succeeded(pred), score))
            case Left(err)    => F.pure(EvalRow(example, EvalOutcome.Failed(err), Score(config.failureScore)))
          }
        case Left(err) => F.pure(EvalRow(example, EvalOutcome.Failed(err), Score(config.failureScore)))
      }
    runProgram

  /** Arithmetic mean of row scores; empty → 0.0. */
  private def mean[I, O](rows: Vector[EvalRow[I, O]]): Double =
    if rows.isEmpty then 0.0
    else rows.map(_.score.value).sum / rows.size
