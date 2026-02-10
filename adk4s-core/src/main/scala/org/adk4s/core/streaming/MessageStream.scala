package org.adk4s.core.streaming

import fs2.{Stream, Pipe}
import cats.effect.IO
import org.adk4s.structured.core.{Message, Role}

object MessageStream:
  def box(message: Message): Stream[IO, Message] =
    Stream.emit(message)

  def fromMessages(messages: Message*): Stream[IO, Message] =
    Stream.emits(messages)

  def concatenate(role: Role): Pipe[IO, String, Message] =
    _.fold("")(_ + _).flatMap(content => Stream.emit(Message(role, content)))

  def merge(streams: Stream[IO, Message]*): Stream[IO, Message] =
    Stream(streams*).parJoinUnbounded

  def broadcast[A](stream: Stream[IO, A], subscriberBuffer: Int = 64): Stream[IO, Stream[IO, A]] =
    Stream.eval(fs2.concurrent.Topic[IO, Option[A]]).flatMap { topic =>
      val publisher = stream.map(Some(_)).through(topic.publish) ++ Stream.eval(topic.publish1(None))
      val subscriber = topic.subscribe(subscriberBuffer).unNoneTerminate
      Stream.emit(subscriber).concurrently(publisher)
    }

  extension (message: Message)
    def toStream: Stream[IO, Message] = box(message)

  extension (messages: Seq[Message])
    def toStreamIO: Stream[IO, Message] = Stream.emits(messages)
