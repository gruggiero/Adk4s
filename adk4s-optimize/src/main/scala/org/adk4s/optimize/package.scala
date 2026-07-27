package org.adk4s.optimize

/**
 * Optimizable predictor surface (DSPy port — Phase 0).
 *
 * This package provides the erased `Optimizable[P]` typeclass with
 * `Mirror`-based derivation, pure `PredictorState`/`PredictorPath`/`Demo`
 * data types, the `OptimizeError` ADT, the `HasPredictorState[Self]` leaf
 * capability, and the `Predict0[F, I, O]` placeholder predictor.
 *
 * The `org.adk4s.optimize.testkit` companion package publishes the
 * `OptimizerLaws` testkit (main scope) for downstream optimizer modules.
 */
