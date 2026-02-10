package org.adk4s.core.runnable

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.CatsEffectSuite
import org.adk4s.core.component.Tool
import upickle.default.*
import org.adk4s.core.runnable.ToRunnable.*
import scala.language.implicitConversions

extension (n: Double | Int)
  def toIntOrLong: Long = n match
    case i: Int => i.toLong
    case d: Double => d.toLong

class ComponentRunnableTest extends CatsEffectSuite:
  test("InvokableTool converts to Runnable") {
    val tool = Tool.invokable[IO](
      "testTool",
      "Test tool",
      (args: ujson.Value) => Right(ujson.Num(args.numOpt.getOrElse(0).toIntOrLong * 2))
    )

    val runnable = tool.asRunnable[ujson.Value, ujson.Value]

    val result = runnable.invoke(ujson.Num(21))
    assertIO(result, ujson.Num(42))
  }

  test("InvokableTool asRunnable works with all paradigms") {
    val tool = Tool.invokable[IO](
      "testTool",
      "Test tool",
      (args: ujson.Value) => Right(ujson.Num(args.numOpt.getOrElse(0).toIntOrLong * 2))
    )

    val runnable = tool.asRunnable[ujson.Value, ujson.Value]

    val invokeResult = runnable.invoke(ujson.Num(21))
    val streamResult = runnable.stream(ujson.Num(21)).compile.toList
    val collectResult = runnable.collect(Stream.emit(ujson.Num(21)))
    val transformResult = runnable.transform(Stream.emit(ujson.Num(21))).compile.toList

    assertIO(invokeResult, ujson.Num(42))
    assertIO(streamResult, List(ujson.Num(42)))
    assertIO(collectResult, ujson.Num(42))
    assertIO(transformResult, List(ujson.Num(42)))
  }

  test("StreamableTool converts to Runnable") {
    val tool = Tool.streamable[IO](
      "testTool",
      "Test tool",
      (args: ujson.Value) => Stream.emits(args.strOpt.getOrElse("abc").toList.map(_.toString))
    )

    val runnable = tool.asRunnable[ujson.Value, String]

    val result = runnable.stream(ujson.Str("hello")).compile.toList
    assertIO(result, List("h", "e", "l", "l", "o"))
  }

  test("Lambda converts to Runnable") {
    val lambda: Lambda[String, Int] = Lambda((s: String) => IO(s.toInt))

    val runnable = lambda.asRunnable[String, Int]

    val result = runnable.invoke("42")
    assertIO(result, 42)
  }

  test("Lambda asRunnable works with all Lambda variants") {
    val invokeLambda: Lambda[String, Int] = Lambda((s: String) => IO(s.toInt))
    val streamLambda: Lambda[String, Int] = Lambda.stream((s: String) => Stream.emits(s.split(",").map(_.toInt)))

    val invokeRunnable = invokeLambda.asRunnable[String, Int]
    val streamRunnable = streamLambda.asRunnable[String, Int]

    val invokeResult = invokeRunnable.invoke("42")
    val streamResult = streamRunnable.stream("1,2,3").compile.toList

    assertIO(invokeResult, 42)
    assertIO(streamResult, List(1, 2, 3))
  }
