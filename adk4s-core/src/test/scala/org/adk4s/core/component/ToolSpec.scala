package org.adk4s.core.component

import cats.effect.*
import cats.effect.unsafe.implicits.global
import munit.*
import upickle.default.*

class ToolSpec extends CatsEffectSuite:

  test("Create InvokableTool from function") {
    val tool = Tool.invokable[IO](
      "sum",
      "Calculate sum",
      (args: ujson.Value) => {
        val a = args("a").num.toInt
        val b = args("b").num.toInt
        Right(ujson.Str((a + b).toString))
      }
    )

    assertEquals(tool.info.name, "sum", "Should have correct name")
    assertEquals(tool.info.description, "Calculate sum", "Should have correct description")
    assertEquals(tool.asToolFunction, None, "Should not have ToolFunction")

    val args = ujson.Obj("a" -> 2, "b" -> 3)
    val result = tool.run(args).attempt.unsafeRunSync()
    assert(result.isRight, "Should run successfully")
    assertEquals(result.toOption.getOrElse(fail("expected Right")).str, "5", "Should return correct sum")
  }

  test("Create StreamableTool from function") {
    val tool = Tool.streamable[IO](
      "count",
      "Count numbers",
      (args: ujson.Value) => {
        val count = args("count").num.toInt
        fs2.Stream.emits((1 to count).map(_.toString)).covary[IO]
      }
    )

    assertEquals(tool.info.name, "count", "Should have correct name")

    val args = ujson.Obj("count" -> 3)
    val result = tool.runStream(args).compile.toList.unsafeRunSync()
    assertEquals(result, List("1", "2", "3"), "Should stream numbers")
  }

  test("InvokableTool from function fails with missing parameters") {
    val tool = Tool.invokable[IO](
      "sum",
      "Calculate sum",
      (args: ujson.Value) => {
        val a = args("a").num.toInt
        val b = args("b").num.toInt
        Right(ujson.Str((a + b).toString))
      }
    )

    val args = ujson.Obj("invalid" -> "data")
    val result = tool.run(args).attempt.unsafeRunSync()

    assert(result.isLeft, "Should fail with missing parameters")
  }

  test("StreamableTool from function fails with invalid arguments") {
    val tool = Tool.streamable[IO](
      "count",
      "Count numbers",
      (args: ujson.Value) => {
        val count = args("count").num.toInt
        fs2.Stream.emits((1 to count).map(_.toString)).covary[IO]
      }
    )

    val args = ujson.Obj("invalid" -> "data")
    val result = tool.runStream(args).compile.toList.attempt.unsafeRunSync()

    assert(result.isLeft, "Should fail with missing parameters")
  }
