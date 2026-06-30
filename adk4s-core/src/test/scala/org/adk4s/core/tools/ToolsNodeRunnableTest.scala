package org.adk4s.core.tools

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.Tool
import org.llm4s.llmconnect.model.{AssistantMessage, ToolCall, ToolMessage}
import ujson.{Obj, Str, Value}

class ToolsNodeRunnableTest extends CatsEffectSuite:

  test("asRunnable creates Runnable from List[ToolCall]") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("echo", "Echo tool", (_: Value) => Right(Str("echo result")))
    val node: ToolsNode = ToolsNode.fromAdkTools(List(tool))

    val calls: List[ToolCall] = List(
      ToolCall("call_1", "echo", Obj("value" -> Str("test")))
    )

    ToolsNodeRunnable.fromToolCalls(node).invoke(calls).map { (result: List[ToolMessage]) =>
      assertEquals(result.length, 1)
      assertEquals(result.headOption.getOrElse(fail("expected non-empty list")).toolCallId, "call_1")
    }
  }

  test("fromAssistantMessage creates Runnable") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("echo", "Echo tool", (_: Value) => Right(Str("echo result")))
    val node: ToolsNode = ToolsNode.fromAdkTools(List(tool))

    val message: AssistantMessage = AssistantMessage(
      contentOpt = Some("Use echo tool"),
      toolCalls = List(ToolCall("call_1", "echo", Obj("value" -> Str("test"))))
    )

    val runnable = ToolsNodeRunnable.fromAssistantMessage(node)

    runnable.invoke(message).map { (result: List[ToolMessage]) =>
      assertEquals(result.length, 1)
      assertEquals(result.headOption.getOrElse(fail("expected non-empty list")).toolCallId, "call_1")
    }
  }

  test("fromToolCalls creates Runnable") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("echo", "Echo tool", (_: Value) => Right(Str("result")))
    val node: ToolsNode = ToolsNode.fromAdkTools(List(tool))

    val calls: List[ToolCall] = List(
      ToolCall("call_1", "echo", Obj())
    )

    val runnable = ToolsNodeRunnable.fromToolCalls(node)

    runnable.invoke(calls).map { (result: List[ToolMessage]) =>
      assertEquals(result.length, 1)
    }
  }

  test("asStreamingRunnable creates streaming Runnable") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("echo", "Echo tool", (_: Value) => Right(Str("result1")))
    val tool2: InvokableTool[IO] = Tool.invokable[IO]("echo2", "Echo2 tool", (_: Value) => Right(Str("result2")))
    val node: ToolsNode = ToolsNode.fromAdkTools(List(tool, tool2))

    val calls: List[ToolCall] = List(
      ToolCall("call_1", "echo", Obj()),
      ToolCall("call_2", "echo2", Obj())
    )

    val runnable = ToolsNodeRunnable.streaming(node)

    runnable.stream(calls).compile.toList.map { (results: List[ToolMessage]) =>
      assertEquals(results.length, 2)
      assert(results.exists((message: ToolMessage) => message.content == "result1"), "Should contain result1")
      assert(results.exists((message: ToolMessage) => message.content == "result2"), "Should contain result2")
    }
  }

  test("streaming Runnable emits results as they complete") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("echo", "Echo tool", (_: Value) => Right(Str("result")))
    val node: ToolsNode = ToolsNode.fromAdkTools(List(tool))

    val calls: List[ToolCall] = List(
      ToolCall("call_1", "echo", Obj())
    )

    val runnable = ToolsNodeRunnable.streaming(node)

    runnable.stream(calls).compile.toList.map { (results: List[ToolMessage]) =>
      assertEquals(results.length, 1)
      assertEquals(results.headOption.getOrElse(fail("expected non-empty list")).content, "result")
    }
  }
