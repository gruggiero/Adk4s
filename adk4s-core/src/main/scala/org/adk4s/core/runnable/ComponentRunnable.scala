package org.adk4s.core.runnable

import fs2.Stream
import cats.effect.IO
import org.adk4s.core.component.*
import org.llm4s.llmconnect.model.{Conversation, Completion, StreamedChunk, AssistantMessage}
import org.adk4s.core.streaming.ChunkAccumulator
import upickle.default.*

trait ToRunnable[C, I, O]:
  def toRunnable(component: C): Runnable[I, O]

object ToRunnable:
  given chatModelRunnable: ToRunnable[ChatModel[IO], Conversation, Completion] =
    new ToRunnable[ChatModel[IO], Conversation, Completion]:
      def toRunnable(model: ChatModel[IO]): Runnable[Conversation, Completion] =
        new Runnable[Conversation, Completion]:
          def invoke(input: Conversation): IO[Completion] = model.generate(input)

          def stream(input: Conversation): Stream[IO, Completion] =
            model
              .stream(input)
              .through(ChunkAccumulator.accumulateAll)
              .map { acc =>
                Completion(
                  id = acc.id.getOrElse(""),
                  created = acc.created.getOrElse(0L),
                  content = acc.content,
                  model = acc.model.getOrElse(""),
                  message = AssistantMessage(
                    contentOpt = Some(acc.content),
                    toolCalls = acc.toolCalls
                  ),
                  toolCalls = acc.toolCalls,
                  usage = acc.usage,
                  thinking = acc.thinking
                )
              }

          def collect(input: Stream[IO, Conversation]): IO[Completion] =
            input.compile.lastOrError.flatMap(model.generate)

          def transform(input: Stream[IO, Conversation]): Stream[IO, Completion] =
            input.evalMap(model.generate)

  given invokableToolRunnable: ToRunnable[InvokableTool[IO], ujson.Value, ujson.Value] =
    new ToRunnable[InvokableTool[IO], ujson.Value, ujson.Value]:
      def toRunnable(tool: InvokableTool[IO]): Runnable[ujson.Value, ujson.Value] =
        Runnable.fromInvoke(tool.run)

  given streamableToolRunnable: ToRunnable[StreamableTool[IO], ujson.Value, String] =
    new ToRunnable[StreamableTool[IO], ujson.Value, String]:
      def toRunnable(tool: StreamableTool[IO]): Runnable[ujson.Value, String] =
        Runnable.fromStream(tool.runStream)

  given [I, O] => ToRunnable[Lambda[I, O], I, O] =
    new ToRunnable[Lambda[I, O], I, O]:
      def toRunnable(lambda: Lambda[I, O]): Runnable[I, O] = lambda.toRunnable

  extension [C](component: C)
    def asRunnable[I, O](using tr: ToRunnable[C, I, O]): Runnable[I, O] =
      tr.toRunnable(component)
