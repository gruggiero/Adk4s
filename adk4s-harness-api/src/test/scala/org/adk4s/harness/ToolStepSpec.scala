package org.adk4s.harness

import org.adk4s.harness.{ HarnessState, ToolCallCtx, ToolCallOut, ToolStep }
import org.adk4s.core.tools.{ ToolInput, ToolOutput }
import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite

/**
 * Tests for `ToolStep.passthrough` — the lift that converts a
 * state-oblivious tool endpoint into a state-threading tool step.
 *
 * NOTE: `HedgehogSuite` extends `HedgehogAssertions` which overrides
 * `assertEquals`/`assert`/`fail` to return `hedgehog.Result` instead of
 * throwing. In `test(...)` blocks (non-property tests), these return values
 * are silently discarded — the assertions do NOT fire. Scenario tests MUST
 * use `withMunitAssertions { a => a.assertEquals(...) }` to get real munit
 * assertions that throw on failure. Hedgehog `property(...)` blocks use
 * `====` and `and` which return `Result` checked by the property harness.
 *
 * spec: agent-middleware — Requirement: ToolStep.passthrough lifts state-oblivious tool endpoints
 */
class ToolStepSpec extends HedgehogSuite:

  // ── Scenario tests ──────────────────────────────────────────────────────

  test("passthrough preserves state exactly"):
    withMunitAssertions { a =>
      val ep: Kleisli[IO, ToolInput, ToolOutput] = Kleisli { input =>
        IO.pure(ToolOutput(input.name, "ok", input.callId, false))
      }
      val input: ToolInput    = ToolInput("test", "{}", "call-1")
      val state: HarnessState = HarnessState.empty
      val result: ToolCallOut = ToolStep.passthrough[IO](ep).run(ToolCallCtx(input, state)).unsafeRunSync()
      a.assertEquals(result.output.name, "test")
      a.assertEquals(result.state, state)
    }

  test("passthrough with a failing endpoint propagates error"):
    withMunitAssertions { a =>
      val ep: Kleisli[IO, ToolInput, ToolOutput] = Kleisli(_ => IO.raiseError(new RuntimeException("tool failed")))
      val input: ToolInput                       = ToolInput("test", "{}", "call-1")
      val state: HarnessState                    = HarnessState.empty
      val result: IO[ToolCallOut]                = ToolStep.passthrough[IO](ep).run(ToolCallCtx(input, state))
      try
        result.unsafeRunSync()
        a.fail("Expected error to propagate")
      catch
        case e: RuntimeException =>
          a.assertEquals(e.getMessage, "tool failed")
    }

  test("passthrough output equals endpoint applied to input"):
    withMunitAssertions { a =>
      val ep: Kleisli[IO, ToolInput, ToolOutput] = Kleisli { input =>
        IO.pure(ToolOutput(input.name, "result-" + input.arguments, input.callId, false))
      }
      val input: ToolInput        = ToolInput("calc", "{\"x\":1}", "call-42")
      val state: HarnessState     = HarnessState.empty
      val stepResult: ToolCallOut = ToolStep.passthrough[IO](ep).run(ToolCallCtx(input, state)).unsafeRunSync()
      val epResult: ToolOutput    = ep.run(input).unsafeRunSync()
      a.assertEquals(stepResult.output, epResult)
    }

  // ── Hedgehog property: toolstep-passthrough-isomorphism ─────────────────

  property("toolstep-passthrough-isomorphism — passthrough(ep)(ctx) == ep(input).map(ToolCallOut(_, state))"):
    for
      name      <- Gen.string(Gen.alpha, Range.linear(3, 10)).forAll
      args      <- Gen.string(Gen.alphaNum, Range.linear(1, 30)).forAll
      callId    <- Gen.string(Gen.alphaNum, Range.linear(1, 36)).forAll
      resultStr <- Gen.string(Gen.alpha, Range.linear(1, 20)).forAll
      isError   <- Gen.boolean.forAll
    yield
      val input: ToolInput                       = ToolInput(name, args, callId)
      val output: ToolOutput                     = ToolOutput(name, resultStr, callId, isError)
      val state: HarnessState                    = HarnessState.empty
      val ep: Kleisli[IO, ToolInput, ToolOutput] = Kleisli(_ => IO.pure(output))
      val step: ToolStep[IO]                     = ToolStep.passthrough[IO](ep)
      val result: ToolCallOut                    = step.run(ToolCallCtx(input, state)).unsafeRunSync()
      result ==== ToolCallOut(output, state)
