package org.adk4s.core.streaming

import fs2.Stream
import cats.effect.IO
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Conversation, CompletionOptions, Completion, StreamedChunk, AssistantMessage}
import org.adk4s.core.error.{AdkError, LlmCallError}
import org.llm4s.error.LLMError

trait StreamingLLMClient[F[_]]:
  def stream(conversation: Conversation, options: CompletionOptions): Stream[F, StreamedChunk]

  def streamContent(conversation: Conversation, options: CompletionOptions): Stream[F, String]

  def complete(conversation: Conversation, options: CompletionOptions): F[Completion]

object StreamingLLMClient:
  def fromClient(client: LLMClient): StreamingLLMClient[IO] = new StreamingLLMClient[IO]:
    def stream(conversation: Conversation, options: CompletionOptions): Stream[IO, StreamedChunk] =
      Stream.eval(IO {
        val chunks = scala.collection.mutable.ListBuffer[StreamedChunk]()
        val result: Either[LLMError, Completion] =
          client.streamComplete(conversation, options, chunk => 
            chunks += chunk
          )
        (result, chunks.toList)
      }).flatMap {
        case (Right(_), chunks) => Stream.emits(chunks)
        case (Left(err), _) => Stream.raiseError[IO](LlmCallError(err))
      }

    def streamContent(conversation: Conversation, options: CompletionOptions): Stream[IO, String] =
      stream(conversation, options)
        .evalMap(chunk => IO.pure(chunk.content.getOrElse("")))
        .filter(_.nonEmpty)

    def complete(conversation: Conversation, options: CompletionOptions): IO[Completion] =
      IO(client.complete(conversation, options)).flatMap {
        case Right(completion) => IO.pure(completion)
        case Left(error) => IO.raiseError(LlmCallError(error))
      }

  def fromNonStreaming(client: LLMClient): StreamingLLMClient[IO] = new StreamingLLMClient[IO]:
    def stream(conversation: Conversation, options: CompletionOptions): Stream[IO, StreamedChunk] =
      Stream.eval(IO(client.complete(conversation, options)))
        .flatMap {
          case Right(completion) =>
            val chunk = StreamedChunk(
              id = completion.id,
              content = Some(completion.content),
              toolCall = None,
              finishReason = Some("stop"),
              thinkingDelta = None
            )
            Stream.emit(chunk)
          case Left(error) =>
            Stream.raiseError[IO](LlmCallError(error))
        }

    def streamContent(conversation: Conversation, options: CompletionOptions): Stream[IO, String] =
      stream(conversation, options).evalMap(chunk => IO.pure(chunk.content.getOrElse(""))).filter(_.nonEmpty)

    def complete(conversation: Conversation, options: CompletionOptions): IO[Completion] =
      IO(client.complete(conversation, options)).flatMap {
        case Right(completion) => IO.pure(completion)
        case Left(error) => IO.raiseError(LlmCallError(error))
      }
