package org.adk4s.core.runnable

import cats.effect.IO
import cats.effect.std.Dispatcher
import fs2.Stream
import org.llm4s.agent.orchestration.TypedAgent
import org.llm4s.types.Result
import scala.concurrent.ExecutionContext

object TypedAgentBridge:
  def toRunnable[I, O](
    agent: TypedAgent[I, O]
  )(using ec: ExecutionContext): Runnable[I, O] = new Runnable[I, O]:
    def invoke(input: I): IO[O] =
      IO.fromFuture(IO(agent.execute(input)))
        .flatMap {
          case Right(value) => IO.pure(value)
          case Left(error)  => IO.raiseError(new RuntimeException(s"TypedAgent error: ${error.message}"))
        }
    def stream(input: I): Stream[IO, O] = Stream.eval(invoke(input))
    def collect(input: Stream[IO, I]): IO[O] = input.compile.lastOrError.flatMap(invoke)
    def transform(input: Stream[IO, I]): Stream[IO, O] = input.evalMap(invoke)
