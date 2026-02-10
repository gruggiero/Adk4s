package org.adk4s.core.streaming

import fs2.Stream
import cats.effect.IO
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.error.LLMError
import org.adk4s.core.error.{AdkError, LlmCallError}

object StreamConverter:
  def fromIterator[A](iter: Iterator[A], chunkSize: Int = 1): Stream[IO, A] =
    Stream.fromIterator[IO](iter, chunkSize)

  def fromEither[A](result: Either[LLMError, Iterator[A]]): Stream[IO, A] =
    result match
      case Right(iter) => fromIterator(iter)
      case Left(error) => Stream.raiseError[IO](LlmCallError(error))

  def fromLlm4sStream(
    result: Either[LLMError, Iterator[StreamedChunk]]
  ): Stream[IO, StreamedChunk] =
    fromEither(result)

  def contentStream(
    result: Either[LLMError, Iterator[StreamedChunk]]
  ): Stream[IO, String] =
    fromLlm4sStream(result)
      .evalMap(chunk => IO.pure(chunk.content.getOrElse("")))
      .filter(_.nonEmpty)

  extension [A](result: Either[LLMError, Iterator[A]])
    def toFs2Stream: Stream[IO, A] = fromEither(result)
