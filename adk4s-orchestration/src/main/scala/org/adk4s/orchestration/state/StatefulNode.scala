package org.adk4s.orchestration.state

import fs2.Stream
import cats.effect.IO
import org.adk4s.core.runnable.Runnable

case class StatefulNodeConfig[I, O, S](
  preHandler: Option[PreHandler[I, S]] = None,
  postHandler: Option[PostHandler[O, S]] = None,
  streamPreHandler: Option[StreamPreHandler[I, S]] = None,
  streamPostHandler: Option[StreamPostHandler[O, S]] = None
)

class StatefulNode[I, O, S](
  inner: Runnable[I, O],
  stateRef: StateRef[IO, S],
  config: StatefulNodeConfig[I, O, S]
) extends Runnable[I, O]:

  def invoke(input: I): IO[O] =
    for
      processedInput <- config.preHandler.fold(IO.pure(input))(_(input, stateRef))
      output <- inner.invoke(processedInput)
      processedOutput <- config.postHandler.fold(IO.pure(output))(_(output, stateRef))
    yield processedOutput

  def stream(input: I): Stream[IO, O] =
    val processedInput = Stream.eval(
      config.preHandler.fold(IO.pure(input))(_(input, stateRef))
    )
    val outputStream = processedInput.flatMap(inner.stream)
    config.streamPostHandler.fold(outputStream)(_(outputStream, stateRef))

  def collect(input: Stream[IO, I]): IO[O] =
    val processedInput = config.streamPreHandler.fold(input)(_(input, stateRef))
    for
      output <- inner.collect(processedInput)
      processedOutput <- config.postHandler.fold(IO.pure(output))(_(output, stateRef))
    yield processedOutput

  def transform(input: Stream[IO, I]): Stream[IO, O] =
    val processedInput = config.streamPreHandler.fold(input)(_(input, stateRef))
    val outputStream = inner.transform(processedInput)
    config.streamPostHandler.fold(outputStream)(_(outputStream, stateRef))

object StatefulNode:
  def wrap[I, O, S](
    runnable: Runnable[I, O],
    stateRef: StateRef[IO, S],
    config: StatefulNodeConfig[I, O, S]
  ): StatefulNode[I, O, S] =
    new StatefulNode(runnable, stateRef, config)

  def withPre[I, O, S](
    runnable: Runnable[I, O],
    stateRef: StateRef[IO, S],
    preHandler: PreHandler[I, S]
  ): StatefulNode[I, O, S] =
    wrap(runnable, stateRef, StatefulNodeConfig(preHandler = Some(preHandler)))

  def withPost[I, O, S](
    runnable: Runnable[I, O],
    stateRef: StateRef[IO, S],
    postHandler: PostHandler[O, S]
  ): StatefulNode[I, O, S] =
    wrap(runnable, stateRef, StatefulNodeConfig(postHandler = Some(postHandler)))
