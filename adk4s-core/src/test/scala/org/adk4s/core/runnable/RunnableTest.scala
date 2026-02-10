package org.adk4s.core.runnable

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.CatsEffectSuite

class RunnableTest extends CatsEffectSuite:
  test("fromInvoke creates Runnable with correct invoke") {
    val f: String => IO[Int] = s => IO(s.toInt)
    val runnable = Runnable.fromInvoke(f)

    val result = runnable.invoke("42")
    assertIO(result, 42)
  }

  test("fromInvoke creates Runnable with correct stream") {
    val f: String => IO[Int] = s => IO(s.toInt)
    val runnable = Runnable.fromInvoke(f)

    val result = runnable.stream("42").compile.toList
    assertIO(result, List(42))
  }

  test("fromInvoke creates Runnable with correct collect") {
    val f: String => IO[Int] = s => IO(s.toInt * 2)
    val runnable = Runnable.fromInvoke(f)

    val result = runnable.collect(Stream.emits(List("1", "2", "3")))
    assertIO(result, 6)
  }

  test("fromInvoke creates Runnable with correct transform") {
    val f: String => IO[Int] = s => IO(s.toInt * 2)
    val runnable = Runnable.fromInvoke(f)

    val result = runnable.transform(Stream.emits(List("1", "2", "3"))).compile.toList
    assertIO(result, List(2, 4, 6))
  }

  test("fromStream creates Runnable with correct invoke") {
    val f: String => Stream[IO, Int] = s => Stream.emits(s.split(",").map(_.toInt))
    val runnable = Runnable.fromStream(f)

    val result = runnable.invoke("1,2,3")
    assertIO(result, 3)
  }

  test("fromStream creates Runnable with correct stream") {
    val f: String => Stream[IO, Int] = s => Stream.emits(s.split(",").map(_.toInt))
    val runnable = Runnable.fromStream(f)

    val result = runnable.stream("1,2,3").compile.toList
    assertIO(result, List(1, 2, 3))
  }

  test("fromStream creates Runnable with correct collect") {
    val f: String => Stream[IO, Int] = s => Stream.emits(s.split(",").map(_.toInt))
    val runnable = Runnable.fromStream(f)

    val result = runnable.collect(Stream.emits(List("1,2", "3,4")))
    assertIO(result, 4)
  }

  test("fromStream creates Runnable with correct transform") {
    val f: String => Stream[IO, Int] = s => Stream.emits(s.split(",").map(_.toInt))
    val runnable = Runnable.fromStream(f)

    val result = runnable.transform(Stream.emits(List("1,2", "3,4"))).compile.toList
    assertIO(result, List(1, 2, 3, 4))
  }

  test("fromCollect creates Runnable with correct invoke") {
    val f: Stream[IO, String] => IO[Int] = stream => stream.compile.toList.map(_.length)
    val runnable = Runnable.fromCollect(f)

    val result = runnable.invoke("test")
    assertIO(result, 1)
  }

  test("fromCollect creates Runnable with correct stream") {
    val f: Stream[IO, String] => IO[Int] = stream => stream.compile.toList.map(_.length)
    val runnable = Runnable.fromCollect(f)

    val result = runnable.stream("test").compile.toList
    assertIO(result, List(1))
  }

  test("fromCollect creates Runnable with correct collect") {
    val f: Stream[IO, String] => IO[Int] = stream => stream.compile.toList.map(_.length)
    val runnable = Runnable.fromCollect(f)

    val result = runnable.collect(Stream.emits(List("a", "b", "c")))
    assertIO(result, 3)
  }

  test("fromCollect creates Runnable with correct transform") {
    val f: Stream[IO, String] => IO[Int] = stream => stream.compile.toList.map(_.length)
    val runnable = Runnable.fromCollect(f)

    val result = runnable.transform(Stream.emits(List("a", "b", "c"))).compile.toList
    assertIO(result, List(3))
  }

  test("fromTransform creates Runnable with correct invoke") {
    val f: Stream[IO, String] => Stream[IO, Int] = stream => stream.map(_.length)
    val runnable = Runnable.fromTransform(f)

    val result = runnable.invoke("test")
    assertIO(result, 4)
  }

  test("fromTransform creates Runnable with correct stream") {
    val f: Stream[IO, String] => Stream[IO, Int] = stream => stream.map(_.length)
    val runnable = Runnable.fromTransform(f)

    val result = runnable.stream("test").compile.toList
    assertIO(result, List(4))
  }

  test("fromTransform creates Runnable with correct collect") {
    val f: Stream[IO, String] => Stream[IO, Int] = stream => stream.map(_.length)
    val runnable = Runnable.fromTransform(f)

    val result = runnable.collect(Stream.emits(List("a", "b", "c")))
    assertIO(result, 1)
  }

  test("fromTransform creates Runnable with correct transform") {
    val f: Stream[IO, String] => Stream[IO, Int] = stream => stream.map(_.length)
    val runnable = Runnable.fromTransform(f)

    val result = runnable.transform(Stream.emits(List("a", "bb", "ccc"))).compile.toList
    assertIO(result, List(1, 2, 3))
  }

  test("full creates Runnable with all explicit paradigms") {
    val invokeFn: String => IO[Int] = s => IO(s.toInt)
    val streamFn: String => Stream[IO, Int] = s => Stream.emit(s.toInt)
    val collectFn: Stream[IO, String] => IO[Int] = stream => stream.compile.last.map(_.get.toInt)
    val transformFn: Stream[IO, String] => Stream[IO, Int] = stream => stream.evalMap(s => IO(s.toInt))

    val runnable = Runnable.full(invokeFn, streamFn, collectFn, transformFn)

    val invokeResult = runnable.invoke("42")
    val streamResult = runnable.stream("42").compile.toList
    val collectResult = runnable.collect(Stream.emit("42"))
    val transformResult = runnable.transform(Stream.emit("42")).compile.toList

    assertIO(invokeResult, 42)
    assertIO(streamResult, List(42))
    assertIO(collectResult, 42)
    assertIO(transformResult, List(42))
  }
