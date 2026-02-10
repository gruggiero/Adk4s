package org.adk4s.core.streaming

import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite

class StreamFieldSplitterTest extends CatsEffectSuite:

  final case class Pair(a: String, b: Int)

  test("split2 produces two streams with correct elements") {
    val source: Stream[IO, Pair] = Stream.emits(List(
      Pair("hello", 1), Pair("world", 2), Pair("foo", 3)
    ))
    for
      (streamA, streamB) <- StreamFieldSplitter.split2[Pair, String, Int](
        source,
        (p: Pair) => p.a,
        (p: Pair) => p.b
      )
      resultA <- streamA.compile.toList
      resultB <- streamB.compile.toList
    yield {
      assertEquals(resultA, List("hello", "world", "foo"))
      assertEquals(resultB, List(1, 2, 3))
    }
  }

  test("split2 handles empty stream") {
    val source: Stream[IO, Pair] = Stream.empty
    for
      (streamA, streamB) <- StreamFieldSplitter.split2[Pair, String, Int](
        source,
        (p: Pair) => p.a,
        (p: Pair) => p.b
      )
      resultA <- streamA.compile.toList
      resultB <- streamB.compile.toList
    yield {
      assertEquals(resultA, List.empty[String])
      assertEquals(resultB, List.empty[Int])
    }
  }

  test("split2 handles single element") {
    val source: Stream[IO, Pair] = Stream.emit(Pair("only", 42))
    for
      (streamA, streamB) <- StreamFieldSplitter.split2[Pair, String, Int](
        source,
        (p: Pair) => p.a,
        (p: Pair) => p.b
      )
      resultA <- streamA.compile.toList
      resultB <- streamB.compile.toList
    yield {
      assertEquals(resultA, List("only"))
      assertEquals(resultB, List(42))
    }
  }

  test("withStaticValue injects value into each element") {
    final case class WithSubStr(fullStr: String, subStr: String)
    val source: Stream[IO, WithSubStr] = Stream.emits(List(
      WithSubStr("hello", ""), WithSubStr("world", "")
    ))
    val result: Stream[IO, WithSubStr] = StreamFieldSplitter.withStaticValue[WithSubStr, String](
      source,
      (item: WithSubStr, value: String) => item.copy(subStr = value),
      "o"
    )
    result.compile.toList.map { (items: List[WithSubStr]) =>
      assertEquals(items.map(_.subStr), List("o", "o"))
      assertEquals(items.map(_.fullStr), List("hello", "world"))
    }
  }

  test("split3 produces three streams") {
    final case class Triple(a: String, b: Int, c: Boolean)
    val source: Stream[IO, Triple] = Stream.emits(List(
      Triple("x", 1, true), Triple("y", 2, false)
    ))
    for
      (sA, sB, sC) <- StreamFieldSplitter.split3[Triple, String, Int, Boolean](
        source,
        (t: Triple) => t.a,
        (t: Triple) => t.b,
        (t: Triple) => t.c
      )
      rA <- sA.compile.toList
      rB <- sB.compile.toList
      rC <- sC.compile.toList
    yield {
      assertEquals(rA, List("x", "y"))
      assertEquals(rB, List(1, 2))
      assertEquals(rC, List(true, false))
    }
  }
