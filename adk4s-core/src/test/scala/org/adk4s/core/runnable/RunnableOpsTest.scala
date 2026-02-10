package org.adk4s.core.runnable

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import org.adk4s.core.runnable.RunnableOps

class RunnableOpsTest extends CatsEffectSuite:
  val r1 = Runnable.fromInvoke((s: String) => IO(s.toInt))
  val r2 = Runnable.fromInvoke((i: Int) => IO(i * 2))
  val r3 = Runnable.fromInvoke((i: Int) => IO(i.toString))

  private val boom: RuntimeException = new RuntimeException("boom")

  test("andThen composes two Runnables") {
    val composed = r1.andThen(r2)

    val result = composed.invoke("21")
    assertIO(result, 42)
  }

  test("andThen composes three Runnables") {
    val composed = r1.andThen(r2).andThen(r3)

    val result = composed.invoke("21")
    assertIO(result, "42")
  }

  test("map transforms output") {
    val mapped = r1.map(_ * 10)

    val result = mapped.invoke("5")
    assertIO(result, 50)
  }

  test("evalMap transforms output with effect") {
    val mapped = r1.evalMap(i => IO(i * 10))

    val result = mapped.invoke("5")
    assertIO(result, 50)
  }

  test("contramap transforms input") {
    val contramapped = r1.contramap((s: String) => s * 2)

    val result = contramapped.invoke("21")
    assertIO(result, 2121)
  }

  test("timeout completes within duration") {
    val runnable = Runnable.fromInvoke((_: String) => IO.sleep(100.millis) *> IO(42))
    val timed = runnable.timeout(1.second)

    val result = timed.invoke("test")
    assertIO(result, 42)
  }

  test("timeout fails when exceeded") {
    val runnable = Runnable.fromInvoke((_: String) => IO.sleep(2.seconds) *> IO(42))
    val timed = runnable.timeout(100.millis)

    val result = timed.invoke("test").attempt
    result.map {
      case Left(_: java.util.concurrent.TimeoutException) => true
      case _ => false
    }.assert
  }

  test("handleError catches exceptions") {
    val failing: Runnable[String, String] = Runnable.fromInvoke((s: String) => IO.raiseError(new RuntimeException("boom")))
    val handled = failing.handleError((e: Throwable) => IO.pure("-1"))

    val result = handled.invoke("test")
    assertIO(result, "-1")
  }

  test("parallel runs two Runnables concurrently") {
    val rA = Runnable.fromInvoke((s: String) => IO(s.toInt * 2))
    val rB = Runnable.fromInvoke((s: String) => IO(s.toInt * 3))
    val combined = RunnableOps.parallel(rA, rB)

    val result = combined.invoke("21")
    assertIO(result, (42, 63))
  }

  test("parallel zips streams") {
    val rA = Runnable.fromInvoke((s: String) => IO(s.toInt * 2))
    val rB = Runnable.fromInvoke((s: String) => IO(s.toInt * 3))
    val combined = RunnableOps.parallel(rA, rB)

    val result = combined.stream("21").compile.toList
    assertIO(result, List((42, 63)))
  }

  test("parallel3 runs three Runnables concurrently") {
    val rA = Runnable.fromInvoke((s: String) => IO(s.toInt * 2))
    val rB = Runnable.fromInvoke((s: String) => IO(s.toInt * 3))
    val rC = Runnable.fromInvoke((s: String) => IO(s.toInt + 5))
    val combined = RunnableOps.parallel3(rA, rB, rC)

    val result = combined.invoke("10")
    assertIO(result, (20, 30, 15))
  }

  test("map and contramap can be chained") {
    val pipeline = r1.map(_ * 10).contramap((s: String) => s * 2)

    val result = pipeline.invoke("21")
    assertIO(result, 21210)
  }

  test("andThen works with map") {
    val pipeline = r1.andThen(r2).map(_.toString)

    val result = pipeline.invoke("21")
    assertIO(result, "42")
  }

  test("withFallback (invoke): uses fallback when self fails for all semantics") {
    val failing: Runnable[Int, Int] = Runnable.fromInvoke((_: Int) => IO.raiseError(boom))
    val fallback: Runnable[Int, Int] = Runnable.fromInvoke((i: Int) => IO.pure(i + 1))

    val resumed: IO[Int] = failing.withFallback(fallback, RunnableOps.FallbackSemantic.Resume).invoke(1)
    val atomic: IO[Int] = failing.withFallback(fallback, RunnableOps.FallbackSemantic.Atomic).invoke(1)
    val beforeFirst: IO[Int] = failing.withFallback(fallback, RunnableOps.FallbackSemantic.BeforeFirstElement).invoke(1)

    assertIO(resumed, 2) *> assertIO(atomic, 2) *> assertIO(beforeFirst, 2)
  }

  test("withFallback (stream): Resume appends fallback after failure (partial output preserved)") {
    val self: Runnable[Int, Int] = Runnable.fromStream((_: Int) => Stream.emit[IO, Int](1) ++ Stream.raiseError[IO](boom))
    val fallback: Runnable[Int, Int] = Runnable.fromStream((_: Int) => Stream.emit[IO, Int](9))

    val r: Runnable[Int, Int] = self.withFallback(fallback, RunnableOps.FallbackSemantic.Resume)
    val result: IO[List[Int]] = r.stream(0).compile.toList
    assertIO(result, List(1, 9))
  }

  test("withFallback (stream): Atomic switches entirely to fallback on any failure") {
    val self: Runnable[Int, Int] = Runnable.fromStream((_: Int) => Stream.emit[IO, Int](1) ++ Stream.raiseError[IO](boom))
    val fallback: Runnable[Int, Int] = Runnable.fromStream((_: Int) => Stream.emit[IO, Int](9))

    val r: Runnable[Int, Int] = self.withFallback(fallback, RunnableOps.FallbackSemantic.Atomic)
    val result: IO[List[Int]] = r.stream(0).compile.toList
    assertIO(result, List(9))
  }

  test("withFallback (stream): BeforeFirstElement does not fallback after first element") {
    val self: Runnable[Int, Int] = Runnable.fromStream((_: Int) => Stream.emit[IO, Int](1) ++ Stream.raiseError[IO](boom))
    val fallback: Runnable[Int, Int] = Runnable.fromStream((_: Int) => Stream.emit[IO, Int](9))

    val r: Runnable[Int, Int] = self.withFallback(fallback, RunnableOps.FallbackSemantic.BeforeFirstElement)

    val first: IO[List[Int]] = r.stream(0).take(1).compile.toList
    val attemptedAll: IO[Either[Throwable, List[Int]]] = r.stream(0).compile.toList.attempt

    val assertFirst: IO[Unit] = assertIO(first, List(1))
    val assertAttempt: IO[Unit] = attemptedAll.map {
      case Left(_: RuntimeException) => true
      case _ => false
    }.assert

    assertFirst *> assertAttempt
  }

  test("withFallback (collect): uses fallback when self fails for all semantics") {
    val self: Runnable[Int, Int] = Runnable.fromCollect((_: Stream[IO, Int]) => IO.raiseError(boom))
    val fallback: Runnable[Int, Int] = Runnable.fromCollect((_: Stream[IO, Int]) => IO.pure(9))

    val input: Stream[IO, Int] = Stream.emit(1)

    val resumed: IO[Int] = self.withFallback(fallback, RunnableOps.FallbackSemantic.Resume).collect(input)
    val atomic: IO[Int] = self.withFallback(fallback, RunnableOps.FallbackSemantic.Atomic).collect(input)
    val beforeFirst: IO[Int] = self.withFallback(fallback, RunnableOps.FallbackSemantic.BeforeFirstElement).collect(input)

    assertIO(resumed, 9) *> assertIO(atomic, 9) *> assertIO(beforeFirst, 9)
  }

  test("withFallback (collect): keeps self result when self succeeds for all semantics") {
    val self: Runnable[Int, Int] = Runnable.fromCollect((_: Stream[IO, Int]) => IO.pure(3))
    val fallback: Runnable[Int, Int] = Runnable.fromCollect((_: Stream[IO, Int]) => IO.pure(9))

    val input: Stream[IO, Int] = Stream.emit(1)

    val resumed: IO[Int] = self.withFallback(fallback, RunnableOps.FallbackSemantic.Resume).collect(input)
    val atomic: IO[Int] = self.withFallback(fallback, RunnableOps.FallbackSemantic.Atomic).collect(input)
    val beforeFirst: IO[Int] = self.withFallback(fallback, RunnableOps.FallbackSemantic.BeforeFirstElement).collect(input)

    assertIO(resumed, 3) *> assertIO(atomic, 3) *> assertIO(beforeFirst, 3)
  }

  test("withFallback (stream): BeforeFirstElement falls back when failing before emitting") {
    val self: Runnable[Int, Int] = Runnable.fromStream((_: Int) => Stream.raiseError[IO](boom))
    val fallback: Runnable[Int, Int] = Runnable.fromStream((_: Int) => Stream.emit[IO, Int](9))

    val r: Runnable[Int, Int] = self.withFallback(fallback, RunnableOps.FallbackSemantic.BeforeFirstElement)
    val result: IO[List[Int]] = r.stream(0).compile.toList
    assertIO(result, List(9))
  }

  test("withFallback (transform): Resume appends fallback after failure (partial output preserved)") {
    val self: Runnable[Int, Int] =
      Runnable.fromTransform((_: Stream[IO, Int]) => Stream.emit[IO, Int](1) ++ Stream.raiseError[IO](boom))
    val fallback: Runnable[Int, Int] = Runnable.fromTransform((_: Stream[IO, Int]) => Stream.emit[IO, Int](9))

    val r: Runnable[Int, Int] = self.withFallback(fallback, RunnableOps.FallbackSemantic.Resume)
    val result: IO[List[Int]] = r.transform(Stream.emit[IO, Int](0)).compile.toList
    assertIO(result, List(1, 9))
  }

  test("withFallback (transform): Atomic switches entirely to fallback on any failure") {
    val self: Runnable[Int, Int] =
      Runnable.fromTransform((_: Stream[IO, Int]) => Stream.emit[IO, Int](1) ++ Stream.raiseError[IO](boom))
    val fallback: Runnable[Int, Int] = Runnable.fromTransform((_: Stream[IO, Int]) => Stream.emit[IO, Int](9))

    val r: Runnable[Int, Int] = self.withFallback(fallback, RunnableOps.FallbackSemantic.Atomic)
    val result: IO[List[Int]] = r.transform(Stream.emit[IO, Int](0)).compile.toList
    assertIO(result, List(9))
  }

  test("withFallback (transform): BeforeFirstElement does not fallback after first element") {
    val self: Runnable[Int, Int] =
      Runnable.fromTransform((_: Stream[IO, Int]) => Stream.emit[IO, Int](1) ++ Stream.raiseError[IO](boom))
    val fallback: Runnable[Int, Int] = Runnable.fromTransform((_: Stream[IO, Int]) => Stream.emit[IO, Int](9))

    val r: Runnable[Int, Int] = self.withFallback(fallback, RunnableOps.FallbackSemantic.BeforeFirstElement)

    val first: IO[List[Int]] = r.transform(Stream.emit[IO, Int](0)).take(1).compile.toList
    val attemptedAll: IO[Either[Throwable, List[Int]]] = r.transform(Stream.emit[IO, Int](0)).compile.toList.attempt

    val assertFirst: IO[Unit] = assertIO(first, List(1))
    val assertAttempt: IO[Unit] = attemptedAll.map {
      case Left(_: RuntimeException) => true
      case _ => false
    }.assert

    assertFirst *> assertAttempt
  }
