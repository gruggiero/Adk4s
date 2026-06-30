package org.adk4s.core.streaming

import munit.CatsEffectSuite
import cats.effect.IO
import fs2.Stream
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.error.{NetworkError, LLMError}
import org.adk4s.core.error.LlmCallError

class StreamConverterTest extends CatsEffectSuite:
  import StreamConverter.*

  test("Convert successful Iterator to Stream") {
    val iterator = Iterator(StreamedChunk("id1", Some("Hello"), None, None), StreamedChunk("id2", Some("World"), None, None))
    val stream = StreamConverter.fromIterator(iterator)
    val resultIO = stream.compile.toList
    assertIO(resultIO.map(_.size), 2)
    assertIO(resultIO.map(_.headOption.getOrElse(fail("expected element")).content), Some("Hello"))
  }

  test("Convert error Either to error Stream") {
    val error = NetworkError("Connection failed", None, "test")
    val result: Either[LLMError, Iterator[StreamedChunk]] = Left(error)
    val stream = StreamConverter.fromEither(result)
    val resultIO = stream.compile.toList.attempt
    assertIO(resultIO.map(_.isLeft), true)
  }

  test("Convert successful Either to Stream") {
    val iterator = Iterator(StreamedChunk("id1", Some("Hello"), None, None))
    val result: Either[LLMError, Iterator[StreamedChunk]] = Right(iterator)
    val stream = StreamConverter.fromEither(result)
    val resultIO = stream.compile.toList
    assertIO(resultIO.map(_.size), 1)
  }

  test("Convert LLM4S streaming result to fs2.Stream") {
    val iterator = Iterator(StreamedChunk("id1", Some("Hello"), None, None))
    val result: Either[LLMError, Iterator[StreamedChunk]] = Right(iterator)
    val stream = StreamConverter.fromLlm4sStream(result)
    val resultIO = stream.compile.toList
    assertIO(resultIO.map(_.size), 1)
  }

  test("Extract content from streaming result") {
    val iterator = Iterator(
      StreamedChunk("id1", Some("Hello"), None, None),
      StreamedChunk("id2", Some("World"), None, None)
    )
    val result: Either[LLMError, Iterator[StreamedChunk]] = Right(iterator)
    val stream = StreamConverter.contentStream(result)
    val resultIO = stream.compile.toList
    assertIO(resultIO.map(_.size), 2)
    assertIO(resultIO.map(_ == List("Hello", "World")), true)
  }

  test("Handle empty Iterator conversion") {
    val iterator = Iterator.empty[StreamedChunk]
    val stream = StreamConverter.fromIterator(iterator)
    val resultIO = stream.compile.toList
    assertIO(resultIO.map(_.isEmpty), true)
  }

  test("Use extension method to convert Either to Stream") {
    val iterator = Iterator(StreamedChunk("id1", Some("Hello"), None, None))
    val result: Either[LLMError, Iterator[StreamedChunk]] = Right(iterator)
    val stream = result.toFs2Stream
    val resultIO = stream.compile.toList
    assertIO(resultIO.map(_.size), 1)
  }
