package org.adk4s.core.tools

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.core.component.{InvokableTool, Tool}
import org.llm4s.toolapi.{ToolFunction, ToolRegistry}

class ToolsNodeConfigExtensionsTest extends CatsEffectSuite:

  private val mockLlm4sTool: ToolFunction[String, String] = ToolFunction[String, String](
    name = "echo",
    description = "Echoes input",
    schema = org.llm4s.toolapi.StringSchema("Input string"),
    handler = _ => Right("echoed")
  )

  private val mockAdkTool: InvokableTool[IO] = Tool.invokable[IO](
    "greet",
    "Greets user",
    (args: ujson.Value) => Right(ujson.Str("Hello!"))
  )

  test("toToolRegistry extracts only LLM4S tools") {
    val config: ToolsNodeConfig = ToolsNodeConfig.builder
      .withTool(mockLlm4sTool)
      .withAdkTool(mockAdkTool)
      .build

    val registry: ToolRegistry = config.toToolRegistry
    assertEquals(registry.tools.length, 1)
    assertEquals(registry.tools.head.name, "echo")
  }

  test("adkTools extracts only ADK4S tools") {
    val config: ToolsNodeConfig = ToolsNodeConfig.builder
      .withTool(mockLlm4sTool)
      .withAdkTool(mockAdkTool)
      .build

    val tools: List[InvokableTool[IO]] = config.adkTools
    assertEquals(tools.length, 1)
    assertEquals(tools.head.info.name, "greet")
  }

  test("toToolRegistry with only ADK tools returns empty registry") {
    val config: ToolsNodeConfig = ToolsNodeConfig.fromAdkTools(List(mockAdkTool))

    val registry: ToolRegistry = config.toToolRegistry
    assertEquals(registry.tools.length, 0)
  }

  test("adkTools with only LLM4S tools returns empty list") {
    val config: ToolsNodeConfig = ToolsNodeConfig.fromToolFunctions(List(mockLlm4sTool))

    val tools: List[InvokableTool[IO]] = config.adkTools
    assertEquals(tools.length, 0)
  }
