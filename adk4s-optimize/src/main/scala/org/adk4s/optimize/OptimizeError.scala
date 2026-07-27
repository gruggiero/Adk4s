package org.adk4s.optimize

/**
 * Typed error ADT for `Optimizable.update` / `Optimizable.updateEither` failures.
 *
 * Stands alone in `org.adk4s.optimize` (NOT extending `org.adk4s.core.error.AdkError`)
 * per the cross-phase convention: new error ADTs stand alone in their module
 * and bridge to `AdkError` later. This keeps the optimize module decoupled
 * from `adk4s-core`.
 *
 * Extends `Throwable` so the raising variant `Optimizable.update` can throw
 * the typed error on the left branch of `updateEither` (with a targeted
 * `@SuppressWarnings` on that single method).
 *
 * Every match over this enum SHALL be exhaustive — no catch-all arm is
 * permitted (enforced by `-Wconf:name=PatternMatchExhaustivity:e`). Adding
 * a variant in a later phase is a compile error everywhere the enum is
 * matched, not a silent fall-through.
 */
enum OptimizeError extends Throwable:
  /** The addressed predictor path is not enumerated by `Optimizable.predictors`. */
  case UnknownPath(path: PredictorPath)

  /** The addressed predictor's state has the frozen flag set. */
  case FrozenPath(path: PredictorPath)
