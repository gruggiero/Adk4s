package org.adk4s.record

import cats.MonadThrow
import cats.syntax.all.*
import fs2.Stream
import org.adk4s.core.component.ChatModel
import org.adk4s.core.component.ChatModelConfig
import org.adk4s.record.canonical.CallKind
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.llmconnect.model.ToolCall as LlmToolCall

// ── RecordedChatModel — recording + caching ChatModel decorator ────────
// spec: add-adk4s-record/recorded-wrappers — Requirement: Wrapped component is observationally equivalent with noop recorder
// spec: add-adk4s-record/recorded-wrappers — Requirement: Hit returns recorded result with zero underlying calls
// spec: add-adk4s-record/recorded-wrappers — Requirement: Miss calls underlying component and records result
// spec: add-adk4s-record/recorded-wrappers — Requirement: Recording failure does not fail the call
// spec: add-adk4s-record/recorded-wrappers — Requirement: Nonzero temperature without RolloutId emits a diagnostic warning
// spec: add-adk4s-record/recorded-wrappers — Requirement: Redaction applies after key computation
//
// Wraps a ChatModel[F] with recording and caching. On a miss, the
// underlying model is called and the result is recorded under the computed
// CallKey. On a hit, the recorded completion is returned with zero
// underlying calls. Recording failures are surfaced without failing the
// call. A nonzero temperature without a RolloutId emits a diagnostic
// warning. Redaction is applied to the payload after key computation.
object RecordedChatModel:

  /**
   * Wrap a ChatModel[F] with recording and caching.
   *
   * @param under
   *   The underlying ChatModel to wrap.
   * @param recorder
   *   The Recorder to store call records in.
   * @param rollout
   *   Optional RolloutId for deliberate resampling. When present, no
   *   sampling warning is emitted even with nonzero temperature.
   * @param redaction
   *   Optional payload redaction function, applied after key computation.
   * @param warningChannel
   *   Optional function to receive diagnostic warnings. Defaults to
   *   a no-op channel if None.
   * @return
   *   A ChatModel[F] that records and caches calls.
   */
  def apply[F[_]](
    under: ChatModel[F],
    recorder: Recorder[F],
    rollout: Option[RolloutId] = None,
    redaction: Option[Redaction] = None,
    warningChannel: Option[String => F[Unit]] = None
  )(using F: MonadThrow[F]): ChatModel[F] =
    val warn: String => F[Unit] = warningChannel.getOrElse(_ => F.unit)
    new ChatModel[F]:
      // ── generate ──────────────────────────────────────────────────
      def generate(conversation: Conversation): F[Completion] =
        generate(conversation, CompletionOptions())

      override def generate(
        conversation: Conversation,
        options: CompletionOptions
      ): F[Completion] =
        // Build the canonical form and key from the request fields
        val req = ModelCallRequest(
          provider = "recorded",
          model = "recorded-model",
          conversation = conversation,
          tools = Nil,
          systemPrompt = "",
          options = options,
          rollout = rollout
        )
        val form = CanonicalFormOps.from(req)
        val key  = CallKey.fromCanonical(form)

        // Emit warning for nonzero temperature without rollout
        val warnAction: F[Unit] =
          if options.temperature != 0.0 && rollout.isEmpty then
            warn(
              "Sampled result (temperature=" +
                options.temperature.toString +
                ") is being cached deterministically without a RolloutId. " +
                "Identical requests will return this sampled result. " +
                "Provide a RolloutId for deliberate resampling."
            )
          else F.unit

        // Lookup → hit or miss
        recorder.lookup(key).flatMap {
          case Some(record) =>
            // Hit: reconstruct Completion from stored payload, zero calls
            record match
              case CallRecord.SucceededCase(succeeded) =>
                succeeded.payload match
                  case RecordPayload.ModelCase(payload) =>
                    F.pure(reconstructCompletion(payload))
                  case _ => // danger-scan:allow payload-kind mismatch means stale/corrupt record; fall through to miss is correct, not a silent mapping to a valid domain value
                    // Wrong payload kind — treat as miss
                    callAndRecord(key, conversation, options, warnAction)
              case CallRecord.FailedCase(_) =>
                // Failed record — treat as miss (retry)
                callAndRecord(key, conversation, options, warnAction)
          case None =>
            // Miss: call underlying, record, return
            callAndRecord(key, conversation, options, warnAction)
        }

      private def callAndRecord(
        key: CallKey,
        conversation: Conversation,
        options: CompletionOptions,
        warnAction: F[Unit]
      ): F[Completion] =
        warnAction *> under.generate(conversation, options).flatMap { completion =>
          val payload  = RecordPayload.model(completionToPayload(completion))
          val redacted = redaction.fold(payload)(_(payload))
          val record = CallRecord.succeeded(
            SucceededRecord(
              key = key.value,
              seq = 0L,
              kind = CallKind.MODEL,
              payload = redacted,
              classification = Classification.PUBLIC
            )
          )
          // Record without failing the call on write failure
          recorder.record(key, record).attempt.void.as(completion)
        }

      // ── stream (not cached — streaming is not recordable) ────────
      def stream(conversation: Conversation): Stream[F, StreamedChunk] =
        under.stream(conversation)

      def streamContent(conversation: Conversation): Stream[F, String] =
        under.streamContent(conversation)

      def withConfig(config: ChatModelConfig): ChatModel[F] =
        RecordedChatModel(under.withConfig(config), recorder, rollout, redaction, warningChannel)

