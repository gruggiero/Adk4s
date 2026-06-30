package org.adk4s.core.tools

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.Tool
import org.llm4s.llmconnect.model.ToolCall
import ujson.{Num, Obj, Str, Value}

class ToolsNodeTest extends CatsEffectSuite:

  test("executeTool runs ADK4S tool") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("double", "Double tool", (args: Value) =>
      Right(Num(args.obj("value").num * 2))
    )
    val node: ToolsNode = ToolsNode.fromAdkTools(List(tool))
    val input: ToolInput = ToolInput("double", """{"value":5}""", "call_1")

    node.executeTool(input).map { (result: ToolOutput) =>
      assertEquals(result.name, "double")
      assertEquals(result.result, "10")
      assertEquals(result.callId, "call_1")
      assert(!result.isError, "Should not have error")
    }
  }

  test("executeFromToolCalls converts and executes") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("echo", "Echo tool", (_: Value) => Right(Str("echo result")))
    val node: ToolsNode = ToolsNode.fromAdkTools(List(tool))

    val calls: List[ToolCall] = List(
      ToolCall("call_1", "echo", Obj("value" -> Str("test")))
    )

    node.executeFromToolCalls(calls).map { (result: ToolExecutionResult) =>
      assertEquals(result.outputs.length, 1)
      assertEquals(result.outputs.headOption.getOrElse(fail("expected non-empty list")).result, "echo result")
      assert(result.allSucceeded, "All tools should succeed")
    }
  }

  test("unknown tool without handler returns error") {
    val node: ToolsNode = ToolsNode.fromAdkTools(List.empty)
    val input: ToolInput = ToolInput("unknown", "{}", "call_1")

    node.executeTool(input).map { (result: ToolOutput) =>
      assert(result.isError, "Should have error")
      assert(result.result.contains("Unknown tool"), "Should contain unknown tool message")
    }
  }

  test("unknown tool with handler uses custom response") {
    val handler: (String, String) => IO[String] = (name: String, _: String) => IO.pure(s"Custom unknown handler: $name")
    val config: ToolsNodeConfig = ToolsNodeConfig.builder
      .withUnknownHandler(handler)
      .build

    val node: ToolsNode = ToolsNode(config)
    val input: ToolInput = ToolInput("unknown", "{}", "call_1")

    node.executeTool(input).map { (result: ToolOutput) =>
      assertEquals(result.result, "Custom unknown handler: unknown")
      assert(!result.isError)
    }
  }

  test("tool execution error is caught") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("failing_tool", "Failing tool", (_: Value) =>
      Left("execution failed")
    )

    val node: ToolsNode = ToolsNode.fromAdkTools(List(tool))
    val input: ToolInput = ToolInput("failing_tool", "{}", "call_1")

    node.executeTool(input).map { (result: ToolOutput) =>
      assert(result.isError, "Should have error")
      assert(result.result.contains("execution failed"), "Should contain error message")
    }
  }

  test("argumentsHandler preprocesses arguments") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("test", "Test tool", (_: Value) => Right(Str("result")))
    val input: ToolInput = ToolInput("test", """{"value":"original"}""", "call_1")

    Ref.of[IO, Option[String]](None).flatMap { (processedArgsRef: Ref[IO, Option[String]]) =>
      val argHandler: (String, String) => IO[String] = (_: String, args: String) =>
        processedArgsRef.set(Some(args)).map((_: Unit) => """{"value":"processed"}""")

      val config: ToolsNodeConfig = ToolsNodeConfig.builder
        .withAdkTool(tool)
        .withArgumentsHandler(argHandler)
        .build

      val node: ToolsNode = ToolsNode(config)

      node.executeTool(input).flatMap { (_: ToolOutput) =>
        processedArgsRef.get.map { (processedArgs: Option[String]) =>
          assertEquals(processedArgs, Some("""{"value":"original"}"""))
        }
      }
    }
  }

  test("middleware is applied") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("test", "Test tool", (_: Value) => Right(Str("result")))
    val input: ToolInput = ToolInput("test", "{}", "call_1")

    Ref.of[IO, Boolean](false).flatMap { (loggedRef: Ref[IO, Boolean]) =>
      val logMiddleware: ToolMiddleware = ToolMiddleware.logging((msg: String) =>
        loggedRef.update((logged: Boolean) => logged || msg.contains("Tool call"))
      )

      val config: ToolsNodeConfig = ToolsNodeConfig.builder
        .withAdkTool(tool)
        .withMiddleware(logMiddleware)
        .build

      val node: ToolsNode = ToolsNode(config)

      node.executeTool(input).flatMap { (_: ToolOutput) =>
        loggedRef.get.map { (logged: Boolean) =>
          assert(logged, "Should have logged")
        }
      }
    }
  }

  test("batch execution with some failures") {
    val successTool: InvokableTool[IO] = Tool.invokable[IO]("success", "Success tool", (_: Value) => Right(Str("result")))
    val failTool: InvokableTool[IO] = Tool.invokable[IO]("fail", "Fail tool", (_: Value) => Left("failed"))

    val node: ToolsNode = ToolsNode.fromAdkTools(List(successTool, failTool))
    val inputs: List[ToolInput] = List(
      ToolInput("success", "{}", "call_1"),
      ToolInput("fail", "{}", "call_2")
    )

    node.executeTools(inputs).map { (result: ToolExecutionResult) =>
      assertEquals(result.outputs.length, 1)
      assertEquals(result.failedTools.length, 1)
      assert(!result.allSucceeded, "Should have failures")
    }
  }
