package org.adk4s.core.streaming

import munit.CatsEffectSuite
import cats.effect.IO
import fs2.Stream
import org.adk4s.structured.core.{Message, Role}
import scala.concurrent.duration.DurationInt

class MessageStreamTest extends CatsEffectSuite:
  import MessageStream.*

  test("Box single message to stream") {
    val message = Message(Role.User, "Hello")
    val stream = MessageStream.box(message)
    val result = stream.compile.toList
    assertIO(result.map(_.size), 1)
    assertIO(result.map(_.headOption.getOrElse(fail("expected element"))), message)
  }

  test("Convert multiple messages to stream") {
    val msg1 = Message(Role.User, "First")
    val msg2 = Message(Role.Assistant, "Second")
    val msg3 = Message(Role.System, "Third")
    val stream = MessageStream.fromMessages(msg1, msg2, msg3)
    val result = stream.compile.toList
    assertIO(result.map(_.size), 3)
    assertIO(result.map(_ == List(msg1, msg2, msg3)), true)
  }

  test("Concatenate content stream into single message") {
    val stream = Stream.emits(List("Hello", " ", "World"))
    val resultStream = stream.through(MessageStream.concatenate(Role.Assistant))
    val result = resultStream.compile.toList
    assertIO(result.map(_.size), 1)
    assertIO(result.map(_.headOption.getOrElse(fail("expected element")).content), "Hello World")
    assertIO(result.map(_.headOption.getOrElse(fail("expected element")).role), Role.Assistant)
  }

  test("Concatenate empty content stream") {
    val stream: Stream[IO, String] = Stream.empty
    val resultStream = stream.through(MessageStream.concatenate(Role.User))
    val resultIO = resultStream.compile.toList
    assertIO(resultIO.map(_.size), 1)
    assertIO(resultIO.map(_.headOption.getOrElse(fail("expected element")).content), "")
  }

  test("Concatenate single element content stream") {
    val stream = Stream.emit("Hello")
    val resultStream = stream.through(MessageStream.concatenate(Role.System))
    val result = resultStream.compile.toList
    assertIO(result.map(_.size), 1)
    assertIO(result.map(_.headOption.getOrElse(fail("expected element")).content), "Hello")
  }

  test("Merge multiple message streams into one") {
    val stream1 = Stream.emit(Message(Role.User, "A")).append(Stream.emit(Message(Role.User, "B")))
    val stream2 = Stream.emit(Message(Role.Assistant, "C"))
    val resultStream = MessageStream.merge(stream1, stream2)
    val result = resultStream.compile.toList
    assertIO(result.map(_.size), 3)
    assertIO(result.map(_.toSet), Set(
      Message(Role.User, "A"),
      Message(Role.User, "B"),
      Message(Role.Assistant, "C")
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
    val message = Message(Role.User, "Hello")
    val stream = message.toStream
    val resultIO = stream.compile.toList
    assertIO(resultIO.map(_.size), 1)
    assertIO(resultIO.map(_.headOption.getOrElse(fail("expected element"))), message)
  }

  test("Use toStreamIO extension on message sequence") {
    val messages = Seq(
      Message(Role.User, "First"),
      Message(Role.Assistant, "Second")
    )
    val stream = messages.toStreamIO
    val resultIO = stream.compile.toList
    assertIO(resultIO.map(_.size), 2)
    assertIO(resultIO.map(_ == messages.toList), true)
  }
