package org.adk4s.optimize.testkit

import cats.effect.IO
import org.adk4s.optimize.Optimizable
import org.adk4s.optimize.PredictorPath
import org.adk4s.optimize.PredictorState

/**
 * Reusable behavioral contract that any optimizer claiming law-compliance
 * must satisfy.
 *
 * An optimizer is a function `P => F[P]` that tunes predictor state without
 * changing the program's structure. The three laws are:
 *
 *  1. **Purity** — the student program passed in is unchanged after compile
 *     (the optimizer must not mutate the input).
 *  2. **Frozen-preserved** — frozen predictors' states are bit-identical in
 *     the result (the optimizer must not touch frozen sites).
 *  3. **Path-set-preserved** — the result has the same predictor path set as
 *     the student (optimizers tune state, never structure).
 *
 * The testkit follows the main-scope laws-suite pattern established by
 * `AgentMemoryLaws`: laws return `IO[Boolean]` so they can be run inside
 * `munit.CatsEffectSuite` tests. The `IO` is pure (no real I/O — just
 * `IO.pure` / `IO.delay` over `Optimizable.predictors`).
 */
final class OptimizerLaws[P](using opt: Optimizable[P]):

  /**
   * Law 1 (purity): the student program is unchanged after compile.
   *
   * Snapshots `predictors(p)` before and after the optimizer runs, and
   * returns `before == after`.
   */
  def purity(optimizer: P => IO[P])(p: P): IO[Boolean] =
    for
      before <- IO.pure(opt.predictors(p))
      _      <- optimizer(p)
      after  <- IO.pure(opt.predictors(p))
    yield before == after

  /**
   * Law 2 (frozen-preserved): frozen predictors' states are bit-identical.
   *
   * Filters `predictors(p)` for frozen entries, runs the optimizer, and
   * returns `frozenBefore == frozenAfter`.
   */
  def frozenPreserved(optimizer: P => IO[P])(p: P): IO[Boolean] =
    for
      frozenBefore <- IO.pure(opt.predictors(p).filter(_._2.frozen))
      result       <- optimizer(p)
      frozenAfter  <- IO.pure(opt.predictors(result).filter(_._2.frozen))
    yield frozenBefore == frozenAfter

  /**
   * Law 3 (path-set-preserved): the result's path set equals the input's.
   *
   * Snapshots the path set before, runs the optimizer, and returns
   * `beforePaths == afterPaths`.
   */
  def pathSetPreserved(optimizer: P => IO[P])(p: P): IO[Boolean] =
    for
      beforePaths <- IO.pure(opt.predictors(p).map(_._1).toSet)
      result      <- optimizer(p)
      afterPaths  <- IO.pure(opt.predictors(result).map(_._1).toSet)
    yield beforePaths == afterPaths

  /** Conjunction of all three laws. Returns `true` iff every law holds. */
  def all(optimizer: P => IO[P])(p: P): IO[Boolean] =
    for
      p1 <- purity(optimizer)(p)
      p2 <- frozenPreserved(optimizer)(p)
      p3 <- pathSetPreserved(optimizer)(p)
    yield p1 && p2 && p3

object OptimizerLaws:
  /** Construct an `OptimizerLaws[P]` for a program type with an `Optimizable` instance. */
  def apply[P](using opt: Optimizable[P]): OptimizerLaws[P] = new OptimizerLaws[P]
