package org.adk4s.core.streaming

import munit.CatsEffectSuite
import cats.effect.IO
import fs2.Stream
import scala.concurrent.duration.*

class StreamOpsTest extends CatsEffectSuite:
  import StreamOps.*

  test("Add timeout to each stream element") {
    val stream = Stream.emits(List(1, 2, 3)).evalMap(x => IO.sleep(10.millis).as(x))
    val resultStream = stream.through(StreamOps.withElementTimeout(1.second))
    val result = resultStream.compile.toList
    assertIO(result.map(_.size), 3)
  }

  test("Add timeout to entire stream") {
    val stream = Stream.emits(List(1, 2, 3)).evalMap(x => IO.sleep(10.millis).as(x))
    val resultStream = stream.through(StreamOps.withStreamTimeout(1.second))
    val result = resultStream.compile.toList
    assertIO(result.map(_.size), 3)
  }

  test("Retry stream on error with exponential backoff") {
    var attempts = 0
    val stream: Stream[IO, Int] = Stream.eval(IO {
      attempts += 1
      if attempts < 3 then throw new RuntimeException("Retry me") else attempts
    })
    val resultStream = StreamOps.withRetry[Int](maxRetries = 5)(stream)
    val result = resultStream.compile.toList
    assertIO(result.map(_.size), 1)
    assertIO(result.map(_.head), 3)
  }

  test("Retry fails after max retries exhausted") {
    var attempts = 0
    val stream: Stream[IO, Int] = Stream.eval(IO {
      attempts += 1
      throw new RuntimeException("Always fails")
    })
    val resultStream = StreamOps.withRetry[Int](maxRetries = 2, initialDelay = 10.millis)(stream)
    val result = resultStream.compile.toList.attempt
    assertIO(result.map(_.isLeft), true)
  }

  test("Buffer stream elements with capacity") {
    val stream = Stream.emits(1 to 20)
    val resultStream = stream.through(StreamOps.buffered(10))
    val result = resultStream.compile.toList
    assertIO(result.map(_.size), 20)
  }

  test("Rate limit stream elements") {
    val start = System.currentTimeMillis()
    val stream = Stream.emits(1 to 5)
    val resultStream = stream.through(StreamOps.rateLimit(5))
    val result = resultStream.compile.toList
    val end = System.currentTimeMillis()
    assertIO(result.map(_.size), 5)
    IO(println(s"Rate limit took ${end - start}ms"))
  }

  test("Rate limit with slow producer") {
    val stream = Stream.emits(1 to 3)
    val resultStream = stream.through(StreamOps.rateLimit(2))
    val result = resultStream.compile.toList
    assertIO(result.map(_.size), 3)
  }

  test("Take elements until condition met inclusive") {
    val stream = Stream.emits(1 to 10)
    val resultStream = stream.through(StreamOps.takeUntilInclusive(_ >= 4))
    val result = resultStream.compile.toList
    assertIO(result.map(_ == List(1, 2, 3, 4)), true)
  }

  test("Take until inclusive with early match") {
    val stream = Stream.emits(1 to 10)
    val resultStream = stream.through(StreamOps.takeUntilInclusive(_ == 1))
    val result = resultStream.compile.toList
    assertIO(result.map(_ == List(1)), true)
  }

  test("Take until inclusive with no match") {
    val stream = Stream.emits(1 to 5)
    val resultStream = stream.through(StreamOps.takeUntilInclusive(_ > 10))
    val result = resultStream.compile.toList
    assertIO(result.map(_.size), 5)
  }

  test("Log stream elements for debugging") {
    val stream = Stream.emits(List("a", "b", "c"))
    val resultStream = stream.through(StreamOps.debug("TEST"))
    val result = resultStream.compile.toList
    assertIO(result, List("a", "b", "c"))
  }
