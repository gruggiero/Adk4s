package org.adk4s.core.streaming

import munit.CatsEffectSuite
import fs2.Stream
import cats.effect.IO
import org.adk4s.structured.core.Message
import scala.concurrent.duration.*

class PackageExportsTest extends CatsEffectSuite:
  import StreamOps.*
  import MessageStream.*
  import StreamConverter.*

  test("StreamConverter methods are available in import") {
    assert(StreamConverter.fromIterator != null)
    assert(StreamConverter.fromEither != null)
    assert(StreamConverter.fromLlm4sStream != null)
    assert(StreamConverter.contentStream != null)
  }

  test("ChunkAccumulator methods are available in import") {
    assert(ChunkAccumulator.empty != null)
    assert(ChunkAccumulator.accumulate != null)
  }

  test("MessageStream methods are available in import") {
    assert(MessageStream.box != null)
    assert(MessageStream.broadcast != null)
  }

  test("StreamOps methods are available in import") {
    assert(StreamOps.withElementTimeout != null)
    assert(StreamOps.withStreamTimeout != null)
    assert(StreamOps.withRetry != null)
    assert(StreamOps.buffered != null)
    assert(StreamOps.rateLimit != null)
    assert(StreamOps.debug != null)
    assert(StreamOps.takeUntilInclusive != null)
  }

  test("AdkStream type alias is available") {
    val stream: AdkStream[Int] = Stream.emit(1)
    assert(stream != null)
  }

  test("StreamConverter toFs2Stream extension works") {
    val result: Either[org.llm4s.error.LLMError, Iterator[Int]] = Right(Iterator(1, 2, 3))
    val stream = result.toFs2Stream
    val resultIO = stream.compile.toList
    assertIO(resultIO, List(1, 2, 3))
  }

  test("Message toStream extension works") {
    val message = Message(org.adk4s.structured.core.Role.User, "Hello")
    val stream = message.toStream
    val resultIO = stream.compile.toList
    assertIO(resultIO, List(message))
  }

  test("Stream withTimeout extension works") {
    val stream = Stream.emit(1).withTimeout(1.second)
    val resultIO = stream.compile.toList
    assertIO(resultIO, List(1))
  }

  test("Stream withElementTimeout extension works") {
    val stream = Stream.emit(1).withElementTimeout(1.second)
    val resultIO = stream.compile.toList
    assertIO(resultIO, List(1))
  }

  test("Stream retryWithBackoff extension works") {
    var attempts = 0
    val stream: Stream[IO, Int] = Stream.eval(IO {
      attempts += 1
      if attempts < 3 then throw new RuntimeException("Retry me") else attempts
    })
    val resultIO = stream.retryWithBackoff(5).compile.toList
    assertIO(resultIO, List(3))
  }

  test("Stream rateLimited extension works") {
    val stream = Stream.emits(1 to 3).rateLimited(10)
    val resultIO = stream.compile.toList
    assertIO(resultIO, List(1, 2, 3))
  }

  test("Stream debugLog extension works") {
    val stream = Stream.emit("test").debugLog("PREFIX")
    val resultIO = stream.compile.toList
    assertIO(resultIO, List("test"))
  }
