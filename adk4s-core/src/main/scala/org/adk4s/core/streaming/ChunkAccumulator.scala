package org.adk4s.core.streaming

import fs2.Stream
import cats.effect.IO
import org.llm4s.llmconnect.model.{StreamedChunk, ToolCall, Completion, AssistantMessage}

case class AccumulatedResponse(
  content: String,
  finishReason: Option[String],
  toolCalls: List[ToolCall],
  id: Option[String] = None,
  created: Option[Long] = None,
  model: Option[String] = None,
  usage: Option[org.llm4s.llmconnect.model.TokenUsage] = None,
  thinking: Option[String] = None
)

object ChunkAccumulator:
  val empty: AccumulatedResponse = AccumulatedResponse("", None, Nil)

  def accumulate(acc: AccumulatedResponse, chunk: StreamedChunk): AccumulatedResponse =
    AccumulatedResponse(
      content = acc.content + chunk.content.getOrElse(""),
      finishReason = chunk.finishReason.orElse(acc.finishReason),
      toolCalls = acc.toolCalls ++ chunk.toolCall.toList,
      id = acc.id.orElse(Some(chunk.id)),
      created = acc.created,
      model = acc.model,
      usage = acc.usage,
      thinking = acc.thinking
    )

  extension (acc: AccumulatedResponse)
    def withCompletion(completion: Completion): AccumulatedResponse =
      acc.copy(
        id = Some(completion.id),
        created = Some(completion.created),
        model = Some(completion.model),
        usage = completion.usage,
        thinking = completion.thinking
      )

  def accumulateAll(chunks: Stream[IO, StreamedChunk]): Stream[IO, AccumulatedResponse] =
    chunks
      .fold(empty)(accumulate)
      .map(acc => acc)
