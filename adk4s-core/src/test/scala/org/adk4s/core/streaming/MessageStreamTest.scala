package org.adk4s.core.streaming

import munit.CatsEffectSuite
import cats.effect.IO
import fs2.Stream
import org.llm4s.llmconnect.model.{Message, MessageRole, SystemMessage, UserMessage, AssistantMessage}
import scala.concurrent.duration.DurationInt

class MessageStreamTest extends CatsEffectSuite:
  import MessageStream.*

  test("Box single message to stream") {
    val message: Message = UserMessage("Hello")
    val stream: Stream[IO, Message] = MessageStream.box(message)
    val result = stream.compile.toList
    assertIO(result.map(_.size), 1)
    assertIO(result.map(_.headOption.getOrElse(fail("expected element"))), message)
  }

  test("Convert multiple messages to stream") {
    val msg1: Message = UserMessage("First")
    val msg2: Message = AssistantMessage(contentOpt = Some("Second"), toolCalls = Seq.empty)
    val msg3: Message = SystemMessage("Third")
    val stream = MessageStream.fromMessages(msg1, msg2, msg3)
    val result = stream.compile.toList
    assertIO(result.map(_.size), 3)
    assertIO(result.map(_ == List(msg1, msg2, msg3)), true)
  }

  test("Concatenate content stream into single message") {
    val stream = Stream.emits(List("Hello", " ", "World"))
    val resultStream = stream.through(MessageStream.concatenate(MessageRole.Assistant))
    val result = resultStream.compile.toList
    assertIO(result.map(_.size), 1)
    assertIO(result.map(_.headOption.getOrElse(fail("expected element")).content), "Hello World")
    assertIO(result.map(_.headOption.getOrElse(fail("expected element")).role), MessageRole.Assistant)
  }

  test("Concatenate empty content stream") {
    val stream: Stream[IO, String] = Stream.empty
    val resultStream = stream.through(MessageStream.concatenate(MessageRole.User))
    val resultIO = resultStream.compile.toList
    assertIO(resultIO.map(_.size), 1)
    assertIO(resultIO.map(_.headOption.getOrElse(fail("expected element")).content), "")
  }

  test("Concatenate single element content stream") {
    val stream = Stream.emit("Hello")
    val resultStream = stream.through(MessageStream.concatenate(MessageRole.System))
    val result = resultStream.compile.toList
    assertIO(result.map(_.size), 1)
    assertIO(result.map(_.headOption.getOrElse(fail("expected element")).content), "Hello")
  }

  test("Concatenate Tool role keeps the tool role, not mislabelled as user (fix #2)") {
    // spec: fix-llm4s-middleware-review-issues — Tool role preserved under concatenate.
    // Previously a `case other => UserMessage(content)` catch-all silently mislabelled
    // tool results as user messages after the Role alias widened to llm4s MessageRole.
    val stream: Stream[IO, String]      = Stream.emits(List("result-", "payload"))
    val resultStream                     = stream.through(MessageStream.concatenate(MessageRole.Tool))
    val result: IO[List[Message]]        = resultStream.compile.toList
    assertIO(result.map(_.size), 1)
    assertIO(result.map(_.headOption.getOrElse(fail("expected element")).role), MessageRole.Tool)
    assertIO(result.map(_.headOption.getOrElse(fail("expected element")).content), "result-payload")
  }

  test("Merge multiple message streams into one") {
    val stream1: Stream[IO, Message] = Stream.emit(UserMessage("A")).append(Stream.emit(UserMessage("B")))
    val stream2: Stream[IO, Message] = Stream.emit(AssistantMessage(contentOpt = Some("C"), toolCalls = Seq.empty))
    val resultStream = MessageStream.merge(stream1, stream2)
    val result = resultStream.compile.toList
    assertIO(result.map(_.size), 3)
    assertIO(result.map(_.toSet), Set[Message](
      UserMessage("A"),
      UserMessage("B"),
      AssistantMessage(contentOpt = Some("C"), toolCalls = Seq.empty)
    ))
  }

  test("Merge empty streams") {
    val stream1: Stream[IO, Message] = Stream.empty
    val stream2: Stream[IO, Message] = Stream.empty
    val resultStream = MessageStream.merge(stream1, stream2)
    val resultIO = resultStream.compile.toList
    assertIO(resultIO.map(_.isEmpty), true)
  }

  test("Broadcast stream to multiple consumers") {
  // Simple test to verify broadcast returns a stream of streams
  val stream = Stream.emits(List("a", "b", "c"))
  val resultStream = MessageStream.broadcast(stream)
  
  // Just verify that broadcast returns a stream that emits a stream
  val consumer = resultStream.take(1).compile.toList
  assertIO(consumer.map(_.size), 1)
}

  test("Use toStream extension on single message") {
    val message: Message = UserMessage("Hello")
    val stream = message.toStream
    val resultIO = stream.compile.toList
    assertIO(resultIO.map(_.size), 1)
    assertIO(resultIO.map(_.headOption.getOrElse(fail("expected element"))), message)
  }

  test("Use toStreamIO extension on message sequence") {
    val messages: Seq[Message] = Seq(
      UserMessage("First"),
      AssistantMessage(contentOpt = Some("Second"), toolCalls = Seq.empty)
    )
    val stream = messages.toStreamIO
    val resultIO = stream.compile.toList
    assertIO(resultIO.map(_.size), 2)
    assertIO(resultIO.map(_ == messages.toList), true)
  }
