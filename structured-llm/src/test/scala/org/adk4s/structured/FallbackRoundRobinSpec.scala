package org.adk4s.structured

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.FunSuite
import org.adk4s.structured.core.*
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Completion, CompletionOptions, Conversation, StreamedChunk}
import org.llm4s.error.LLMError
import org.llm4s.error.UnknownError
import scala.concurrent.duration.DurationInt

// spec: fallback-round-robin — Test oracle

class FallbackRoundRobinSpec extends HedgehogSuite:
  given IORuntime = IORuntime.global

  // ── Dummy LLM clients ──────────────────────────────────────────────────

  private class StubClient(val name: String, result: Either[Throwable, String]) extends LLMClient:
    override def complete(conversation: Conversation, options: CompletionOptions): Either[LLMError, Completion] =
      result match
        case Right(content) =>
          Right(Completion(
            id = "test-id",
            created = 0L,
            content = content,
            model = "test-model",
            message = org.llm4s.llmconnect.model.AssistantMessage(content)
          ))
        case Left(err) => Left(UnknownError(err.getMessage, err))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      callback: StreamedChunk => Unit
    ): Either[LLMError, Completion] =
      complete(conversation, options)
    def getContextWindow(): Int = 4096
    def getReserveCompletion(): Int = 0

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: Fallback tries clients in order until success
  // ════════════════════════════════════════════════════════════════════════

  property("fallback strategy tries clients in order until one succeeds") {
    val idxGen: Gen[Int] = Gen.int(Range.linear(0, 2))
    idxGen.forAll.map { (successIdx: Int) =>
      val clients: Vector[LLMClient] = Vector(
        new StubClient("a", if successIdx == 0 then Right("ok") else Left(new Exception("fail-a"))),
        new StubClient("b", if successIdx == 1 then Right("ok") else Left(new Exception("fail-b"))),
        new StubClient("c", if successIdx == 2 then Right("ok") else Left(new Exception("fail-c")))
      )
      val strategy: ClientStrategy = ClientStrategy.fallback(clients)
      val operation: LLMClient => IO[String] = client =>
        IO.fromEither(
          client.complete(Conversation.empty(), CompletionOptions())
            .map(_.content)
            .left.map(e => StructuredLLMError.LLMCallFailed(e, Prompt.empty))
        )
      val result: Either[Throwable, String] =
        ClientStrategy.execute[IO, String](strategy, operation, Vector("a", "b", "c")).attempt.unsafeRunSync()
      result ==== Right("ok")
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: All clients fail — returns last error
  // ════════════════════════════════════════════════════════════════════════

  test("all clients fail returns error") {
    val clients: Vector[LLMClient] = Vector(
      new StubClient("a", Left(new Exception("fail-a"))),
      new StubClient("b", Left(new Exception("fail-b")))
    )
    val strategy: ClientStrategy = ClientStrategy.fallback(clients)
    val operation: LLMClient => IO[String] = client =>
      IO.fromEither(
        client.complete(Conversation.empty(), CompletionOptions())
          .map(_.content)
          .left.map(e => StructuredLLMError.LLMCallFailed(e, Prompt.empty))
      )
    val result: Either[Throwable, String] =
      ClientStrategy.execute[IO, String](strategy, operation, Vector("a", "b")).attempt.unsafeRunSync()
    assert(result.isLeft)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: First client succeeds — no further attempts
  // ════════════════════════════════════════════════════════════════════════

  test("first client succeeds immediately") {
    val clients: Vector[LLMClient] = Vector(
      new StubClient("a", Right("success"))
    )
    val strategy: ClientStrategy = ClientStrategy.fallback(clients)
    val operation: LLMClient => IO[String] = client =>
      IO.fromEither(
        client.complete(Conversation.empty(), CompletionOptions())
          .map(_.content)
          .left.map(e => StructuredLLMError.LLMCallFailed(e, Prompt.empty))
      )
    val result: Either[Throwable, String] =
      ClientStrategy.execute[IO, String](strategy, operation, Vector("a")).attempt.unsafeRunSync()
    assertEquals(result.toOption, Some("success"))
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: short clientNames vector yields index-based names (fix #7)
  // spec: fix-llm4s-middleware-review-issues — Requirement: Fallback clients
  // are named by index, not "unknown"
  // ════════════════════════════════════════════════════════════════════════

  test("short clientNames vector yields index-based names, not repeated unknown (fix #7)") {
    // spec: fix-llm4s-middleware-review-issues — Scenario: short clientNames
    // When clientNames is shorter than clients, the unnamed clients must be
    // named by index (client-1, client-2, ...) so AttemptRecord names are
    // unambiguous. Previously they were all labelled "unknown".
    val clients: Vector[LLMClient] = Vector(
      new StubClient("a", Left(new Exception("fail-a"))),
      new StubClient("b", Left(new Exception("fail-b"))),
      new StubClient("c", Left(new Exception("fail-c")))
    )
    val strategy: ClientStrategy = ClientStrategy.fallback(clients)
    val operation: LLMClient => IO[String] = client =>
      IO.fromEither(
        client.complete(Conversation.empty(), CompletionOptions())
          .map(_.content)
          .left.map(e => StructuredLLMError.LLMCallFailed(e, Prompt.empty))
      )
    val result: Either[Throwable, String] =
      ClientStrategy.execute[IO, String](strategy, operation, Vector("only")).attempt.unsafeRunSync()
    result match
      case Left(e: StructuredLLMError.Enriched) =>
        val names: Seq[String] = e.attempts.map(_.client)
        assertEquals(names(0), "only")
        assertEquals(names(1), "client-1")
        assertEquals(names(2), "client-2")
      case Left(other) =>
        fail(s"Expected Enriched error with attempt records, got ${other.getClass.getName}: $other")
      case Right(value) =>
        fail(s"Expected error, got $value")
  }
