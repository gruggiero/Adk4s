package org.adk4s.harness.testkit

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.apply.catsSyntaxApplyOps
import cats.syntax.functor.toFunctorOps
import org.adk4s.core.component.{ ChatModel, ChatModelConfig }
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  Message,
  StreamedChunk,
  SystemMessage,
  ToolCall
}
import fs2.Stream

/**
 * What the deterministic double observed at the base model step, per request.
 *
 * The trace is the observable for L3 hook distribution and L0 equivalence:
 * it captures the rendered system prompt, the message list, and the tool
 * names (in order) that the base model step actually received.
 *
 * spec: middleware-laws — Requirement: Deterministic ChatModel double enables observational equivalence
 */
final case class RecordedRequest(
  renderedSystemPrompt: Option[String],
  messages: List[Message],
  toolNames: List[String]
)

/**
 * A deterministic `ChatModel[IO]` double for L0–L11 observational equivalence.
 *
 * Given a fixed seed and a scripted response sequence, the double produces a
 * fixed `Completion` for each call and records the request trace (rendered
 * system prompt, messages, tool names) at the base step. The double is
 * deterministic across repeated runs with the same seed and depends on no
 * wall-clock time or external I/O: `Completion.id` is derived from the seed
 * and a deterministic counter, `created` is always `0L`, and no `UUID` /
 * `System.currentTimeMillis` / `Clock` is used.
 *
 * The recorded trace is the observable for L3 hook distribution (wrapping
 * order) and L0 equivalence (request traces match between two runs).
 *
 * spec: middleware-laws — Requirement: Deterministic ChatModel double enables observational equivalence
 */
final class DeterministicChatModel private (
  seed: Long,
  script: List[Completion],
  val capturedRequests: Ref[IO, List[RecordedRequest]]
) extends ChatModel[IO]:
  private val counter: Array[Int] = Array(0)

  private def completionFor(idx: Int): Completion =
    if idx < script.length then script(idx)
    else
      // Deterministic fallback: id and content are derived from the seed and
      // index — no UUID, no wall-clock. Repeated runs with the same seed
      // produce the same fallback for the same index.
      DeterministicChatModel.fallbackCompletion(seed, idx)

  override def generate(conversation: Conversation, options: CompletionOptions): IO[Completion] =
    val record: RecordedRequest = RecordedRequest(
      renderedSystemPrompt = conversation.messages.collectFirst { case m: SystemMessage => m.content },
      messages = conversation.messages.toList,
      toolNames = options.tools.map((tf: org.llm4s.toolapi.ToolFunction[?, ?]) => tf.name).toList
    )
    capturedRequests.update((acc: List[RecordedRequest]) => acc :+ record) *> IO.delay {
      val idx: Int = counter(0)
      counter(0) = idx + 1
      completionFor(idx)
    }

  def generate(conversation: Conversation): IO[Completion] =
    generate(conversation, CompletionOptions())

  def stream(conversation: Conversation): Stream[IO, StreamedChunk] =
    Stream.empty.covary[IO]

  def streamContent(conversation: Conversation): Stream[IO, String] =
    Stream.empty.covary[IO]

  def withConfig(config: ChatModelConfig): ChatModel[IO] = this

object DeterministicChatModel:
  /** Construct a `DeterministicChatModel` with a fixed seed and script. */
  def apply(seed: Long, script: List[Completion]): IO[DeterministicChatModel] =
    Ref
      .of[IO, List[RecordedRequest]](Nil)
      .map((ref: Ref[IO, List[RecordedRequest]]) => new DeterministicChatModel(seed, script, ref))

  /**
   * Deterministic fallback completion — id and content derived from seed and
   * index, `created = 0L`, no UUID / wall-clock.
   */
  def fallbackCompletion(seed: Long, idx: Int): Completion =
    Completion(
      id = s"det-$seed-$idx",
      created = 0L,
      content = s"fallback-$idx",
      model = "deterministic-test-model",
      message = AssistantMessage(Some(s"fallback-$idx"))
    )

  // ── Completion builders (deterministic — no UUID) ───────────────────────

  /** A final-text completion with a deterministic id derived from the seed. */
  def textCompletion(seed: Long, idx: Int, content: String): Completion =
    Completion(
      id = s"det-$seed-$idx",
      created = 0L,
      content = content,
      model = "deterministic-test-model",
      message = AssistantMessage(Some(content))
    )

  /** A single-tool-call completion with a deterministic call id. */
  def toolCallCompletion(seed: Long, idx: Int, toolName: String, args: String): Completion =
    val callId: String = s"call-$seed-$idx"
    val tc: ToolCall   = ToolCall(id = callId, name = toolName, arguments = ujson.read(args))
    Completion(
      id = s"det-$seed-$idx",
      created = 0L,
      content = "",
      model = "deterministic-test-model",
      message = AssistantMessage(None, Seq(tc))
    )

  /** A multi-tool-call completion with deterministic call ids. */
  def multiToolCallCompletion(seed: Long, idx: Int, toolNames: List[String]): Completion =
    val calls: Seq[ToolCall] = toolNames.zipWithIndex.map { case (name, j) =>
      ToolCall(id = s"call-$seed-$idx-$j", name = name, arguments = ujson.Obj())
    }
    Completion(
      id = s"det-$seed-$idx",
      created = 0L,
      content = "",
      model = "deterministic-test-model",
      message = AssistantMessage(None, calls)
    )
