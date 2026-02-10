package org.adk4s.core.batch

import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite
import org.adk4s.core.runnable.Runnable

class BatchExecutorTest extends CatsEffectSuite:

  private val doubler: Runnable[Int, Int] =
    Runnable.fromInvoke[Int, Int]((n: Int) => IO.pure(n * 2))

  private val failOnNegative: Runnable[Int, Int] =
    Runnable.fromInvoke[Int, Int]((n: Int) =>
      if n < 0 then IO.raiseError(new RuntimeException(s"Negative input: $n"))
      else IO.pure(n * 2)
    )

  test("invokeAll processes all inputs sequentially") {
    val executor: BatchExecutor[Int, Int] = BatchExecutor.fromRunnable(doubler)
    val result: IO[List[Either[Throwable, Int]]] = executor.invokeAll(List(1, 2, 3))
    assertIO(result, List(Right(2), Right(4), Right(6)))
  }

  test("invokeAll returns empty list for empty input") {
    val executor: BatchExecutor[Int, Int] = BatchExecutor.fromRunnable(doubler)
    val result: IO[List[Either[Throwable, Int]]] = executor.invokeAll(List.empty)
    assertIO(result, List.empty)
  }

  test("invokeAll isolates errors per item") {
    val executor: BatchExecutor[Int, Int] = BatchExecutor.fromRunnable(failOnNegative)
    val result: IO[List[Either[Throwable, Int]]] = executor.invokeAll(List(1, -2, 3))
    result.map { (results: List[Either[Throwable, Int]]) =>
      assertEquals(results.length, 3)
      assertEquals(results(0), Right(2))
      assert(results(1).isLeft)
      assertEquals(results(2), Right(6))
    }
  }

  test("invokeAllPar processes all inputs in parallel") {
    val executor: BatchExecutor[Int, Int] = BatchExecutor.fromRunnable(doubler)
    val result: IO[List[Either[Throwable, Int]]] = executor.invokeAllPar(List(1, 2, 3, 4, 5), 3)
    result.map { (results: List[Either[Throwable, Int]]) =>
      assertEquals(results.length, 5)
      val values: List[Int] = results.collect { case Right(v) => v }
      assertEquals(values.sorted, List(2, 4, 6, 8, 10))
    }
  }

  test("invokeAllPar isolates errors per item") {
    val executor: BatchExecutor[Int, Int] = BatchExecutor.fromRunnable(failOnNegative)
    val result: IO[List[Either[Throwable, Int]]] = executor.invokeAllPar(List(1, -2, 3, -4, 5), 2)
    result.map { (results: List[Either[Throwable, Int]]) =>
      assertEquals(results.length, 5)
      val successes: List[Int] = results.collect { case Right(v) => v }
      val failures: List[Throwable] = results.collect { case Left(e) => e }
      assertEquals(successes.sorted, List(2, 6, 10))
      assertEquals(failures.length, 2)
    }
  }

  test("stream emits results as they complete") {
    val executor: BatchExecutor[Int, Int] = BatchExecutor.fromRunnable(doubler)
    val result: IO[List[Either[Throwable, Int]]] =
      executor.stream(List(1, 2, 3), 2).compile.toList
    result.map { (results: List[Either[Throwable, Int]]) =>
      assertEquals(results.length, 3)
      val values: List[Int] = results.collect { case Right(v) => v }
      assertEquals(values.sorted, List(2, 4, 6))
    }
  }

  test("stream isolates errors per item") {
    val executor: BatchExecutor[Int, Int] = BatchExecutor.fromRunnable(failOnNegative)
    val result: IO[List[Either[Throwable, Int]]] =
      executor.stream(List(1, -2, 3), 2).compile.toList
    result.map { (results: List[Either[Throwable, Int]]) =>
      assertEquals(results.length, 3)
      val successes: List[Int] = results.collect { case Right(v) => v }
      val failures: List[Throwable] = results.collect { case Left(e) => e }
      assertEquals(successes.sorted, List(2, 6))
      assertEquals(failures.length, 1)
    }
  }
