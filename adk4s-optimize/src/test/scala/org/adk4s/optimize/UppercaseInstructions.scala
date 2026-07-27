package org.adk4s.optimize

import cats.effect.IO

/**
 * Toy optimizer that rewrites every non-frozen predictor's instructions to
 * uppercase via `Optimizable.updateAll`.
 *
 * This is one of the two structurally different toy optimizers that compile
 * the same program through one derived `Optimizable` instance, exercising the
 * surface as the acceptance test.
 */
object UppercaseInstructions:

  /** Compile `p` by uppercasing every non-frozen predictor's instructions. */
  def compile[P](p: P)(using opt: Optimizable[P]): IO[P] =
    IO.pure(
      opt.updateAll(p, (_, state) => state.copy(instructions = state.instructions.toUpperCase))
    )
