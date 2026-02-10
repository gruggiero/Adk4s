package org.adk4s.agent.tools

import munit.CatsEffectSuite
import cats.effect.IO
import org.adk4s.core.component.Tool
import org.adk4s.core.tools.{ToolsNodeConfig, ToolMiddleware}

class ToolsNodeConfigTest extends CatsEffectSuite:

  test("fromAdkTools creates config with ADK4S tools") {
    val tool = Tool.invokable[IO]("test_tool", "Test tool", _ => Right("result"))
    val config = ToolsNodeConfig.fromAdkTools(List(tool))

    assertEquals(config.tools.length, 1)
    assert(config.tools.head.isRight)
    assertEquals(config.tools.head.map(_.info.name), Right("test_tool"))
  }

  test("builder adds ADK4S tool") {
    val tool = Tool.invokable[IO]("test_tool", "Test tool", _ => Right("result"))
    val config = ToolsNodeConfig.builder
      .withAdkTool(tool)
      .build

    assertEquals(config.tools.length, 1)
    assert(config.tools.head.isRight)
  }

  test("builder sets sequential execution") {
    val config = ToolsNodeConfig.builder
      .sequential
      .build

    assert(config.executeSequentially)
  }

  test("builder sets parallel execution") {
    val config = ToolsNodeConfig.builder
      .parallel(maxConcurrency = 5)
      .build

    assert(!config.executeSequentially)
    assertEquals(config.maxConcurrency, 5)
  }

  test("builder adds middleware") {
    val middleware: ToolMiddleware = identity
    val config = ToolsNodeConfig.builder
      .withMiddleware(middleware)
      .build

    assertEquals(config.middlewares.length, 1)
  }

  test("builder sets unknown tool handler") {
    val handler = (name: String, args: String) => IO.pure(s"Unknown: $name")
    val config = ToolsNodeConfig.builder
      .withUnknownHandler(handler)
      .build

    assert(config.unknownToolHandler.isDefined)
  }

  test("builder chains multiple methods") {
    val tool = Tool.invokable[IO]("test_tool", "Test tool", _ => Right("result"))
    val handler = (name: String, args: String) => IO.pure(s"Unknown: $name")

    val config = ToolsNodeConfig.builder
      .withAdkTool(tool)
      .parallel(maxConcurrency = 5)
      .withUnknownHandler(handler)
      .build

    assertEquals(config.tools.length, 1)
    assert(!config.executeSequentially)
    assertEquals(config.maxConcurrency, 5)
    assert(config.unknownToolHandler.isDefined)
  }
