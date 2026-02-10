package org.adk4s.core.streaming

import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite

class StreamFieldMergerTest extends CatsEffectSuite:

  test("merge2 zips two streams element-wise") {
    val streamA: Stream[IO, String] = Stream.emits(List("a", "b", "c"))
    val streamB: Stream[IO, Int] = Stream.emits(List(1, 2, 3))
    val result: Stream[IO, String] = StreamFieldMerger.merge2[String, Int, String](
      streamA, streamB, (a: String, b: Int) => s"$a=$b"
    )
    result.compile.toList.map { (items: List[String]) =>
      assertEquals(items, List("a=1", "b=2", "c=3"))
    }
  }

  test("merge2 truncates to shorter stream") {
    val streamA: Stream[IO, Int] = Stream.emits(List(1, 2, 3, 4))
    val streamB: Stream[IO, Int] = Stream.emits(List(10, 20))
    val result: Stream[IO, Int] = StreamFieldMerger.merge2[Int, Int, Int](
      streamA, streamB, (a: Int, b: Int) => a + b
    )
    result.compile.toList.map { (items: List[Int]) =>
      assertEquals(items, List(11, 22))
    }
  }

  test("merge2 handles empty streams") {
    val streamA: Stream[IO, Int] = Stream.empty
    val streamB: Stream[IO, Int] = Stream.emits(List(1, 2))
    val result: Stream[IO, Int] = StreamFieldMerger.merge2[Int, Int, Int](
      streamA, streamB, (a: Int, b: Int) => a + b
    )
    result.compile.toList.map { (items: List[Int]) =>
      assertEquals(items, List.empty[Int])
    }
  }

  test("merge3 zips three streams element-wise") {
    val sA: Stream[IO, String] = Stream.emits(List("x", "y"))
    val sB: Stream[IO, Int] = Stream.emits(List(1, 2))
    val sC: Stream[IO, Boolean] = Stream.emits(List(true, false))
    val result: Stream[IO, String] = StreamFieldMerger.merge3[String, Int, Boolean, String](
      sA, sB, sC, (a: String, b: Int, c: Boolean) => s"$a-$b-$c"
    )
    result.compile.toList.map { (items: List[String]) =>
      assertEquals(items, List("x-1-true", "y-2-false"))
    }
  }

  test("reduceAndMerge2 accumulates and combines") {
    val streamA: Stream[IO, Int] = Stream.emits(List(1, 2, 3))
    val streamB: Stream[IO, Int] = Stream.emits(List(10, 20, 30))
    val result: IO[String] = StreamFieldMerger.reduceAndMerge2[Int, Int, String](
      streamA, streamB,
      (a: Int, b: Int) => a + b,
      (a: Int, b: Int) => a + b,
      (a: Int, b: Int) => s"sum_a=$a, sum_b=$b"
    )
    result.map { (s: String) =>
      assertEquals(s, "sum_a=6, sum_b=60")
    }
  }
