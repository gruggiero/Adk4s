package org.adk4s.core.tools

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.AdkToolInfo
import org.adk4s.core.component.Tool
import org.adk4s.core.error.AgentInterruptedException
import org.adk4s.core.interrupt.InterruptSignal

class ToolsNodeInterruptTest extends CatsEffectSuite:

  private val normalTool: InvokableTool[IO] = Tool.invokable[IO](
    "normal",
    "A normal tool",
    (args: ujson.Value) => Right(ujson.Str("ok"))
  )

  private val interruptingTool: InvokableTool[IO] = new InvokableTool[IO]:
    def info: AdkToolInfo = AdkToolInfo("interrupting", "Tool that interrupts", ujson.Obj())
    def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None
    def run(arguments: ujson.Value): IO[ujson.Value] =
      IO.raiseError(AgentInterruptedException(InterruptSignal.simple("Approve?")))

  test("sequential execution stops on interrupt") {
    val config: ToolsNodeConfig = ToolsNodeConfig.fromAdkTools(List(normalTool, interruptingTool, normalTool))
      .copy(executeSequentially = true)
    val node: ToolsNode = ToolsNode(config)
    val inputs: List[ToolInput] = List(
      ToolInput("normal", """{}""", "call-1"),
      ToolInput("interrupting", """{}""", "call-2"),
      ToolInput("normal", """{}""", "call-3")
    )
    node.executeTools(inputs).map { (result: ToolExecutionResult) =>
      // First tool should have succeeded
      assertEquals(result.outputs.length, 1)
      assertEquals(result.outputs.headOption.getOrElse(fail("expected non-empty list")).name, "normal")
      // Interrupt signal should be captured
      assert(result.interruptSignal.isDefined)
      assertEquals(result.interruptSignal.getOrElse(fail("expected interrupt signal")).info, "Approve?")
    }
  }

  test("batch result with no interrupts has None signal") {
    val config: ToolsNodeConfig = ToolsNodeConfig.fromAdkTools(List(normalTool))
      .copy(executeSequentially = true)
    val node: ToolsNode = ToolsNode(config)
    val inputs: List[ToolInput] = List(ToolInput("normal", """{}""", "call-1"))
    node.executeTools(inputs).map { (result: ToolExecutionResult) =>
      assertEquals(result.outputs.length, 1)
      assert(result.interruptSignal.isEmpty)
    }
  }

  test("executeTool propagates interrupt exception for single tool") {
    val config: ToolsNodeConfig = ToolsNodeConfig.fromAdkTools(List(interruptingTool))
    val node: ToolsNode = ToolsNode(config)
    val input: ToolInput = ToolInput("interrupting", """{}""", "call-1")
    node.executeTool(input).attempt.map {
      case Left(e: AgentInterruptedException) =>
        assertEquals(e.signal.info, "Approve?")
      case other =>
        fail(s"Expected AgentInterruptedException, got $other")
    }
  }
