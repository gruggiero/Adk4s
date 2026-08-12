package org.adk4s.harness

import org.adk4s.harness.{ HarnessState, ToolCallCtx, ToolCallOut, ToolStep }
import org.adk4s.core.tools.{ ToolInput, ToolOutput }
import org.adk4s.verified.{ MiddlewareKernel, ToolInputModel, ToolOutputModel }
import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite

/**
 * Ring 6 bridge test — runs the real `ToolStep.passthrough` and the
 * `MiddlewareKernel` model on the SAME generated inputs and asserts they
 * agree on the proven invariant:
 *   1. the real `passthrough(ep).run(ctx)` output state equals the context
 *      state (state preservation);
 *   2. the real `passthrough(ep).run(ctx)` output value equals `ep.run(ctx.input)`
 *      (endpoint applied correctly).
 *
 * spec: agent-middleware — Formal Contracts (Ring 6) — Bridge
 */
class MiddlewareKernelBridgeSpec extends HedgehogSuite:

  property("bridge-state-preservation — real passthrough preserves state like the model"):
    for
      name      <- Gen.string(Gen.alpha, Range.linear(3, 10)).forAll
      args      <- Gen.string(Gen.alphaNum, Range.linear(1, 30)).forAll
      callId    <- Gen.string(Gen.alphaNum, Range.linear(1, 36)).forAll
      resultStr <- Gen.string(Gen.alpha, Range.linear(1, 20)).forAll
      isError   <- Gen.boolean.forAll
      stateId   <- Gen.int(Range.linear(0, 1000)).forAll
    yield
      // Real types
      val input: ToolInput                       = ToolInput(name, args, callId)
      val output: ToolOutput                     = ToolOutput(name, resultStr, callId, isError)
      val state: HarnessState                    = HarnessState.empty
      val ep: Kleisli[IO, ToolInput, ToolOutput] = Kleisli(_ => IO.pure(output))
      val realResult: ToolCallOut = ToolStep.passthrough[IO](ep).run(ToolCallCtx(input, state)).unsafeRunSync()

      // Model types
      val modelInput: ToolInputModel                 = ToolInputModel(name, args, callId)
      val modelOutput: ToolOutputModel               = ToolOutputModel(name, resultStr, callId, isError)
      val modelEp: ToolInputModel => ToolOutputModel = _ => modelOutput
      val modelCtx: (ToolInputModel, BigInt)         = (modelInput, BigInt(stateId))
      val modelResult: (ToolOutputModel, BigInt)     = MiddlewareKernel.passthrough(modelEp, modelCtx)

      // Assert state preservation (both preserve the context state)
      val realStatePreserved: Boolean  = realResult.state == state
      val modelStatePreserved: Boolean = MiddlewareKernel.statePreserved(modelEp, modelCtx)
      (realStatePreserved ==== true).and(modelStatePreserved ==== true)

  property("bridge-endpoint-applied — real passthrough applies endpoint like the model"):
    for
      name      <- Gen.string(Gen.alpha, Range.linear(3, 10)).forAll
      args      <- Gen.string(Gen.alphaNum, Range.linear(1, 30)).forAll
      callId    <- Gen.string(Gen.alphaNum, Range.linear(1, 36)).forAll
      resultStr <- Gen.string(Gen.alpha, Range.linear(1, 20)).forAll
      isError   <- Gen.boolean.forAll
    yield
      // Real types
      val input: ToolInput                       = ToolInput(name, args, callId)
      val output: ToolOutput                     = ToolOutput(name, resultStr, callId, isError)
      val state: HarnessState                    = HarnessState.empty
      val ep: Kleisli[IO, ToolInput, ToolOutput] = Kleisli(_ => IO.pure(output))
      val realResult: ToolCallOut = ToolStep.passthrough[IO](ep).run(ToolCallCtx(input, state)).unsafeRunSync()

      // Model types
      val modelInput: ToolInputModel                 = ToolInputModel(name, args, callId)
      val modelOutput: ToolOutputModel               = ToolOutputModel(name, resultStr, callId, isError)
      val modelEp: ToolInputModel => ToolOutputModel = _ => modelOutput
      val modelCtx: (ToolInputModel, BigInt)         = (modelInput, BigInt(0))
      val modelResult: (ToolOutputModel, BigInt)     = MiddlewareKernel.passthrough(modelEp, modelCtx)

      // Assert endpoint applied (both produce the endpoint's output)
      val realEndpointApplied: Boolean  = realResult.output == output
      val modelEndpointApplied: Boolean = MiddlewareKernel.endpointApplied(modelEp, modelCtx)
      (realEndpointApplied ==== true).and(modelEndpointApplied ==== true)

  property("bridge-full-contract — both real and model satisfy the full passthrough contract"):
    for
      name      <- Gen.string(Gen.alpha, Range.linear(3, 10)).forAll
      args      <- Gen.string(Gen.alphaNum, Range.linear(1, 30)).forAll
      callId    <- Gen.string(Gen.alphaNum, Range.linear(1, 36)).forAll
      resultStr <- Gen.string(Gen.alpha, Range.linear(1, 20)).forAll
      isError   <- Gen.boolean.forAll
      stateId   <- Gen.int(Range.linear(0, 1000)).forAll
    yield
      // Real
      val input: ToolInput                       = ToolInput(name, args, callId)
      val output: ToolOutput                     = ToolOutput(name, resultStr, callId, isError)
      val state: HarnessState                    = HarnessState.empty
      val ep: Kleisli[IO, ToolInput, ToolOutput] = Kleisli(_ => IO.pure(output))
      val realResult: ToolCallOut = ToolStep.passthrough[IO](ep).run(ToolCallCtx(input, state)).unsafeRunSync()
      val realContract: Boolean   = realResult.state == state && realResult.output == output

      // Model
      val modelInput: ToolInputModel                 = ToolInputModel(name, args, callId)
      val modelOutput: ToolOutputModel               = ToolOutputModel(name, resultStr, callId, isError)
      val modelEp: ToolInputModel => ToolOutputModel = _ => modelOutput
      val modelCtx: (ToolInputModel, BigInt)         = (modelInput, BigInt(stateId))
      val modelContract: Boolean                     = MiddlewareKernel.passthroughContract(modelEp, modelCtx)

      (realContract ==== true).and(modelContract ==== true)
