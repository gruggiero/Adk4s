package org.adk4s.optimize

/**
 * Leaf-predictor capability: anything that *is* a predictor exposes its
 * `PredictorState` for reading and produces a new predictor with a replaced
 * `PredictorState` for writing.
 *
 * A field with this capability is treated as a leaf predictor by
 * `Optimizable.derived` (path = field name). The Phase-0 placeholder
 * `Predict0` implements this capability; Phase 2's real predictors will
 * implement the same capability and drop into derived `Optimizable`
 * instances unchanged.
 *
 * @tparam Self the predictor's own type (the type whose state is read/replaced)
 */
trait HasPredictorState[Self]:
  /** Read the predictor's current tunable state. */
  def state(self: Self): PredictorState

  /** Produce a new predictor with the replaced state. */
  def withState(self: Self, s: PredictorState): Self
