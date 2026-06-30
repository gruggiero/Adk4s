package org.adk4s.agent.tools

import munit.CatsEffectSuite
import cats.effect.IO
import org.llm4s.llmconnect.model.{ToolCall, ToolMessage, AssistantMessage}
import org.adk4s.core.component.Tool
import org.adk4s.core.tools.ToolsNode
import org.adk4s.core.tools.ToolsNodeRunnable
import org.adk4s.core.tools.ToolsNodeRunnable.*

class ToolsNodeRunnableTest extends CatsEffectSuite:

  test("asRunnable creates Runnable from List[ToolCall]") {
    val tool = Tool.invokable[IO]("echo", "Echo tool", _ => Right(ujson.Str("echo result")))
    val node = ToolsNode.fromAdkTools(List(tool))

    val calls = List(
      ToolCall("call_1", "echo", ujson.Obj("value" -> ujson.Str("test")))
    )

    val result = ToolsNodeRunnable.fromToolCalls(node).invoke(calls).unsafeRunSync()

    assertEquals(result.length, 1)
    assertEquals(result.headOption.getOrElse(fail("expected non-empty list")).toolCallId, "call_1")
  }

  test("fromAssistantMessage creates Runnable") {
    val tool = Tool.invokable[IO]("echo", "Echo tool", _ => Right(ujson.Str("echo result")))
    val node = ToolsNode.fromAdkTools(List(tool))

    val message = AssistantMessage(
      contentOpt = Some("Use echo tool"),
      toolCalls = List(ToolCall("call_1", "echo", ujson.Obj("value" -> ujson.Str("test"))))
    )

    val runnable = ToolsNodeRunnable.fromAssistantMessage(node)
    val result = runnable.invoke(message).unsafeRunSync()

    assertEquals(result.length, 1)
    assertEquals(result.headOption.getOrElse(fail("expected non-empty list")).toolCallId, "call_1")
  }

  test("fromToolCalls creates Runnable") {
    val tool = Tool.invokable[IO]("echo", "Echo tool", _ => Right(ujson.Str("result")))
    val node = ToolsNode.fromAdkTools(List(tool))

    val calls = List(
      ToolCall("call_1", "echo", ujson.Obj())
    )

    val runnable = ToolsNodeRunnable.fromToolCalls(node)
    val result = runnable.invoke(calls).unsafeRunSync()

    assertEquals(result.length, 1)
  }

  test("asStreamingRunnable creates streaming Runnable") {
    val tool = Tool.invokable[IO]("echo", "Echo tool", _ => Right(ujson.Str("result1")))
    val tool2 = Tool.invokable[IO]("echo2", "Echo2 tool", _ => Right(ujson.Str("result2")))
    val node = ToolsNode.fromAdkTools(List(tool, tool2))

    val calls = List(
      ToolCall("call_1", "echo", ujson.Obj()),
      ToolCall("call_2", "echo2", ujson.Obj())
    )

    val runnable = node.asStreamingRunnable
    val stream = runnable.stream(calls)
    val results = stream.compile.toList.unsafeRunSync()

    assertEquals(results.length, 2)
    assert(results.exists(_.content == "result1"), "Should contain result1")
    assert(results.exists(_.content == "result2"), "Should contain result2")
  }

  test("streaming Runnable emits results as they complete") {
    val tool = Tool.invokable[IO]("echo", "Echo tool", _ => Right(ujson.Str("result")))
    val node = ToolsNode.fromAdkTools(List(tool))

    val calls = List(
      ToolCall("call_1", "echo", ujson.Obj())
    )

    val runnable = ToolsNodeRunnable.streaming(node)
    val stream = runnable.stream(calls)
    val results = stream.compile.toList.unsafeRunSync()

    assertEquals(results.length, 1)
    assertEquals(results.headOption.getOrElse(fail("expected non-empty list")).content, "result")
  }
