package org.adk4s.optimize

import cats.effect.IO

/**
 * Toy optimizer that appends a fixed `Demo` to every non-frozen predictor's
 * demo list via `Optimizable.updateAll`.
 *
 * This is one of the two structurally different toy optimizers that compile
 * the same program through one derived `Optimizable` instance, exercising the
 * surface as the acceptance test.
 */
object StaticDemoInjector:

  /** The fixed demo appended to every non-frozen predictor. */
  val fixedDemo: Demo = Demo(input = ujson.Str("input"), output = ujson.Str("output"))

  /** Compile `p` by appending the fixed demo to every non-frozen predictor. */
  def compile[P](p: P)(using opt: Optimizable[P]): IO[P] =
    IO.pure(
      opt.updateAll(p, (_, state) => state.copy(demos = state.demos :+ fixedDemo))
    )
