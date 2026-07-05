package org.adk4s.core.streaming

import fs2.{ Stream, Pipe }
import cats.effect.IO
import org.llm4s.llmconnect.model.{ Message, MessageRole, SystemMessage, UserMessage, AssistantMessage, ToolMessage }

object MessageStream:
  def box(message: Message): Stream[IO, Message] =
    Stream.emit(message)

  def fromMessages(messages: Message*): Stream[IO, Message] =
    Stream.emits(messages)

  def concatenate(role: MessageRole): Pipe[IO, String, Message] =
    _.fold("")(_ + _).flatMap(content => Stream.emit(messageForRole(role, content)))

  def merge(streams: Stream[IO, Message]*): Stream[IO, Message] =
    Stream(streams*).parJoinUnbounded

  def broadcast[A](stream: Stream[IO, A], subscriberBuffer: Int = 64): Stream[IO, Stream[IO, A]] =
    Stream.eval(fs2.concurrent.Topic[IO, Option[A]]).flatMap { topic =>
      val publisher  = stream.map(Some(_)).through(topic.publish) ++ Stream.eval(topic.publish1(None))
      val subscriber = topic.subscribe(subscriberBuffer).unNoneTerminate
      Stream.emit(subscriber).concurrently(publisher)
    }

  extension (message: Message) def toStream: Stream[IO, Message] = box(message)

  extension (messages: Seq[Message]) def toStreamIO: Stream[IO, Message] = Stream.emits(messages)

  private def messageForRole(role: MessageRole, content: String): Message =
    role match
      case MessageRole.System    => SystemMessage(content)
      case MessageRole.User      => UserMessage(content)
      case MessageRole.Assistant => AssistantMessage(contentOpt = Some(content), toolCalls = Seq.empty)
      // Tool results keep their role label rather than being silently mislabelled as
      // user messages. `concatenate(role)` has no toolCallId, so it is left empty;
      // callers that need a real id must construct the ToolMessage directly.
      case MessageRole.Tool      => ToolMessage(content, toolCallId = "")
