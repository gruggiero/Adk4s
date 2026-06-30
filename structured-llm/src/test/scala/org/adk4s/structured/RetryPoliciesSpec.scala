package org.adk4s.structured

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.FunSuite
import org.adk4s.structured.core.*
import org.adk4s.structured.core.RetryTrigger
import org.llm4s.error.UnknownError
import org.llm4s.llmconnect.model.Conversation
import scala.concurrent.duration.DurationInt

// spec: retry-policies — Test oracle (Step 2)
// Tests written from the spec BEFORE implementation.

class RetryPoliciesSpec extends HedgehogSuite:
  given IORuntime = IORuntime.global

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: Retry count never exceeds maxAttempts
  // spec: retry-policies — Property: retry count ≤ maxAttempts
  // ════════════════════════════════════════════════════════════════════════

  property("total attempts never exceed maxAttempts for always-failing operation") {
    val maxAttemptsGen: Gen[Int] = Gen.int(Range.linear(1, 5))
    maxAttemptsGen.forAll.map { (maxAttempts: Int) =>
      val counter: IO[Int] = IO.ref(0).flatMap { ref =>
        val operation: IO[String] = ref.update(_ + 1) *>
          IO.raiseError[String](StructuredLLMError.ParseFailed(List.empty, "fail"))
        Retry.withRetry[IO, String](maxAttempts, 0.seconds, RetryTrigger.All)(operation)
          .attempt *> ref.get
      }
      val attempts: Int = counter.unsafeRunSync()
      attempts ==== maxAttempts
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 2: Success on first attempt does not retry
  // spec: retry-policies — Property: no retry on success
  // ════════════════════════════════════════════════════════════════════════

  property("successful first attempt does not retry") {
    val successGen: Gen[String] = Gen.string(Gen.char('a', 'z'), Range.linear(1, 10))
    successGen.forAll.map { (value: String) =>
      val counter: IO[Int] = IO.ref(0).flatMap { ref =>
        val operation: IO[String] = ref.update(_ + 1) *> IO.pure(value)
        Retry.withRetry[IO, String](5, 0.seconds, RetryTrigger.All)(operation)
          .map(_ => ()) *> ref.get
      }
      val attempts: Int = counter.unsafeRunSync()
      (attempts ==== 1)
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Parse failure triggers retry, second attempt succeeds
  // ════════════════════════════════════════════════════════════════════════

  test("parse failure triggers retry and second attempt succeeds") {
    val counter: IO[Int] = IO.ref(0).flatMap { ref =>
      val operation: IO[String] = ref.update(_ + 1) *> ref.get.flatMap {
        case 1 => IO.raiseError[String](StructuredLLMError.ParseFailed(List.empty, "fail"))
        case _ => IO.pure("success")
      }
      Retry.withRetry[IO, String](3, 0.seconds, RetryTrigger.All)(operation).map(_ => ()) *> ref.get
    }
    val attempts: Int = counter.unsafeRunSync()
    assertEquals(attempts, 2)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Max retries exhausted
  // ════════════════════════════════════════════════════════════════════════

  test("max retries exhausted returns last error") {
    val operation: IO[String] =
      IO.raiseError[String](StructuredLLMError.ParseFailed(List.empty, "always fail"))
    val result: Either[Throwable, String] =
      Retry.withRetry[IO, String](2, 0.seconds, RetryTrigger.ParseFailure)(operation).attempt.unsafeRunSync()
    assert(result.isLeft)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: LLM error — no retry when trigger is ParseFailure only
  // ════════════════════════════════════════════════════════════════════════

  test("LLM error does not retry when trigger is ParseFailure only") {
    val dummyError: org.llm4s.error.LLMError = UnknownError("test error", new Exception("test"))
    val dummyPrompt: Prompt = Prompt.empty
    val counter: IO[Int] = IO.ref(0).flatMap { ref =>
      val operation: IO[String] = ref.update(_ + 1) *>
        IO.raiseError[String](StructuredLLMError.LLMCallFailed(dummyError, dummyPrompt))
      Retry.withRetry[IO, String](3, 0.seconds, RetryTrigger.ParseFailure)(operation)
        .attempt *> ref.get
    }
    val attempts: Int = counter.unsafeRunSync()
    assertEquals(attempts, 1)
  }
