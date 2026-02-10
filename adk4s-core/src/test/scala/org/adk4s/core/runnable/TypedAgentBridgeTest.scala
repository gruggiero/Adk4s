package org.adk4s.core.runnable

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import org.llm4s.agent.orchestration.TypedAgent
import org.llm4s.error.ValidationError

class TypedAgentBridgeTest extends CatsEffectSuite:
  test("TypedAgentBridge converts successful TypedAgent to Runnable") {
    val agent = TypedAgent.fromFunction[Int, String]("testAgent") { (i: Int) =>
      Right(i.toString)
    }

    val runnable = TypedAgentBridge.toRunnable(agent)
    val result = runnable.invoke(42)

    assertIO(result, "42")
  }

  test("TypedAgentBridge converts failing TypedAgent to Runnable") {
    val agent = TypedAgent.fromFunction[Int, String]("failingAgent") { (_: Int) =>
      Left(ValidationError("agent failed", "test"))
    }

    val runnable = TypedAgentBridge.toRunnable(agent)
    val result = runnable.invoke(42).attempt

    result.map {
      case Left(error: RuntimeException) => error.getMessage.startsWith("TypedAgent error:")
      case _ => false
    }.assert
  }

  test("TypedAgentBridge works with stream paradigm") {
    val agent = TypedAgent.fromFunction[Int, String]("streamAgent") { (i: Int) =>
      Right(i.toString)
    }

    val runnable = TypedAgentBridge.toRunnable(agent)
    val result = runnable.stream(123).compile.toList

    assertIO(result, List("123"))
  }

  test("TypedAgentBridge works with collect paradigm") {
    val agent = TypedAgent.fromFunction[Int, String]("collectAgent") { (i: Int) =>
      Right(i.toString)
    }

    val runnable = TypedAgentBridge.toRunnable(agent)
    val result = runnable.collect(fs2.Stream.emits(List(42)))

    assertIO(result, "42")
  }

  test("TypedAgentBridge works with transform paradigm") {
    val agent = TypedAgent.fromFunction[Int, String]("transformAgent") { (i: Int) =>
      Right(i.toString)
    }

    val runnable = TypedAgentBridge.toRunnable(agent)
    val result = runnable.transform(fs2.Stream.emits(List(42))).compile.toList

    assertIO(result, List("42"))
  }
