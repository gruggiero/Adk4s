package org.adk4s.orchestration.state

import fs2.Stream
import cats.effect.IO
import cats.syntax.all.*

type PreHandler[I, S] = (I, StateRef[IO, S]) => IO[I]

type PostHandler[O, S] = (O, StateRef[IO, S]) => IO[O]

type StreamPreHandler[I, S] = (Stream[IO, I], StateRef[IO, S]) => Stream[IO, I]

type StreamPostHandler[O, S] = (Stream[IO, O], StateRef[IO, S]) => Stream[IO, O]

object StateHandlers:
  def identityPre[I, S]: PreHandler[I, S] = (i, _) => IO.pure(i)

  def identityPost[O, S]: PostHandler[O, S] = (o, _) => IO.pure(o)

  def accumulate[I, S](lens: S => List[I], update: (S, List[I]) => S): PreHandler[I, S] =
    (input, stateRef) =>
      stateRef.modify { s =>
        val accumulated = lens(s) :+ input
        (update(s, accumulated), input)
      }

  def fromState[I, S](lens: S => I): PreHandler[I, S] =
    (_, stateRef) => stateRef.get.map(lens)

  def storeOutput[O, S](update: (S, O) => S): PostHandler[O, S] =
    (output, stateRef) =>
      stateRef.update(s => update(s, output)).as(output)

  def combinePre[I, S](handlers: PreHandler[I, S]*): PreHandler[I, S] =
    (input, stateRef) =>
      handlers.foldLeftM(input)((i, h) => h(i, stateRef))

  def combinePost[O, S](handlers: PostHandler[O, S]*): PostHandler[O, S] =
    (output, stateRef) =>
      handlers.foldLeftM(output)((o, h) => h(o, stateRef))
