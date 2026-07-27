package org.adk4s.optimize

import org.adk4s.structured.core.PromptTemplate
import org.adk4s.structured.core.Schema
import org.adk4s.structured.core.StructuredLLM

/**
 * Placeholder predictor wrapping `StructuredLLM.completeTemplate`, enough to
 * carry `PredictorState` and exercise the `HasPredictorState` leaf capability
 * and `Optimizable` structural derivation.
 *
 * Replaced by the real `Predict` in Phase 2. In Phase 0, the placeholder
 * carries state but does NOT render demos into prompts (no `run`/`render`/
 * `complete` method is exposed — state is carried but not consumed).
 *
 * @tparam F[_] the effect type (carried via `StructuredLLM[F]`, not invoked in Phase 0)
 * @tparam I    the template input type
 * @tparam O    the output type (with a `Schema[O]` for `completeTemplate`)
 */
final case class Predict0[F[_], I, O](
  state: PredictorState,
  template: PromptTemplate[I],
  schema: Schema[O],
  structured: StructuredLLM[F]
)

object Predict0:
  /** Leaf-predictor capability for `Predict0`: reads/replaces the `state` field. */
  given hasPredictorStateForPredict0[F[_], I, O]: HasPredictorState[Predict0[F, I, O]] with
    def state(self: Predict0[F, I, O]): PredictorState                           = self.state
    def withState(self: Predict0[F, I, O], s: PredictorState): Predict0[F, I, O] = self.copy(state = s)
