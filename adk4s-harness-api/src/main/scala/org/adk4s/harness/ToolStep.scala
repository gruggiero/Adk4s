package org.adk4s.harness

import cats.data.Kleisli
import cats.Functor
import cats.syntax.functor.toFunctorOps
import org.adk4s.core.tools.{ ToolInput, ToolOutput }

/**
 * The tool step: a function from `ToolCallCtx` to an effectful
 * `ToolCallOut`. The wrap-tool-call hook accepts and returns a tool step,
 * composing as a Kleisli endomorphism.
 *
 * spec: agent-middleware — Requirement: ToolStep is a Kleisli from ToolCallCtx to ToolCallOut
 */
type ToolStep[F[_]] = Kleisli[F, ToolCallCtx, ToolCallOut]

object ToolStep:
  /**
   * Lifts a state-oblivious tool endpoint (`Kleisli[F, ToolInput,
   * ToolOutput]`) into a state-threading tool step by threading the
   * context's state through unchanged.
   *
   * At `F = IO`, the state-oblivious endpoint is definitionally the
   * existing `ToolEndpoint`, so the entire existing `ToolMiddleware`
   * combinator set (logging, timing, retry, validation) composes under
   * the new abstraction unchanged.
   *
   * spec: agent-middleware — Requirement: ToolStep.passthrough lifts state-oblivious tool endpoints
   */
  def passthrough[F[_]: Functor](
    ep: Kleisli[F, ToolInput, ToolOutput]
  ): ToolStep[F] =
    Kleisli((ctx: ToolCallCtx) => ep.run(ctx.input).map(out => ToolCallOut(out, ctx.state)))
