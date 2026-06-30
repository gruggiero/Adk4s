package org.adk4s.core.runnable

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.CatsEffectSuite
import scala.language.implicitConversions

class LambdaTest extends CatsEffectSuite:
  test("Lambda.apply toRunnable works correctly") {
    val lambda: Lambda[String, Int] = Lambda((s: String) => IO(s.toInt))
    val runnable: Runnable[String, Int] = lambda.toRunnable

    val result: IO[Int] = runnable.invoke("42")
    assertIO(result, 42)
  }

  test("Lambda.pure toRunnable works correctly") {
    val lambda: Lambda[String, Int] = Lambda.pure((s: String) => s.toInt)
    val runnable: Runnable[String, Int] = lambda.toRunnable

    val result: IO[Int] = runnable.invoke("42")
    assertIO(result, 42)
  }

  test("Lambda.stream toRunnable works correctly") {
    val lambda: Lambda[String, Int] = Lambda.stream((s: String) => Stream.emits(s.split(",").map(_.toInt)))
    val runnable: Runnable[String, Int] = lambda.toRunnable

    val result: IO[List[Int]] = runnable.stream("1,2,3").compile.toList
    assertIO(result, List(1, 2, 3))
  }

  test("Lambda.collect toRunnable works correctly") {
    val lambda: Lambda[String, Int] =
      Lambda.collect((stream: Stream[IO, String]) => stream.compile.toList.map(_.length))
    val runnable: Runnable[String, Int] = lambda.toRunnable

    val result: IO[Int] = runnable.collect(Stream.emits(List("a", "b", "c")))
    assertIO(result, 3)
  }

  test("Lambda.transform toRunnable works correctly") {
    val lambda: Lambda[String, Int] = Lambda.transform((stream: Stream[IO, String]) => stream.map(_.length))
    val runnable: Runnable[String, Int] = lambda.toRunnable

    val result: IO[List[Int]] =
      runnable.transform(Stream.emits(List("a", "bb", "ccc"))).compile.toList
    assertIO(result, List(1, 2, 3))
  }

  test("Lambda.full toRunnable works correctly") {
    val lambda: Lambda[String, Int] = Lambda.full(
      (s: String) => IO(s.toInt),
      (s: String) => Stream.emit(s.toInt),
      (stream: Stream[IO, String]) => stream.compile.last.map(_.getOrElse(fail("expected last element")).toInt),
      (stream: Stream[IO, String]) => stream.evalMap(s => IO(s.toInt))
    )
    val runnable: Runnable[String, Int] = lambda.toRunnable

    val invokeResult: IO[Int] = runnable.invoke("42")
    val streamResult: IO[List[Int]] = runnable.stream("42").compile.toList
    val collectResult: IO[Int] = runnable.collect(Stream.emit("42"))
    val transformResult: IO[List[Int]] = runnable.transform(Stream.emit("42")).compile.toList

    assertIO(invokeResult, 42)
    assertIO(streamResult, List(42))
    assertIO(collectResult, 42)
    assertIO(transformResult, List(42))
  }

  test("Lambda.named sets name in config") {
    val lambda: Lambda[String, Int] = Lambda((s: String) => IO(s.toInt))
    val named: Lambda[String, Int] = lambda.named("myLambda")

    assertEquals(named.config.name, Some("myLambda"))
    assertEquals(named.config.description, None)
  }

  test("Lambda.described sets description in config") {
    val lambda: Lambda[String, Int] = Lambda((s: String) => IO(s.toInt))
    val described: Lambda[String, Int] = lambda.described("My lambda description")

    assertEquals(described.config.name, None)
    assertEquals(described.config.description, Some("My lambda description"))
  }

  test("Lambda can chain named and described") {
    val lambda: Lambda[String, Int] = Lambda((s: String) => IO(s.toInt))
    val configured: Lambda[String, Int] = lambda.named("myLambda").described("My lambda description")

    assertEquals(configured.config.name, Some("myLambda"))
    assertEquals(configured.config.description, Some("My lambda description"))
  }

  test("Implicitly converted Lambda works correctly") {
    val lambda: Lambda[String, Int] = (s: String) => IO(s.toInt)
    val runnable: Runnable[String, Int] = lambda.toRunnable

    val result: IO[Int] = runnable.invoke("42")
    assertIO(result, 42)
  }
