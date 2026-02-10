package org.adk4s.core.runnable

import fs2.Stream
import cats.effect.IO
import cats.effect.std.Random
import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration

object RunnableOps:
  enum FallbackSemantic:
    case Resume
    case Atomic
    case BeforeFirstElement

  extension [I, O](self: Runnable[I, O])
    def andThen[O2](next: Runnable[O, O2]): Runnable[I, O2] = new Runnable[I, O2]:
      def invoke(input: I): IO[O2] = self.invoke(input).flatMap(next.invoke)
      def stream(input: I): Stream[IO, O2] = self.stream(input).flatMap(o => next.stream(o))
      def collect(input: Stream[IO, I]): IO[O2] = self.collect(input).flatMap(next.invoke)
      def transform(input: Stream[IO, I]): Stream[IO, O2] = next.transform(self.transform(input))

    def map[O2](f: O => O2): Runnable[I, O2] = new Runnable[I, O2]:
      def invoke(input: I): IO[O2] = self.invoke(input).map(f)
      def stream(input: I): Stream[IO, O2] = self.stream(input).map(f)
      def collect(input: Stream[IO, I]): IO[O2] = self.collect(input).map(f)
      def transform(input: Stream[IO, I]): Stream[IO, O2] = self.transform(input).map(f)

    def evalMap[O2](f: O => IO[O2]): Runnable[I, O2] = new Runnable[I, O2]:
      def invoke(input: I): IO[O2] = self.invoke(input).flatMap(f)
      def stream(input: I): Stream[IO, O2] = self.stream(input).evalMap(f)
      def collect(input: Stream[IO, I]): IO[O2] = self.collect(input).flatMap(f)
      def transform(input: Stream[IO, I]): Stream[IO, O2] = self.transform(input).evalMap(f)

    def contramap[I2](f: I2 => I): Runnable[I2, O] = new Runnable[I2, O]:
      def invoke(input: I2): IO[O] = self.invoke(f(input))
      def stream(input: I2): Stream[IO, O] = self.stream(f(input))
      def collect(input: Stream[IO, I2]): IO[O] = self.collect(input.map(f))
      def transform(input: Stream[IO, I2]): Stream[IO, O] = self.transform(input.map(f))

    def timeout(duration: FiniteDuration): Runnable[I, O] = new Runnable[I, O]:
      def invoke(input: I): IO[O] = self.invoke(input).timeout(duration)
      def stream(input: I): Stream[IO, O] = self.stream(input).timeout(duration)
      def collect(input: Stream[IO, I]): IO[O] = self.collect(input).timeout(duration)
      def transform(input: Stream[IO, I]): Stream[IO, O] = self.transform(input).timeout(duration)

    def handleError(handler: Throwable => IO[O]): Runnable[I, O] = new Runnable[I, O]:
      def invoke(input: I): IO[O] = self.invoke(input).handleErrorWith(handler)
      def stream(input: I): Stream[IO, O] = self.stream(input).handleErrorWith(e => Stream.eval(handler(e)))
      def collect(input: Stream[IO, I]): IO[O] = self.collect(input).handleErrorWith(handler)
      def transform(input: Stream[IO, I]): Stream[IO, O] = self.transform(input).handleErrorWith(e => Stream.eval(handler(e)))

    def withRetry(maxRetries: Int, initialDelay: FiniteDuration): Runnable[I, O] = new Runnable[I, O]:
      def invoke(input: I): IO[O] =
        Random.scalaUtilRandom[IO].flatMap { random =>
          def attempt(retriesLeft: Int, delay: FiniteDuration): IO[O] =
            self.invoke(input).handleErrorWith { error =>
              if retriesLeft > 0 then
                for
                  jitter <- random.betweenDouble(0.0, 1.0)
                  delayWithJitter = delay * (1.0 + jitter * 0.1)
                  _ <- IO.sleep(delayWithJitter)
                  result <- attempt(retriesLeft - 1, delay * 2)
                yield result
              else
                IO.raiseError(error)
            }
          attempt(maxRetries, initialDelay)
        }
      def stream(input: I): Stream[IO, O] =
        Stream.eval(invoke(input)).flatMap(Stream.emit)
      def collect(input: Stream[IO, I]): IO[O] =
        input.compile.lastOrError.flatMap(invoke)
      def transform(input: Stream[IO, I]): Stream[IO, O] =
        input.evalMap(invoke)

    def withFallback(
      fallback: Runnable[I, O],
      semantic: RunnableOps.FallbackSemantic = RunnableOps.FallbackSemantic.Resume
    ): Runnable[I, O] = new Runnable[I, O]:
      def invoke(input: I): IO[O] =
        self.invoke(input).handleErrorWith(_ => fallback.invoke(input))
      def stream(input: I): Stream[IO, O] =
        semantic match
          case RunnableOps.FallbackSemantic.Resume =>
            self.stream(input).handleErrorWith(_ => fallback.stream(input))
          case RunnableOps.FallbackSemantic.Atomic =>
            val attempted: IO[Either[Throwable, List[O]]] = self.stream(input).compile.toList.attempt
            Stream.eval(attempted).flatMap {
              case Right(values: List[O]) => Stream.emits(values)
              case Left(_: Throwable) => fallback.stream(input)
            }
          case RunnableOps.FallbackSemantic.BeforeFirstElement =>
            val attemptedFirst: fs2.Pull[IO, Nothing, Either[Throwable, Option[(O, Stream[IO, O])]]] =
              self.stream(input).pull.uncons1.attempt
            attemptedFirst.flatMap {
              case Left(_: Throwable) => fallback.stream(input).pull.echo
              case Right(None) => fs2.Pull.done
              case Right(Some((head: O, tail: Stream[IO, O]))) =>
                fs2.Pull.output1(head) >> tail.pull.echo
            }.stream
      def collect(input: Stream[IO, I]): IO[O] =
        self.collect(input).handleErrorWith(_ => fallback.collect(input))
      def transform(input: Stream[IO, I]): Stream[IO, O] =
        semantic match
          case RunnableOps.FallbackSemantic.Resume =>
            self.transform(input).handleErrorWith(_ => fallback.transform(input))
          case RunnableOps.FallbackSemantic.Atomic =>
            Stream.eval(input.compile.toList).flatMap { (items: List[I]) =>
              val attempted: IO[Either[Throwable, List[O]]] =
                self.transform(Stream.emits(items)).compile.toList.attempt
              Stream.eval(attempted).flatMap {
                case Right(values: List[O]) => Stream.emits(values)
                case Left(_: Throwable) => fallback.transform(Stream.emits(items))
              }
            }
          case RunnableOps.FallbackSemantic.BeforeFirstElement =>
            Stream.eval(input.compile.toList).flatMap { (items: List[I]) =>
              val attemptedFirst: fs2.Pull[IO, Nothing, Either[Throwable, Option[(O, Stream[IO, O])]]] =
                self.transform(Stream.emits(items)).pull.uncons1.attempt
              attemptedFirst.flatMap {
                case Left(_: Throwable) => fallback.transform(Stream.emits(items)).pull.echo
                case Right(None) => fs2.Pull.done
                case Right(Some((head: O, tail: Stream[IO, O]))) =>
                  fs2.Pull.output1(head) >> tail.pull.echo
              }.stream
            }

  def parallel[I, O1, O2](
    r1: Runnable[I, O1],
    r2: Runnable[I, O2]
  ): Runnable[I, (O1, O2)] = new Runnable[I, (O1, O2)]:
    def invoke(input: I): IO[(O1, O2)] = (r1.invoke(input), r2.invoke(input)).parTupled
    def stream(input: I): Stream[IO, (O1, O2)] = r1.stream(input).zip(r2.stream(input))
    def collect(input: Stream[IO, I]): IO[(O1, O2)] =
      input.compile.toList.flatMap { items =>
        (r1.collect(Stream.emits(items)), r2.collect(Stream.emits(items))).parTupled
      }
    def transform(input: Stream[IO, I]): Stream[IO, (O1, O2)] =
      Stream.eval(input.compile.toList).flatMap { items =>
        r1.transform(Stream.emits(items)).zip(r2.transform(Stream.emits(items)))
      }

  def parallel3[I, O1, O2, O3](
    r1: Runnable[I, O1],
    r2: Runnable[I, O2],
    r3: Runnable[I, O3]
  ): Runnable[I, (O1, O2, O3)] = new Runnable[I, (O1, O2, O3)]:
    def invoke(input: I): IO[(O1, O2, O3)] =
      (r1.invoke(input), r2.invoke(input), r3.invoke(input)).parTupled
    def stream(input: I): Stream[IO, (O1, O2, O3)] =
      r1.stream(input).zip(r2.stream(input)).zip(r3.stream(input)).map {
        case ((o1, o2), o3) => (o1, o2, o3)
      }
    def collect(input: Stream[IO, I]): IO[(O1, O2, O3)] =
      input.compile.toList.flatMap { items =>
        (
          r1.collect(Stream.emits(items)),
          r2.collect(Stream.emits(items)),
          r3.collect(Stream.emits(items))
        ).parTupled
      }
    def transform(input: Stream[IO, I]): Stream[IO, (O1, O2, O3)] =
      Stream.eval(input.compile.toList).flatMap { items =>
        r1.transform(Stream.emits(items))
          .zip(r2.transform(Stream.emits(items)))
          .zip(r3.transform(Stream.emits(items)))
          .map { case ((o1, o2), o3) => (o1, o2, o3) }
      }
