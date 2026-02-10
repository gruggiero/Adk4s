package org.adk4s.core.streaming

import cats.effect.IO
import cats.effect.std.Queue
import fs2.Stream

/**
 * Splits a single stream into multiple streams by extracting fields.
 *
 * Eino equivalent: stream field mapping that fans out a single stream
 * to multiple downstream nodes, each receiving a different field.
 *
 * Uses fs2 queues for fan-out to avoid consuming the source multiple times.
 */
object StreamFieldSplitter:

  /**
   * Split a stream into two derived streams using field extractors.
   * Both output streams are backed by queues fed from a single traversal of the source.
   */
  def split2[I, A, B](
    source: Stream[IO, I],
    extractA: I => A,
    extractB: I => B
  ): IO[(Stream[IO, A], Stream[IO, B])] =
    for
      queueA <- Queue.unbounded[IO, Option[A]]
      queueB <- Queue.unbounded[IO, Option[B]]
    yield
      val feeder: Stream[IO, Unit] = source.evalMap { (item: I) =>
        queueA.offer(Some(extractA(item))) *> queueB.offer(Some(extractB(item)))
      } ++ Stream.eval(queueA.offer(None) *> queueB.offer(None))

      val streamA: Stream[IO, A] = Stream.fromQueueNoneTerminated(queueA)
      val streamB: Stream[IO, B] = Stream.fromQueueNoneTerminated(queueB)

      // The feeder must run concurrently with consumers
      val concurrentA: Stream[IO, A] = streamA.concurrently(feeder)
      (concurrentA, streamB)

  /**
   * Split a stream into three derived streams using field extractors.
   */
  def split3[I, A, B, C](
    source: Stream[IO, I],
    extractA: I => A,
    extractB: I => B,
    extractC: I => C
  ): IO[(Stream[IO, A], Stream[IO, B], Stream[IO, C])] =
    for
      queueA <- Queue.unbounded[IO, Option[A]]
      queueB <- Queue.unbounded[IO, Option[B]]
      queueC <- Queue.unbounded[IO, Option[C]]
    yield
      val feeder: Stream[IO, Unit] = source.evalMap { (item: I) =>
        queueA.offer(Some(extractA(item))) *>
          queueB.offer(Some(extractB(item))) *>
          queueC.offer(Some(extractC(item)))
      } ++ Stream.eval(queueA.offer(None) *> queueB.offer(None) *> queueC.offer(None))

      val streamA: Stream[IO, A] = Stream.fromQueueNoneTerminated(queueA)
      val streamB: Stream[IO, B] = Stream.fromQueueNoneTerminated(queueB)
      val streamC: Stream[IO, C] = Stream.fromQueueNoneTerminated(queueC)

      val concurrentA: Stream[IO, A] = streamA.concurrently(feeder)
      (concurrentA, streamB, streamC)

  /**
   * Inject a static value into each element of a stream.
   * Eino equivalent: SetStaticValue([]string{"SubStr"}, "o")
   */
  def withStaticValue[I, O](
    source: Stream[IO, I],
    combine: (I, O) => I,
    staticValue: O
  ): Stream[IO, I] =
    source.map((item: I) => combine(item, staticValue))
