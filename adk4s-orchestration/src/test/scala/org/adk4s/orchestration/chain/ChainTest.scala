package org.adk4s.orchestration.chain

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.core.runnable.Lambda
import org.adk4s.core.runnable.Runnable

class ChainTest extends CatsEffectSuite:
  test("create empty chain") {
    val chain: Chain[Int, Int] = Chain[Int]
    val compiled: Runnable[Int, Int] = chain.compile.unsafeRunSync()
    val result: Int = compiled.invoke(42).unsafeRunSync()
    assertEquals(result, 42, "result should match input for empty chain")
  }

  test("append lambda to chain") {
    val chain: Chain[Int, Int] = Chain[Int]
      .appendLambda(Lambda[Int, Int]((x: Int) => IO.pure(x * 2)))

    val compiled: Runnable[Int, Int] = chain.compile.unsafeRunSync()
    val result: Int = compiled.invoke(21).unsafeRunSync()
    assertEquals(result, 42)
  }

  test("append multiple lambdas to chain") {
    val chain: Chain[Int, String] = Chain[Int]
      .appendLambda(Lambda[Int, Int]((x: Int) => IO.pure(x * 2)))
      .appendLambda(Lambda[Int, String]((x: Int) => IO.pure(x.toString)))

    val compiled: Runnable[Int, String] = chain.compile.unsafeRunSync()
    val result: String = compiled.invoke(21).unsafeRunSync()
    assertEquals(result, "42")
  }

  test("appendPassthrough does nothing") {
    val chain: Chain[Int, Int] = Chain[Int]
      .appendLambda(Lambda[Int, Int]((x: Int) => IO.pure(x * 2)))
      .appendPassthrough

    val compiled: Runnable[Int, Int] = chain.compile.unsafeRunSync()
    val result: Int = compiled.invoke(10).unsafeRunSync()
    assertEquals(result, 20)
  }

  test("fromRunnable creates chain from Runnable") {
    val runnable: Runnable[Int, String] = Runnable.fromInvoke((i: Int) => IO.pure(i.toString))
    val chain: Chain[Int, String] = Chain.fromRunnable(runnable)

    val compiled: Runnable[Int, String] = chain.compile.unsafeRunSync()
    val result: String = compiled.invoke(42).unsafeRunSync()
    assertEquals(result, "42")
  }

  test("chain compiles to Graph") {
    val chain: Chain[Int, String] = Chain[Int]
      .appendLambda(Lambda[Int, Int]((x: Int) => IO.pure(x * 2)))
      .appendLambda(Lambda[Int, String]((x: Int) => IO.pure(x.toString)))

    val graph = chain.toGraph.unsafeRunSync()
    assert(graph.nodesMap.nonEmpty, "Graph should have nodes")
    assert(graph.entry.isDefined, "Graph should have entry node")
    assert(graph.endNodesSet.nonEmpty, "Graph should have end nodes")
  }