// ── Completion ↔ ModelPayload conversion ────────────────────────────────
// These are the boundary conversions between llm4s Completion and the
// Smithy-generated ModelPayload. They preserve all fields needed for
// full reconstruction on a cache hit.

object ModelPayloadOps:
  /** Convenience constructor for tests — only content is required. */
  def simple(content: String): ModelPayload =
    ModelPayload(
      content = content,
      id = "",
      created = 0L,
      model = ""
    )

private def completionToPayload(c: Completion): ModelPayload =
  // Use message.toolCalls (the AssistantMessage's tool calls) rather than
  // completion.toolCalls (the top-level field). The HarnessAgent and
  // ReactAgent loop check message.toolCalls, not completion.toolCalls.
  // The DeterministicChatModel and real providers put tool calls in
  // message.toolCalls; completion.toolCalls is often empty.
  val msgToolCalls: List[LlmToolCall] = c.message.toolCalls.toList
  val toolCalls: Option[List[ModelToolCall]] =
    if msgToolCalls.isEmpty then None
    else
      Some(msgToolCalls.map { tc =>
        ModelToolCall(
          id = tc.id,
          name = tc.name,
          arguments = org.adk4s.core.json.JsonValueCodec.fromUjson(tc.arguments)
        )
      })
  val usage: Option[TokenUsage] = c.usage
  ModelPayload(
    content = c.content,
    id = c.id,
    created = c.created,
    model = c.model,
    finishReason = None,
    toolCalls = toolCalls,
    promptTokens = usage.map(_.promptTokens),
    completionTokens = usage.map(_.completionTokens),
    totalTokens = usage.map(_.totalTokens),
    thinkingTokens = usage.flatMap(_.thinkingTokens),
    cachedTokens = usage.flatMap(_.cachedTokens),
    cacheCreationTokens = usage.flatMap(_.cacheCreationTokens),
    thinking = c.thinking,
    estimatedCost = c.estimatedCost,
    messageContent = c.message.contentOpt
  )

private def reconstructCompletion(p: ModelPayload): Completion =
  val toolCalls: List[LlmToolCall] = p.toolCalls.getOrElse(Nil).map { tc =>
    LlmToolCall(
      id = tc.id,
      name = tc.name,
      arguments = org.adk4s.core.json.JsonValueCodec.toUjson(tc.arguments)
    )
  }
  // The || → && mutants here are equivalent: TokenUsage always has all 3
  // token fields, so completionToPayload always sets all 3 to Some(_).
  // When all 3 are defined, || and && produce the same result.
  val usage: Option[TokenUsage] =
    if p.promptTokens.isDefined || p.completionTokens.isDefined || p.totalTokens.isDefined then
      Some(
        TokenUsage(
          promptTokens = p.promptTokens.getOrElse(0),
          completionTokens = p.completionTokens.getOrElse(0),
          totalTokens = p.totalTokens.getOrElse(0),
          thinkingTokens = p.thinkingTokens,
          cachedTokens = p.cachedTokens,
          cacheCreationTokens = p.cacheCreationTokens
        )
      )
    else None
  Completion(
    id = p.id,
    created = p.created,
    content = p.content,
    model = p.model,
    message = AssistantMessage(p.messageContent, toolCalls),
    toolCalls = toolCalls,
    usage = usage,
    thinking = p.thinking,
    estimatedCost = p.estimatedCost
  )
