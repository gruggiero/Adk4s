package org.adk4s.core.runnable

import cats.effect.IO
import fs2.Stream

final case class LambdaConfig(
  name: Option[String] = None,
  description: Option[String] = None
)

trait LambdaBuilder[A, I, O]:
  def toRunnable(value: A): Runnable[I, O]

object LambdaBuilder:
  final case class Full[I, O](
    invoke: I => IO[O],
    stream: I => Stream[IO, O],
    collect: Stream[IO, I] => IO[O],
    transform: Stream[IO, I] => Stream[IO, O]
  )

  given fromInvoke: [I, O] => LambdaBuilder[I => IO[O], I, O] =
    new LambdaBuilder[I => IO[O], I, O] {
      def toRunnable(value: I => IO[O]): Runnable[I, O] = Runnable.fromInvoke(value)
    }

  given fromStream: [I, O] => LambdaBuilder[I => Stream[IO, O], I, O] =
    new LambdaBuilder[I => Stream[IO, O], I, O] {
      def toRunnable(value: I => Stream[IO, O]): Runnable[I, O] = Runnable.fromStream(value)
    }

  given fromCollect: [I, O] => LambdaBuilder[Stream[IO, I] => IO[O], I, O] =
    new LambdaBuilder[Stream[IO, I] => IO[O], I, O] {
      def toRunnable(value: Stream[IO, I] => IO[O]): Runnable[I, O] = Runnable.fromCollect(value)
    }

  given fromTransform: [I, O] => LambdaBuilder[Stream[IO, I] => Stream[IO, O], I, O] =
    new LambdaBuilder[Stream[IO, I] => Stream[IO, O], I, O] {
      def toRunnable(value: Stream[IO, I] => Stream[IO, O]): Runnable[I, O] =
        Runnable.fromTransform(value)
    }

  given fromFull: [I, O] => LambdaBuilder[Full[I, O], I, O] =
    new LambdaBuilder[Full[I, O], I, O] {
      def toRunnable(value: Full[I, O]): Runnable[I, O] =
        Runnable.full(value.invoke, value.stream, value.collect, value.transform)
    }

final case class Lambda[I, O](
  runnable: Runnable[I, O],
  config: LambdaConfig
):
  def toRunnable: Runnable[I, O] = runnable

  def withConfig(newConfig: LambdaConfig): Lambda[I, O] =
    copy(config = newConfig)

  def named(name: String): Lambda[I, O] =
    withConfig(config.copy(name = Some(name)))

  def described(description: String): Lambda[I, O] =
    withConfig(config.copy(description = Some(description)))

object Lambda:
  def from[A, I, O](value: A)(using builder: LambdaBuilder[A, I, O]): Lambda[I, O] =
    val runnable: Runnable[I, O] = builder.toRunnable(value)
    val config: LambdaConfig = LambdaConfig()
    Lambda[I, O](runnable, config)

  def apply[I, O](f: I => IO[O]): Lambda[I, O] =
    from[I => IO[O], I, O](f)

  def pure[I, O](f: I => O): Lambda[I, O] =
    val effect: I => IO[O] = (input: I) => IO.pure(f(input))
    from[I => IO[O], I, O](effect)

  def stream[I, O](f: I => Stream[IO, O]): Lambda[I, O] =
    from[I => Stream[IO, O], I, O](f)

  def collect[I, O](f: Stream[IO, I] => IO[O]): Lambda[I, O] =
    from[Stream[IO, I] => IO[O], I, O](f)

  def transform[I, O](f: Stream[IO, I] => Stream[IO, O]): Lambda[I, O] =
    from[Stream[IO, I] => Stream[IO, O], I, O](f)

  def full[I, O](
    invoke: I => IO[O],
    stream: I => Stream[IO, O],
    collect: Stream[IO, I] => IO[O],
    transform: Stream[IO, I] => Stream[IO, O]
  ): Lambda[I, O] =
    val fullValue: LambdaBuilder.Full[I, O] =
      LambdaBuilder.Full(invoke, stream, collect, transform)
    from[LambdaBuilder.Full[I, O], I, O](fullValue)

  given [I, O] => Conversion[I => IO[O], Lambda[I, O]] =
    (f: I => IO[O]) => from[I => IO[O], I, O](f)
