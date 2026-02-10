package org.adk4s.core.runnable

import fs2.Stream
import cats.effect.IO

trait Runnable[I, O]:
  def invoke(input: I): IO[O]
  def stream(input: I): Stream[IO, O]
  def collect(input: Stream[IO, I]): IO[O]
  def transform(input: Stream[IO, I]): Stream[IO, O]

object Runnable:
  def fromInvoke[I, O](f: I => IO[O]): Runnable[I, O] = new Runnable[I, O]:
    def invoke(input: I): IO[O] = f(input)
    def stream(input: I): Stream[IO, O] = Stream.eval(f(input))
    def collect(input: Stream[IO, I]): IO[O] = input.compile.lastOrError.flatMap(f)
    def transform(input: Stream[IO, I]): Stream[IO, O] = input.evalMap(f)

  def fromStream[I, O](f: I => Stream[IO, O]): Runnable[I, O] = new Runnable[I, O]:
    def invoke(input: I): IO[O] = f(input).compile.lastOrError
    def stream(input: I): Stream[IO, O] = f(input)
    def collect(input: Stream[IO, I]): IO[O] = input.compile.lastOrError.flatMap(i => f(i).compile.lastOrError)
    def transform(input: Stream[IO, I]): Stream[IO, O] = input.flatMap(f)

  def fromCollect[I, O](f: Stream[IO, I] => IO[O]): Runnable[I, O] = new Runnable[I, O]:
    def invoke(input: I): IO[O] = f(Stream.emit(input))
    def stream(input: I): Stream[IO, O] = Stream.eval(f(Stream.emit(input)))
    def collect(input: Stream[IO, I]): IO[O] = f(input)
    def transform(input: Stream[IO, I]): Stream[IO, O] = Stream.eval(f(input))

  def fromTransform[I, O](f: Stream[IO, I] => Stream[IO, O]): Runnable[I, O] = new Runnable[I, O]:
    def invoke(input: I): IO[O] = f(Stream.emit(input)).compile.lastOrError
    def stream(input: I): Stream[IO, O] = f(Stream.emit(input))
    def collect(input: Stream[IO, I]): IO[O] = f(input).compile.lastOrError
    def transform(input: Stream[IO, I]): Stream[IO, O] = f(input)

  def identity[I]: Runnable[I, I] = fromInvoke[I, I]((input: I) => IO.pure(input))

  def full[I, O](
    invokeFn: I => IO[O],
    streamFn: I => Stream[IO, O],
    collectFn: Stream[IO, I] => IO[O],
    transformFn: Stream[IO, I] => Stream[IO, O]
  ): Runnable[I, O] = new Runnable[I, O]:
    def invoke(input: I): IO[O] = invokeFn(input)
    def stream(input: I): Stream[IO, O] = streamFn(input)
    def collect(input: Stream[IO, I]): IO[O] = collectFn(input)
    def transform(input: Stream[IO, I]): Stream[IO, O] = transformFn(input)
