package org.adk4s.agent.tools

import munit.CatsEffectSuite
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.llmconnect.model.ToolCall
import org.adk4s.core.component.Tool
import org.adk4s.core.tools.{ToolsNode, ToolsNodeConfig, ToolInput, ToolOutput, ToolMiddleware}
import upickle.default.*
import scala.collection.mutable.ListBuffer

class ToolsNodeTest extends CatsEffectSuite:

  test("executeTool runs ADK4S tool") {
    val tool = Tool.invokable[IO]("double", "Double tool", args =>
      Right(ujson.Num(args.obj("value").num * 2))
    )
    val node = ToolsNode.fromAdkTools(List(tool))

    val input = ToolInput("double", """{"value":5}""", "call_1")
    val result = node.executeTool(input).unsafeRunSync()

    assertEquals(result.name, "double")
    assertEquals(result.result, "10")
    assertEquals(result.callId, "call_1")
    assert(!result.isError, "Should not have error")
  }


  test("executeFromToolCalls converts and executes") {
    val tool = Tool.invokable[IO]("echo", "Echo tool", _ => Right(ujson.Str("echo result")))
    val node = ToolsNode.fromAdkTools(List(tool))

    val calls = List(
      ToolCall("call_1", "echo", ujson.Obj("value" -> ujson.Str("test")))
    )
    val result = node.executeFromToolCalls(calls).unsafeRunSync()

    assertEquals(result.outputs.length, 1)
    assertEquals(result.outputs.head.result, "echo result")
    assert(result.allSucceeded, "All tools should succeed")
  }

  test("unknown tool without handler returns error") {
    val node = ToolsNode.fromAdkTools(List.empty)

    val input = ToolInput("unknown", """{}""", "call_1")
    val result = node.executeTool(input).unsafeRunSync()

    assert(result.isError, "Should have error")
    assert(result.result.contains("Unknown tool"), "Should contain unknown tool message")
  }

  test("unknown tool with handler uses custom response") {
    val handler = (name: String, args: String) => IO.pure(s"Custom unknown handler: $name")
    val config = ToolsNodeConfig.builder
      .withUnknownHandler(handler)
      .build

    val node = ToolsNode(config)
    val input = ToolInput("unknown", """{}""", "call_1")
    val result = node.executeTool(input).unsafeRunSync()

    assertEquals(result.result, "Custom unknown handler: unknown")
    assert(!result.isError)
  }

  test("tool execution error is caught") {
    val tool = Tool.invokable[IO]("failing_tool", "Failing tool", _ => 
      throw new RuntimeException("execution failed")
    )

    val node = ToolsNode.fromAdkTools(List(tool))
    val input = ToolInput("failing_tool", """{}""", "call_1")
    val result = node.executeTool(input).unsafeRunSync()

    assert(result.isError, "Should have error")
    assert(result.result.contains("execution failed"), "Should contain error message")
  }


  test("argumentsHandler preprocesses arguments") {
    var processedArgs: Option[String] = None
    val argHandler = (name: String, args: String) => IO {
      processedArgs = Some(args)
      """{"value":"processed"}"""
    }

    val tool = Tool.invokable[IO]("test", "Test tool", _ => Right(ujson.Str("result")))

    val config = ToolsNodeConfig.builder
      .withAdkTool(tool)
      .withArgumentsHandler(argHandler)
      .build

    val node = ToolsNode(config)
    val input = ToolInput("test", """{"value":"original"}""", "call_1")
    node.executeTool(input).unsafeRunSync()

    assertEquals(processedArgs, Some("""{"value":"original"}"""))
  }

  test("middleware is applied") {
    var logged = false
    val logMiddleware: ToolMiddleware = ToolMiddleware.logging(msg => IO {
      if msg.contains("Tool call") then logged = true
    })

    val tool = Tool.invokable[IO]("test", "Test tool", _ => Right(ujson.Str("result")))

    val config = ToolsNodeConfig.builder
      .withAdkTool(tool)
      .withMiddleware(logMiddleware)
      .build

    val node = ToolsNode(config)
    val input = ToolInput("test", """{}""", "call_1")
    node.executeTool(input).unsafeRunSync()

    assert(logged, "Should have logged")
  }

  test("batch execution with some failures") {
    val successTool = Tool.invokable[IO]("success", "Success tool", _ => Right(ujson.Str("result")))
    val failTool = Tool.invokable[IO]("fail", "Fail tool", _ => 
      throw new RuntimeException("failed")
    )

    val node = ToolsNode.fromAdkTools(List(successTool, failTool))
    val inputs = List(
      ToolInput("success", """{}""", "call_1"),
      ToolInput("fail", """{}""", "call_2")
    )

    val result = node.executeTools(inputs).unsafeRunSync()

    assertEquals(result.outputs.length, 1)
    assertEquals(result.failedTools.length, 1)
    assert(!result.allSucceeded, "Should have failures")
  }
