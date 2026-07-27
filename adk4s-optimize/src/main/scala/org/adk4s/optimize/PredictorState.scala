package org.adk4s.optimize

/**
 * Pure, serializable-ready view of one LM-call site's tunable state.
 *
 * Optimizers read and write this state via `Optimizable` actions. The state
 * is plain immutable data with no behavior:
 *   - `instructions` — the tunable instruction string for the LM-call site.
 *   - `demos` — the few-shot example list (`Vector[Demo]`).
 *   - `frozen` — when `true`, excludes this state from `Optimizable.updateAll`
 *     and causes `Optimizable.updateEither` to return a `FrozenPath` error.
 *     A frozen state is still readable via `Optimizable.predictors`.
 *
 * Freezing is data, not a type-level distinction: a frozen state is a valid
 * value constructible through the public API.
 */
final case class PredictorState(instructions: String, demos: Vector[Demo], frozen: Boolean)
