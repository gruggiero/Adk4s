package org.adk4s.core.batch

import cats.effect.IO
import fs2.Stream
import org.adk4s.core.runnable.Runnable

trait BatchExecutor[I, O]:
  def invokeAll(inputs: List[I]): IO[List[Either[Throwable, O]]]
  def invokeAllPar(inputs: List[I], concurrency: Int): IO[List[Either[Throwable, O]]]
  def stream(inputs: List[I], concurrency: Int): Stream[IO, Either[Throwable, O]]

object BatchExecutor:
  def fromRunnable[I, O](runnable: Runnable[I, O]): BatchExecutor[I, O] =
    new BatchExecutor[I, O]:
      def invokeAll(inputs: List[I]): IO[List[Either[Throwable, O]]] =
        Stream
          .emits(inputs)
          .evalMap((input: I) => runnable.invoke(input).attempt)
          .compile
          .toList

      def invokeAllPar(inputs: List[I], concurrency: Int): IO[List[Either[Throwable, O]]] =
        Stream
          .emits(inputs)
          .parEvalMap(concurrency)((input: I) => runnable.invoke(input).attempt)
          .compile
          .toList

      def stream(inputs: List[I], concurrency: Int): Stream[IO, Either[Throwable, O]] =
        Stream
          .emits(inputs)
          .parEvalMap(concurrency)((input: I) => runnable.invoke(input).attempt)
