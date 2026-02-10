package org.adk4s.core.tools

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.Tool
import ujson.{Str, Value}

class ToolsNodeConfigTest extends CatsEffectSuite:

  test("fromAdkTools creates config with ADK4S tools") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("test_tool", "Test tool", (_: Value) => Right(Str("result")))
    val config: ToolsNodeConfig = ToolsNodeConfig.fromAdkTools(List(tool))

    assertEquals(config.tools.length, 1)
    assert(config.tools.head.isRight)
    assertEquals(config.tools.head.map(_.info.name), Right("test_tool"))
  }

  test("builder adds ADK4S tool") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("test_tool", "Test tool", (_: Value) => Right(Str("result")))
    val config: ToolsNodeConfig = ToolsNodeConfig.builder
      .withAdkTool(tool)
      .build

    assertEquals(config.tools.length, 1)
    assert(config.tools.head.isRight)
  }

  test("builder sets sequential execution") {
    val config: ToolsNodeConfig = ToolsNodeConfig.builder
      .sequential
      .build

    assert(config.executeSequentially)
  }

  test("builder sets parallel execution") {
    val config: ToolsNodeConfig = ToolsNodeConfig.builder
      .parallel(maxConcurrency = 5)
      .build

    assert(!config.executeSequentially)
    assertEquals(config.maxConcurrency, 5)
  }

  test("builder adds middleware") {
    val middleware: ToolMiddleware = identity
    val config: ToolsNodeConfig = ToolsNodeConfig.builder
      .withMiddleware(middleware)
      .build

    assertEquals(config.middlewares.length, 1)
  }

  test("builder sets unknown tool handler") {
    val handler: (String, String) => IO[String] = (name: String, _: String) => IO.pure(s"Unknown: $name")
    val config: ToolsNodeConfig = ToolsNodeConfig.builder
      .withUnknownHandler(handler)
      .build

    assert(config.unknownToolHandler.isDefined)
  }

  test("builder chains multiple methods") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("test_tool", "Test tool", (_: Value) => Right(Str("result")))
    val handler: (String, String) => IO[String] = (name: String, _: String) => IO.pure(s"Unknown: $name")

    val config: ToolsNodeConfig = ToolsNodeConfig.builder
      .withAdkTool(tool)
      .parallel(maxConcurrency = 5)
      .withUnknownHandler(handler)
      .build

    assertEquals(config.tools.length, 1)
    assert(!config.executeSequentially)
    assertEquals(config.maxConcurrency, 5)
    assert(config.unknownToolHandler.isDefined)
  }
