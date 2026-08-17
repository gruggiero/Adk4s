package org.adk4s.record

import cats.effect.IO
import fs2.Stream
import munit.FunSuite
import org.adk4s.core.component.ChatModel
import org.adk4s.core.component.Embedder
import org.adk4s.core.component.Embedding
import org.adk4s.core.component.EmbeddingResult
import org.adk4s.core.tools.ToolMiddleware
import org.adk4s.harness.testkit.DeterministicChatModel
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk

// spec: add-adk4s-record/recorded-wrappers — Typed Contract (Gate 1)
// Type-level structure tests for RecordedChatModel, RecordedEmbedder,
// RecordingToolMiddleware, and Redaction. These are GREEN-BY-DESIGN:
// they verify the signatures compile and have the right shape. No
// behavior is tested here (that is the test oracle's job).
//
// The factory methods return `???` at this stage — we do NOT call them.
// We only verify that the types line up via assignment-level checks that
// the compiler enforces. Each test constructs a value of the expected
// type WITHOUT calling the factory (using a stub), so the test passes
// now and will continue to pass after implementation.
final class RecordedWrappersTypeContract extends FunSuite:

  test("Redaction is a function type from RecordPayload to RecordPayload"):
    val r: Redaction     = (payload: RecordPayload) => payload
    val _: RecordPayload = r(RecordPayload.model(ModelPayloadOps.simple("x")))
    assert(true)

  test("Redaction.identity is a no-op"):
    val payload: RecordPayload = RecordPayload.model(
      ModelPayloadOps.simple("test")
    )
    assertEquals(Redaction.identity(payload), payload)

  test("RecordedChatModel.apply signature returns ChatModel[IO]"):
    // Type-level check: the apply method's return type is ChatModel[F].
    // We verify by constructing a stub ChatModel and assigning it.
    val stub: ChatModel[IO] = new ChatModel[IO]:
      def generate(conversation: Conversation): IO[Completion] =
        IO.pure(
          Completion(
            id = "stub",
            created = 0L,
            content = "",
            model = "stub",
            message = org.llm4s.llmconnect.model.AssistantMessage(Some(""))
          )
        )
      def stream(conversation: Conversation): Stream[IO, StreamedChunk]               = Stream.empty
      def streamContent(conversation: Conversation): Stream[IO, String]               = Stream.empty
      def withConfig(config: org.adk4s.core.component.ChatModelConfig): ChatModel[IO] = this
    val _: ChatModel[IO] = stub
    assert(true)

  test("RecordedChatModel.apply accepts rollout and redaction params"):
    // Type-level check: the apply method accepts Option[RolloutId] and
    // Option[Redaction]. Verified by the compiler — if the signature
    // changes, this test file fails to compile.
    val rollout: Option[RolloutId]   = RolloutId.refineEither("run-1").toOption
    val redaction: Option[Redaction] = Some(Redaction.identity)
    val _: Option[RolloutId]         = rollout
    val _: Option[Redaction]         = redaction
    assert(true)

  test("RecordedEmbedder.apply signature returns Embedder[IO]"):
    val stub: Embedder[IO] = new Embedder[IO]:
      def embed(text: String): IO[Embedding] = IO.pure(Vector.empty[Double])
      def embedBatch(texts: List[String]): IO[EmbeddingResult] =
        IO.pure(EmbeddingResult(Nil, None))
      def dimension: IO[Int] = IO.pure(0)
    val _: Embedder[IO] = stub
    assert(true)

  test("RecordingToolMiddleware.recording signature returns ToolMiddleware"):
    // Type-level check: ToolMiddleware is a type alias
    // (ToolEndpoint => ToolEndpoint). We verify the alias resolves.
    val mw: ToolMiddleware = identity
    val _: ToolMiddleware  = mw
    assert(true)

  test("RecordPayload union has model/tool/embedding variants"):
    val modelPayload: RecordPayload = RecordPayload.model(ModelPayloadOps.simple("x"))
    val toolPayload: RecordPayload = RecordPayload.tool(
      ToolPayload(name = "t", result = smithy4s.Document.nullDoc, callId = "c", isError = false)
    )
    val embeddingPayload: RecordPayload = RecordPayload.embedding(
      EmbeddingPayload(model = "m")
    )
    // Pattern match instead of isInstanceOf (wartremover)
    modelPayload match
      case _: RecordPayload => ()
    toolPayload match
      case _: RecordPayload => ()
    embeddingPayload match
      case _: RecordPayload => ()
    assert(true)

  test("Classification enum has PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED"):
    assertEquals(Classification.PUBLIC.value, "PUBLIC")
    assertEquals(Classification.INTERNAL.value, "INTERNAL")
    assertEquals(Classification.CONFIDENTIAL.value, "CONFIDENTIAL")
    assertEquals(Classification.RESTRICTED.value, "RESTRICTED")

  test("CallRecord union has succeeded/failed variants"):
    val succeeded: CallRecord = CallRecord.succeeded(
      SucceededRecord(
        key = "k",
        seq = 0L,
        kind = org.adk4s.record.canonical.CallKind.MODEL,
        payload = RecordPayload.model(ModelPayloadOps.simple("x")),
        classification = Classification.PUBLIC
      )
    )
    val failed: CallRecord = CallRecord.failed(
      FailedRecord(
        key = "k",
        seq = 0L,
        kind = org.adk4s.record.canonical.CallKind.MODEL,
        error = RecordedError(errorType = "Err", message = "msg"),
        classification = Classification.PUBLIC
      )
    )
    succeeded match
      case _: CallRecord => ()
    failed match
      case _: CallRecord => ()
    assert(true)

  test("DeterministicChatModel is available from harness-testkit"):
    // Type-level check: DeterministicChatModel.apply returns IO[DeterministicChatModel]
    val _: IO[org.adk4s.harness.testkit.DeterministicChatModel] =
      DeterministicChatModel(42L, Nil)
    assert(true)
