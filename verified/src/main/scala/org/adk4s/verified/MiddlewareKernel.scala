package org.adk4s.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of `ToolStep.passthrough` state-preservation.
 *
 * This model mirrors the `passthrough` lift that converts a state-oblivious
 * tool endpoint into a state-threading tool step. The shipped code uses
 * `cats.data.Kleisli` and `F[_]` effect types that Stainless cannot model,
 * and the build's Scala version (3.8.4) differs from the Stainless frontend
 * (3.7.2). The *algorithm* under `passthrough` is a pure state-threading
 * lift, and it survives reduction to observable effect.
 *
 * Abstraction:
 *   - A `ToolInput` becomes a `ToolInputModel` (name, args, callId).
 *   - A `ToolOutput` becomes a `ToolOutputModel` (name, result, callId, isError).
 *   - A harness state becomes a `BigInt` identity (the state is opaque to
 *     the lift; only identity matters for the isomorphism).
 *   - A tool-call context becomes a pair `(ToolInputModel, BigInt)`.
 *   - A tool-call output becomes a pair `(ToolOutputModel, BigInt)`.
 *   - The state-oblivious endpoint becomes a total function
 *     `ToolInputModel => ToolOutputModel` (the effect is trivial — `pure` —
 *     in the model).
 *
 * spec: agent-middleware — Formal Contracts (Ring 6)
 */
case class ToolInputModel(name: String, args: String, callId: String)
case class ToolOutputModel(name: String, result: String, callId: String, isError: Boolean)

object MiddlewareKernel:

  /**
   * The `passthrough` lift: applies the endpoint to the context's input and
   * threads the context's state through unchanged.
   *
   * Contract: the output's second component (state) equals the context's
   * second component (state), and the output's first component equals `ep`
   * applied to the context's first component (input).
   */
  def passthrough(
    ep: ToolInputModel => ToolOutputModel,
    ctx: (ToolInputModel, BigInt)
  ): (ToolOutputModel, BigInt) =
    (ep(ctx._1), ctx._2)

  // ── Lemma functions (standalone boolean properties for bridge spec) ────

  /** State preservation: the output state equals the context state. */
  def statePreserved(
    ep: ToolInputModel => ToolOutputModel,
    ctx: (ToolInputModel, BigInt)
  ): Boolean =
    passthrough(ep, ctx)._2 == ctx._2

  /** Endpoint applied: the output value equals `ep` applied to the input. */
  def endpointApplied(
    ep: ToolInputModel => ToolOutputModel,
    ctx: (ToolInputModel, BigInt)
  ): Boolean =
    passthrough(ep, ctx)._1 == ep(ctx._1)

  /** Combined contract: both state preservation and endpoint application. */
  def passthroughContract(
    ep: ToolInputModel => ToolOutputModel,
    ctx: (ToolInputModel, BigInt)
  ): Boolean =
    statePreserved(ep, ctx) && endpointApplied(ep, ctx)
