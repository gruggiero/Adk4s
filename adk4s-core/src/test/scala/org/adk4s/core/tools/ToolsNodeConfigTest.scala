package org.adk4s.core.tools

import cats.effect.IO
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.CatsEffectSuite
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.Tool
import org.adk4s.core.error.ConfigError
import ujson.{Str, Value}

class ToolsNodeConfigTest extends CatsEffectSuite:

  test("fromAdkTools creates config with ADK4S tools") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("test_tool", "Test tool", (_: Value) => Right(Str("result")))
    val config: ToolsNodeConfig = ToolsNodeConfig.fromAdkTools(List(tool))

    assertEquals(config.tools.length, 1)
    assert(config.tools.headOption.getOrElse(fail("expected non-empty list")).isRight)
    assertEquals(config.tools.headOption.getOrElse(fail("expected non-empty list")).map(_.info.name), Right("test_tool"))
  }

  test("builder adds ADK4S tool") {
    val tool: InvokableTool[IO] = Tool.invokable[IO]("test_tool", "Test tool", (_: Value) => Right(Str("result")))
    val config: ToolsNodeConfig = ToolsNodeConfig.builder
      .withAdkTool(tool)
      .build

    assertEquals(config.tools.length, 1)
    assert(config.tools.headOption.getOrElse(fail("expected non-empty list")).isRight)
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

  // ── Iron refined type: parallelEither ────────────────────────────────────
  // spec: add-iron-refined-types/tools-node — Test oracle

  test("parallelEither accepts positive value") {
    // spec: add-iron-refined-types/tools-node — Scenario: parallelEither accepts positive value
    val result: Either[ConfigError, ToolsNodeConfigBuilder] =
      ToolsNodeConfig.builder.parallelEither(8)
    assert(result.isRight, s"Expected Right, got $result")
    val config: ToolsNodeConfig = result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      b => b.build
    )
    assertEquals(config.maxConcurrency, 8)
    assert(!config.executeSequentially)
  }

  test("parallelEither rejects zero") {
    // spec: add-iron-refined-types/tools-node — Scenario: parallelEither rejects zero
    val result: Either[ConfigError, ToolsNodeConfigBuilder] =
      ToolsNodeConfig.builder.parallelEither(0)
    assert(result.isLeft, s"Expected Left, got $result")
    result match
      case Left(err: ConfigError) =>
        assertEquals(err.field, "maxConcurrency")
        assertEquals(err.invalidValue, "0")
        assertEquals(err.constraint, "Positive")
      case other =>
        fail(s"Expected Left(ConfigError), got $other")
  }

  test("parallelEither rejects negative") {
    // spec: add-iron-refined-types/tools-node — Scenario: Negative is rejected at runtime
    val result: Either[ConfigError, ToolsNodeConfigBuilder] =
      ToolsNodeConfig.builder.parallelEither(-1)
    assert(result.isLeft, s"Expected Left, got $result")
  }

  test("parallel throwing overload still throws for zero") {
    // spec: add-iron-refined-types/tools-node — Scenario: Throwing overload preserved
    intercept[ConfigError]:
      ToolsNodeConfig.builder.parallel(0)
  }

  test("default config has maxConcurrency = 10") {
    // spec: add-iron-refined-types/tools-node — Scenario: Default value 10 remains valid
    val config: ToolsNodeConfig = ToolsNodeConfig()
    assertEquals(config.maxConcurrency, 10)
  }

class ToolsNodeConfigIronSpec extends HedgehogSuite:

  property("maxConcurrency parallelEither round-trips for positive inputs") {
    // spec: add-iron-refined-types/tools-node — Property: maxConcurrency refineEither round-trips
    val gen: Gen[Int] = Gen.int(Range.linear(1, 100))
    for n <- gen.forAll
      yield
        val result: Either[ConfigError, ToolsNodeConfigBuilder] =
          ToolsNodeConfig.builder.parallelEither(n)
        result.map { (b: ToolsNodeConfigBuilder) => b.build.maxConcurrency } ==== Right(n)
  }

  property("maxConcurrency parallelEither rejects zero and negatives") {
    // spec: add-iron-refined-types/tools-node — Property: maxConcurrency rejects zero and negatives
    val gen: Gen[Int] = Gen.int(Range.linear(-100, 0))
    for n <- gen.forAll
      yield
        val result: Either[ConfigError, ToolsNodeConfigBuilder] =
          ToolsNodeConfig.builder.parallelEither(n)
        result.isLeft ==== true
  }
