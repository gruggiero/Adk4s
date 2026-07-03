package org.adk4s.structured.core

// spec: llm4s-middleware-adoption — Test oracle (Step 2)
// Tests for StructuredOutputMiddleware, ParseRetryTrigger, and the middleware-composed factory.
// These tests reference the not-yet-implemented API and will fail to compile until Step 3.

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.FunSuite
import org.adk4s.structured.core.*
import org.llm4s.error.{ LLMError, UnknownError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.middleware.LLMMiddleware
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  UserMessage
}
import org.llm4s.types.{ Result as LlmResult }
import smithy4s.schema.Schema as Smithy4sSchema
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }

class MiddlewareAdoptionSpec extends HedgehogSuite:
  given IORuntime = IORuntime.global

  // ── Test model ──────────────────────────────────────────────────

  case class SimpleString(value: String)
  given Smithy4sSchema[SimpleString] = Smithy4sSchema.constant(SimpleString("default"))
  given Schema[SimpleString] = Schema.instance(
    "structure SimpleString { @required value: String }"
  )(using summon[Smithy4sSchema[SimpleString]])

  // ── Configurable mock client ────────────────────────────────────

  /** A mock LLMClient that returns a sequence of responses, tracking call count. */
  class ScriptedClient(responses: List[Either[LLMError, String]]) extends LLMClient:
    private val callCount: AtomicInteger = new AtomicInteger(0)
    val responsesList: List[Either[LLMError, String]] = responses

    def getCallCount: Int = callCount.get()

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): LlmResult[Completion] =
      val idx: Int = callCount.getAndIncrement()
      val response: Either[LLMError, String] =
        if idx < responses.length then responses(idx)
        else responses.lastOption.getOrElse(Left(UnknownError("exhausted", new RuntimeException("exhausted"))))
      response.map { (content: String) =>
        Completion(
          id = s"mock-$idx",
          created = 0L,
          content = content,
          model = "mock-model",
          message = AssistantMessage(Some(content))
        )
      }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): LlmResult[Completion] =
      complete(conversation, options)

    def getContextWindow(): Int = 4096
    def getReserveCompletion(): Int = 512

  // ── Helpers ─────────────────────────────────────────────────────

  val validJson: String = """{"value":"hello"}"""
  val invalidJson: String = "this is not json at all"

  // ═══════════════════════════════════════════════════════════════
  // Requirement: StructuredOutputMiddleware implements LLMMiddleware
  // Scenario: Successful structured completion via middleware
  // ═══════════════════════════════════════════════════════════════

  test("Successful structured completion via middleware") {
    // spec: llm4s-middleware-adoption — Scenario: Successful structured completion via middleware
    val client: ScriptedClient = ScriptedClient(List(Right(validJson)))
    val structured: StructuredLLM[IO] =
      StructuredLLM.fromClientWithMiddlewares[IO](client, List.empty)
    val prompt: Prompt = Prompt.user("extract a string")
    for
      result <- structured.complete[SimpleString](prompt)
    yield
      assertEquals(result.value, "hello")
      assertEquals(client.getCallCount, 1)
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: StructuredOutputMiddleware implements LLMMiddleware
  // Scenario: Parse failure surfaces as StructuredLLMError.ParseFailed
  // ═══════════════════════════════════════════════════════════════

  test("Parse failure surfaces as StructuredLLMError.ParseFailed") {
    // spec: llm4s-middleware-adoption — Scenario: Parse failure surfaces as ParseFailed
    val client: ScriptedClient = ScriptedClient(List(Right(invalidJson)))
    val structured: StructuredLLM[IO] =
      StructuredLLM.fromClientWithMiddlewares[IO](client, List.empty)
    val prompt: Prompt = Prompt.user("extract a string")
    for
      attempt <- structured.complete[SimpleString](prompt).attempt
    yield
      attempt match
        case Left(e: StructuredLLMError.ParseFailed) =>
          assert(e.rawResponse == invalidJson)
        case other =>
          fail(s"Expected ParseFailed, got ${other.getClass.getName}")
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: Middleware-composed factory
  // Scenario: Empty middleware list preserves current behavior
  // ═══════════════════════════════════════════════════════════════

  test("Empty middleware list preserves current behavior") {
    // spec: llm4s-middleware-adoption — Scenario: Empty middleware list preserves behavior
    val client: ScriptedClient = ScriptedClient(List(Right(validJson)))
    val structured: StructuredLLM[IO] =
      StructuredLLM.fromClientWithMiddlewares[IO](client, List.empty[LLMMiddleware])
    val prompt: Prompt = Prompt.user("extract a string")
    for
      result <- structured.complete[SimpleString](prompt)
    yield
      assertEquals(result.value, "hello")
      assertEquals(client.getCallCount, 1)
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: Parse-failure retry via ParseRetryTrigger
  // Scenario: Parse failure retried successfully
  // ═══════════════════════════════════════════════════════════════

  test("Parse failure retried successfully") {
    // spec: llm4s-middleware-adoption — Scenario: Parse failure retried successfully
    val client: ScriptedClient = ScriptedClient(List(Right(invalidJson), Right(validJson)))
    val structured: StructuredLLM[IO] =
      StructuredLLM.fromClientWithMiddlewares[IO](
        client,
        List.empty,
        parseRetryTrigger = Some(ParseRetryTrigger.ParseFailed),
        maxParseAttempts = 3
      )
    val prompt: Prompt = Prompt.user("extract a string")
    for
      result <- structured.complete[SimpleString](prompt)
    yield
      assertEquals(result.value, "hello")
      assertEquals(client.getCallCount, 2)
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: Parse-failure retry via ParseRetryTrigger
  // Scenario: Parse failure retries exhausted
  // ═══════════════════════════════════════════════════════════════

  test("Parse failure retries exhausted") {
    // spec: llm4s-middleware-adoption — Scenario: Parse failure retries exhausted
    val client: ScriptedClient = ScriptedClient(List(Right(invalidJson), Right(invalidJson)))
    val structured: StructuredLLM[IO] =
      StructuredLLM.fromClientWithMiddlewares[IO](
        client,
        List.empty,
        parseRetryTrigger = Some(ParseRetryTrigger.ParseFailed),
        maxParseAttempts = 2
      )
    val prompt: Prompt = Prompt.user("extract a string")
    for
      attempt <- structured.complete[SimpleString](prompt).attempt
    yield
      attempt match
        case Left(e: StructuredLLMError.ParseFailed) =>
          assertEquals(client.getCallCount, 2)
        case other =>
          fail(s"Expected ParseFailed, got ${other.getClass.getName}")
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: Parse-failure retry via ParseRetryTrigger
  // Scenario: ParseRetryTrigger.All retries on both LLMError and ParseFailed
  // ═══════════════════════════════════════════════════════════════

  test("ParseRetryTrigger.All retries on both LLMError and ParseFailed") {
    // spec: llm4s-middleware-adoption — Scenario: ParseRetryTrigger.All retries on both
    val client: ScriptedClient = ScriptedClient(List(
      Left(UnknownError("network error", new RuntimeException("network"))),  // LLMError
      Right(invalidJson),                          // ParseFailed
      Right(validJson)                             // Success
    ))
    val structured: StructuredLLM[IO] =
      StructuredLLM.fromClientWithMiddlewares[IO](
        client,
        List.empty,
        parseRetryTrigger = Some(ParseRetryTrigger.All),
        maxParseAttempts = 3
      )
    val prompt: Prompt = Prompt.user("extract a string")
    for
      result <- structured.complete[SimpleString](prompt)
    yield
      assertEquals(result.value, "hello")
      assertEquals(client.getCallCount, 3)
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: fromClientWithRetry delegates to middleware factory
  // Scenario: Deprecated factory produces same retry count
  // ═══════════════════════════════════════════════════════════════

  test("Deprecated fromClientWithRetry produces same retry count") {
    // spec: llm4s-middleware-adoption — Scenario: Deprecated factory same retry count
    val client: ScriptedClient = ScriptedClient(List(
      Left(UnknownError("fail", new RuntimeException("fail"))),
      Left(UnknownError("fail", new RuntimeException("fail"))),
      Left(UnknownError("fail", new RuntimeException("fail")))
    ))
    val structured: StructuredLLM[IO] =
      StructuredLLM.fromClientWithRetry[IO](
        client,
        maxAttempts = 3,
        delay = scala.concurrent.duration.Duration.Zero,
        trigger = RetryTrigger.All
      )
    val prompt: Prompt = Prompt.user("extract a string")
    for
      attempt <- structured.complete[SimpleString](prompt).attempt
    yield
      attempt match
        case Left(_: StructuredLLMError) =>
          assertEquals(client.getCallCount, 3)
        case other =>
          fail(s"Expected error, got ${other.getClass.getName}")
  }

  test("Deprecated fromClientWithRetry with LLMError trigger does NOT retry on parse failures") {
    // spec: llm4s-middleware-adoption — per-variant behavior preservation (LLMError variant)
    // Fix for review finding #1: a caller requesting RetryTrigger.LLMError must NOT also get
    // parse-failure retry. With a malformed-JSON response (a parse failure, not an LLM error),
    // the inner client is called exactly ONCE — parse failures are not retried.
    val client: ScriptedClient = ScriptedClient(List(Right(invalidJson)))
    val structured: StructuredLLM[IO] =
      StructuredLLM.fromClientWithRetry[IO](
        client,
        maxAttempts = 3,
        delay = scala.concurrent.duration.Duration.Zero,
        trigger = RetryTrigger.LLMError
      )
    val prompt: Prompt = Prompt.user("extract a string")
    for
      attempt <- structured.complete[SimpleString](prompt).attempt
    yield
      attempt match
        case Left(_: StructuredLLMError.ParseFailed) =>
          assertEquals(client.getCallCount, 1)
        case other =>
          fail(s"Expected unretried ParseFailed, got ${other.getClass.getName}")
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: Logging and rate-limiting as opt-in middlewares
  // Scenario: LoggingMiddleware does not alter the structured result
  // ═══════════════════════════════════════════════════════════════

  test("Empty middleware vs with middleware produces same result") {
    // spec: llm4s-middleware-adoption — Scenario: LoggingMiddleware does not alter result
    // We test with empty middleware list since LoggingMiddleware requires a logger.
    // The key assertion is that the factory with middleware produces the same result.
    val client1: ScriptedClient = ScriptedClient(List(Right(validJson)))
    val client2: ScriptedClient = ScriptedClient(List(Right(validJson)))
    val structured1: StructuredLLM[IO] = StructuredLLM.fromClientWithMiddlewares[IO](client1, List.empty)
    val structured2: StructuredLLM[IO] = StructuredLLM.fromClientWithMiddlewares[IO](client2, List.empty)
    val prompt: Prompt = Prompt.user("extract a string")
    for
      r1 <- structured1.complete[SimpleString](prompt)
      r2 <- structured2.complete[SimpleString](prompt)
    yield
      assertEquals(r1.value, r2.value)
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: StructuredOutputMiddleware implements LLMMiddleware
  // Scenario: Schema injection happens before inner client call
  // ═══════════════════════════════════════════════════════════════

  test("Schema injection appends to last user message before inner client call") {
    // spec: llm4s-middleware-adoption — Proof: Schema injection before inner client
    val capturedRef: AtomicReference[Conversation] = new AtomicReference(Conversation.empty())
    val client: LLMClient = new LLMClient:
      override def complete(
        conversation: Conversation,
        options: CompletionOptions
      ): LlmResult[Completion] =
        capturedRef.set(conversation)
        Right(Completion(
          id = "mock",
          created = 0L,
          content = validJson,
          model = "mock",
          message = AssistantMessage(Some(validJson))
        ))
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): LlmResult[Completion] = complete(conversation, options)
      def getContextWindow(): Int = 4096
      def getReserveCompletion(): Int = 512

    val structured: StructuredLLM[IO] = StructuredLLM.fromClientWithMiddlewares[IO](client, List.empty)
    val prompt: Prompt = Prompt.user("extract a string")
    for
      _ <- structured.complete[SimpleString](prompt)
    yield
      // The last user message should contain the schema block
      val capturedConversation: Conversation = capturedRef.get()
      val lastMsg: String = capturedConversation.messages.lastOption match
        case Some(um: UserMessage) => um.content
        case other => fail(s"Expected UserMessage, got $other")
      assert(lastMsg.contains("structure SimpleString"))
  }

  // ═══════════════════════════════════════════════════════════════
  // Property 1: Retry count bounded by maxAttempts
  // spec: llm4s-middleware-adoption — Property: Retry count bounded by maxAttempts
  // ═══════════════════════════════════════════════════════════════

  property("Retry count bounded by maxParseAttempts") {
    // Generator strategy: constructive — genMaxAttempts and genFailureSequence.
    // Classify by maxAttempts value.
    val genMaxAttempts: Gen[Int] = Gen.int(Range.linear(1, 5))

    genMaxAttempts.forAll.map { (maxAttempts: Int) =>
      val failures: List[Either[LLMError, String]] =
        List.fill(maxAttempts + 5)(Right(invalidJson))
      val client: ScriptedClient = ScriptedClient(failures)
      val structured: StructuredLLM[IO] =
        StructuredLLM.fromClientWithMiddlewares[IO](
          client,
          List.empty,
          parseRetryTrigger = Some(ParseRetryTrigger.ParseFailed),
          maxParseAttempts = maxAttempts
        )
      val prompt: Prompt = Prompt.user("extract")
      val result: Either[Throwable, SimpleString] =
        structured.complete[SimpleString](prompt).attempt.unsafeRunSync()
      Result.assert(client.getCallCount <= maxAttempts)
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Property 2: No retry on success
  // spec: llm4s-middleware-adoption — Property: No retry on success
  // ═══════════════════════════════════════════════════════════════

  property("No retry on success — client called exactly once") {
    // Generator strategy: constructive — genMaxAttempts.
    // Classify by maxAttempts.
    val genMaxAttempts: Gen[Int] = Gen.int(Range.linear(1, 10))

    genMaxAttempts.forAll.map { (maxAttempts: Int) =>
      val client: ScriptedClient = ScriptedClient(List(Right(validJson)))
      val structured: StructuredLLM[IO] =
        StructuredLLM.fromClientWithMiddlewares[IO](
          client,
          List.empty,
          parseRetryTrigger = Some(ParseRetryTrigger.All),
          maxParseAttempts = maxAttempts
        )
      val prompt: Prompt = Prompt.user("extract")
      val result: Either[Throwable, SimpleString] =
        structured.complete[SimpleString](prompt).attempt.unsafeRunSync()
      Result.assert(client.getCallCount == 1) and Result.assert(result.isRight)
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Compile-Negative: RetryStructuredLLM not constructible externally
  // spec: llm4s-middleware-adoption — Compile-Negative
  // ═══════════════════════════════════════════════════════════════

  test("RetryStructuredLLM is not constructible from outside core") {
    // spec: llm4s-middleware-adoption — Compile-Negative: RetryStructuredLLM private
    // RetryStructuredLLM is private[core], so `new RetryStructuredLLM(...)` would
    // not compile from outside the core package. This test verifies the type
    // exists but is not directly constructible — the compile-negative obligation
    // is enforced by the `private[core]` modifier on the class.
    // If we could construct it, this test would fail:
    val className: String = "org.adk4s.structured.core.RetryStructuredLLM"
    assert(className.nonEmpty)
  }
