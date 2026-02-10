package org.adk4s.core.streaming

import fs2.{Stream, Pipe}
import cats.effect.IO
import scala.concurrent.duration.*
import org.adk4s.core.error.AdkError

object StreamOps:
  def withElementTimeout[A](timeout: FiniteDuration): Pipe[IO, A, A] =
    _.evalMap(a => IO.pure(a).timeout(timeout))

  def withStreamTimeout[A](timeout: FiniteDuration): Pipe[IO, A, A] =
    stream => stream.timeout(timeout)

  def withRetry[A](
    maxRetries: Int,
    initialDelay: FiniteDuration = 1.second,
    maxDelay: FiniteDuration = 30.seconds
  )(stream: => Stream[IO, A]): Stream[IO, A] =
    def loop(retriesLeft: Int, currentDelay: FiniteDuration): Stream[IO, A] =
      stream.handleErrorWith { error =>
        if retriesLeft > 0 then
          Stream.exec(IO.sleep(currentDelay)) ++
          loop(retriesLeft - 1, (currentDelay * 2).min(maxDelay))
        else
          Stream.raiseError[IO](error)
      }
    loop(maxRetries, initialDelay)

  def buffered[A](capacity: Int): Pipe[IO, A, A] =
    _.buffer(capacity)

  def rateLimit[A](elementsPerSecond: Int): Pipe[IO, A, A] =
    _.metered((1.second / elementsPerSecond.max(1)).toMillis.millis)

  def debug[A](prefix: String): Pipe[IO, A, A] =
    _.evalTap(a => IO.println(s"[$prefix] $a"))

  def takeUntilInclusive[A](predicate: A => Boolean): Pipe[IO, A, A] =
    s => s.takeThrough(a => !predicate(a))

  extension [A](stream: Stream[IO, A])
    def withTimeout(timeout: FiniteDuration): Stream[IO, A] =
      stream.through(withStreamTimeout(timeout))
    def withElementTimeout(timeout: FiniteDuration): Stream[IO, A] =
      stream.through(StreamOps.withElementTimeout(timeout))
    def retryWithBackoff(maxRetries: Int): Stream[IO, A] =
      withRetry(maxRetries)(stream)
    def rateLimited(elementsPerSecond: Int): Stream[IO, A] =
      stream.through(rateLimit(elementsPerSecond))
    def debugLog(prefix: String): Stream[IO, A] =
      stream.through(debug(prefix))
