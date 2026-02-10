package org.adk4s.core.streaming

import cats.effect.IO
import fs2.Stream

/**
 * Merges multiple streams element-wise into a single output stream.
 *
 * Eino equivalent: workflow END node that collects outputs from multiple
 * upstream nodes (e.g., ToField("content_count") + ToField("reasoning_content_count"))
 * and merges them into a single output per chunk.
 */
object StreamFieldMerger:

  /**
   * Merge two streams element-wise using a combine function.
   * Zips elements 1:1 — if one stream is shorter, the output ends at the shorter length.
   */
  def merge2[A, B, O](
    streamA: Stream[IO, A],
    streamB: Stream[IO, B],
    combine: (A, B) => O
  ): Stream[IO, O] =
    streamA.zip(streamB).map { case (a: A, b: B) => combine(a, b) }

  /**
   * Merge three streams element-wise using a combine function.
   */
  def merge3[A, B, C, O](
    streamA: Stream[IO, A],
    streamB: Stream[IO, B],
    streamC: Stream[IO, C],
    combine: (A, B, C) => O
  ): Stream[IO, O] =
    streamA.zip(streamB).zip(streamC).map { case ((a: A, b: B), c: C) => combine(a, b, c) }

  /**
   * Merge two streams by accumulating (reducing) all elements.
   * Useful when the Eino pattern collects stream results into a single aggregate.
   */
  def reduceAndMerge2[A, B, O](
    streamA: Stream[IO, A],
    streamB: Stream[IO, B],
    reduceA: (A, A) => A,
    reduceB: (B, B) => B,
    combine: (A, B) => O
  ): IO[O] =
    for
      a <- streamA.reduce(reduceA).compile.lastOrError
      b <- streamB.reduce(reduceB).compile.lastOrError
    yield combine(a, b)
