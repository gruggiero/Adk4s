package org.adk4s.core.tools

// spec: tool-abstraction-dedup — Test oracle (Step 2)
// Tests written from the spec BEFORE implementation.
// These tests reference the NEW API (refactored ToolWrapper with single
// toolFunction field, StructuredToolFunction.toToolFunction method).
// They will NOT compile until Step 3 implements the changes.

import cats.effect.IO
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.FunSuite
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.Tool
import org.llm4s.toolapi.SafeParameterExtractor
import org.llm4s.toolapi.StringSchema
import org.llm4s.toolapi.ToolCallError
import org.llm4s.toolapi.ToolFunction
import org.llm4s.toolapi.ToolRegistry
import ujson.Value

// ─────────────────────────────────────────────────────────────────
// Test domain types
// ─────────────────────────────────────────────────────────────────

case class AddRequest(a: Int, b: Int)
case class AddResult(sum: Int)

class ToolAbstractionDedupSpec extends HedgehogSuite:

  // ───────────────────────────────────────────────────────────────
  // Schema instances for test domain types
  // ───────────────────────────────────────────────────────────────

  given ToolSchema[AddRequest] = ToolSchema.instance[AddRequest](
    jsonSchema = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "a" -> ujson.Obj("type" -> "integer"),
        "b" -> ujson.Obj("type" -> "integer")
      ),
      "required" -> ujson.Arr("a", "b")
    ),
    description = Some("Add request parameters")
  )(
    decoder = json =>
      for
        a <- json.obj.get("a").toRight(
          ToolSchemaError.MissingRequiredField("a", "")
        ).flatMap { v =>
          v.numOpt.map(_.toInt).toRight(
            ToolSchemaError.TypeMismatch("integer", v, "a")
          )
        }
        b <- json.obj.get("b").toRight(
          ToolSchemaError.MissingRequiredField("b", "")
        ).flatMap { v =>
          v.numOpt.map(_.toInt).toRight(
            ToolSchemaError.TypeMismatch("integer", v, "b")
          )
        }
      yield AddRequest(a, b),
    encoder = req => ujson.Obj("a" -> req.a, "b" -> req.b)
  )

  given ToolSchema[AddResult] = ToolSchema.instance[AddResult](
    jsonSchema = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "sum" -> ujson.Obj("type" -> "integer")
      ),
      "required" -> ujson.Arr("sum")
    ),
    description = Some("Add result")
  )(
    decoder = json =>
      json.obj.get("sum").toRight(
        ToolSchemaError.MissingRequiredField("sum", "")
      ).flatMap { v =>
        v.numOpt.map(_.toInt).toRight(
          ToolSchemaError.TypeMismatch("integer", v, "sum")
        )
      }.map(AddResult(_)),
    encoder = res => ujson.Obj("sum" -> res.sum)
  )

  // ───────────────────────────────────────────────────────────────
  // Helper: create a simple llm4s ToolFunction for testing
  // ───────────────────────────────────────────────────────────────

  private def makeEchoTool(name: String = "echo"): ToolFunction[String, String] =
    ToolFunction[String, String](
      name = name,
      description = "Echoes input",
      schema = StringSchema("Input string"),
      handler = (extractor: SafeParameterExtractor) => Right("echoed")
    )

  private def makeAddStructuredTool: StructuredToolFunction[AddRequest, AddResult] =
    StructuredToolFunction.pure[AddRequest, AddResult](
      name = "add",
      description = "Adds two numbers",
      inputSchema = summon[ToolSchema[AddRequest]],
      outputSchema = summon[ToolSchema[AddResult]],
      handler = req => AddResult(req.a + req.b)
    )

  // ═══════════════════════════════════════════════════════════════
  // Requirement: ToolWrapper stores single ToolFunction
  // Scenario: ToolWrapper from ToolFunction
  // ═══════════════════════════════════════════════════════════════

  test("ToolWrapper from ToolFunction stores toolFunction with correct name") {
    // spec: tool-abstraction-dedup — Scenario: ToolWrapper from ToolFunction
    val tf: ToolFunction[String, String] = makeEchoTool("get-weather")
    val wrapper: ToolWrapper = ToolWrapper(tf)
    assertEquals(wrapper.toolFunction.name, "get-weather")
  }

  test("ToolWrapper from ToolFunction delegates execute") {
    // spec: tool-abstraction-dedup — Scenario: ToolWrapper from ToolFunction
    val tf: ToolFunction[String, String] = makeEchoTool("echo")
    val wrapper: ToolWrapper = ToolWrapper(tf)
    val result: Either[Throwable, Value] = wrapper.execute(ujson.Str("hello"))
    assert(result.isRight)
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: ToolWrapper stores single ToolFunction
  // Scenario: ToolWrapper from StructuredToolFunction
  // ═══════════════════════════════════════════════════════════════

  test("ToolWrapper from StructuredToolFunction has toolFunction with correct name") {
    // spec: tool-abstraction-dedup — Scenario: ToolWrapper from StructuredToolFunction
    val stf: StructuredToolFunction[AddRequest, AddResult] = makeAddStructuredTool
    val wrapper: ToolWrapper = stf.toToolWrapper
    assertEquals(wrapper.toolFunction.name, "add")
  }

  test("ToolWrapper from StructuredToolFunction toolFunction is not null") {
    // spec: tool-abstraction-dedup — Scenario: ToolWrapper from StructuredToolFunction
    val stf: StructuredToolFunction[AddRequest, AddResult] = makeAddStructuredTool
    val wrapper: ToolWrapper = stf.toToolWrapper
    // The toolFunction must be a real ToolFunction, not None (the old bug)
    assertEquals(wrapper.toolFunction.name, "add")
    assertEquals(wrapper.toolFunction.description, "Adds two numbers")
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: StructuredToolFunction synthesizes ToolFunction
  // Scenario: Synthesized ToolFunction executes correctly
  // ═══════════════════════════════════════════════════════════════

  test("synthesized ToolFunction executes correctly with valid args") {
    // spec: tool-abstraction-dedup — Scenario: Synthesized ToolFunction executes correctly
    val stf: StructuredToolFunction[AddRequest, AddResult] = makeAddStructuredTool
    val tf: ToolFunction[ujson.Value, ujson.Value] = stf.toToolFunction
    val result: Either[ToolCallError, Value] = tf.execute(ujson.Obj("a" -> 2, "b" -> 3))
    assert(result.isRight)
    result match
      case Right(json) =>
        assertEquals(json.obj("sum").num, 5.0)
      case Left(err) => fail(s"Expected success, got $err")
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: StructuredToolFunction synthesizes ToolFunction
  // Scenario: Synthesized ToolFunction surfaces handler errors
  // ═══════════════════════════════════════════════════════════════

  test("synthesized ToolFunction surfaces handler errors with field detail") {
    // spec: tool-abstraction-dedup — Scenario: Synthesized ToolFunction surfaces handler errors
    // Faithful oracle: the llm4s ToolFunction handler boundary is Either[String, R], so the
    // error surfaces as ToolCallError.HandlerError whose `error` message preserves the
    // field/path from the underlying ToolSchemaError. Assert the variant + message exactly —
    // NO loosened `|| contains` fallback (oracle faithfulness, schema Step 2 rule).
    val stf: StructuredToolFunction[AddRequest, AddResult] = makeAddStructuredTool
    val tf: ToolFunction[ujson.Value, ujson.Value]   = stf.toToolFunction
    val result: Either[ToolCallError, Value]        = tf.execute(ujson.Obj())
    result match
      case Left(err: ToolCallError.HandlerError) =>
        assertEquals(err.toolName, "add")
        assert(err.error.contains("Missing required field 'a'"))
      case Left(other)  => fail(s"Expected HandlerError, got ${other.getClass.getName}: $other")
      case Right(value) => fail(s"Expected error, got $value")
  }

  test("synthesized ToolFunction exposes its input parameters to the LLM") {
    // spec: tool-abstraction-dedup — Scenario: Synthesized ToolFunction LLM-facing schema
    // The synthesized ToolFunction must expose its parameters (names/types/required) so the
    // LLM can call it — not an empty schema (fix for review finding #5).
    val stf: StructuredToolFunction[AddRequest, AddResult] = makeAddStructuredTool
    val tf: ToolFunction[ujson.Value, ujson.Value]         = stf.toToolFunction
    val schema: ujson.Value                                = tf.schema.toJsonSchema(false)
    val props: ujson.Value                                 = schema("properties")
    assertEquals(props("a")("type").str, "integer")
    assertEquals(props("b")("type").str, "integer")
    val required: Set[String] = schema("required").arr.map(_.str).toSet
    assert(required.contains("a") && required.contains("b"))
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: toToolRegistry includes all ToolWrappers
  // Scenario: All tools appear in registry
  // ═══════════════════════════════════════════════════════════════

  test("toToolRegistry includes all ToolWrappers from ToolFunction and StructuredToolFunction") {
    // spec: tool-abstraction-dedup — Scenario: All tools appear in registry
    val tf1: ToolFunction[String, String] = makeEchoTool("echo1")
    val stf: StructuredToolFunction[AddRequest, AddResult] = makeAddStructuredTool
    val tf3: ToolFunction[String, String] = makeEchoTool("echo3")

    val config: ToolsNodeConfig = ToolsNodeConfig.builder
      .withTool(tf1)
      .withStructuredTool(stf)
      .withTool(tf3)
      .build

    val registry: ToolRegistry = config.toToolRegistry
    assertEquals(registry.tools.length, 3)
    val names: Seq[String] = registry.tools.map(_.name)
    assert(names.contains("echo1"))
    assert(names.contains("add"))
    assert(names.contains("echo3"))
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: toToolRegistry includes all ToolWrappers
  // Scenario: No StructuredToolFunction silently dropped
  // ═══════════════════════════════════════════════════════════════

  test("toToolRegistry does not silently drop StructuredToolFunction tools") {
    // spec: tool-abstraction-dedup — Scenario: No StructuredToolFunction silently dropped
    val stf1: StructuredToolFunction[AddRequest, AddResult] =
      StructuredToolFunction.pure[AddRequest, AddResult](
        name = "add1",
        description = "Add 1",
        inputSchema = summon[ToolSchema[AddRequest]],
        outputSchema = summon[ToolSchema[AddResult]],
        handler = req => AddResult(req.a + req.b)
      )
    val stf2: StructuredToolFunction[AddRequest, AddResult] =
      StructuredToolFunction.pure[AddRequest, AddResult](
        name = "add2",
        description = "Add 2",
        inputSchema = summon[ToolSchema[AddRequest]],
        outputSchema = summon[ToolSchema[AddResult]],
        handler = req => AddResult(req.a + req.b)
      )

    val config: ToolsNodeConfig = ToolsNodeConfig.builder
      .withStructuredTool(stf1)
      .withStructuredTool(stf2)
      .build

    val registry: ToolRegistry = config.toToolRegistry
    assertEquals(registry.tools.length, 2)
    val names: Seq[String] = registry.tools.map(_.name)
    assert(names.contains("add1"))
    assert(names.contains("add2"))
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: ToolWrapper.execute derives executable on demand
  // Scenario: Execute delegates to derived executable
  // ═══════════════════════════════════════════════════════════════

  test("ToolWrapper.execute delegates to toolFunction.execute for valid args") {
    // spec: tool-abstraction-dedup — Scenario: Execute delegates to derived executable
    val tf: ToolFunction[String, String] = makeEchoTool("echo")
    val wrapper: ToolWrapper = ToolWrapper(tf)
    val result: Either[Throwable, Value] = wrapper.execute(ujson.Str("hello"))
    assert(result.isRight)
    result match
      case Right(json) => assertEquals(json.str, "echoed")
      case Left(err)   => fail(s"Expected success, got $err")
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: ToolsNodeConfigBuilder.withStructuredTool produces visible tool
  // Scenario: withStructuredTool tool appears in registry
  // ═══════════════════════════════════════════════════════════════

  test("withStructuredTool produces a tool visible in toToolRegistry") {
    // spec: tool-abstraction-dedup — MODIFIED Requirement: withStructuredTool produces visible tool
    val stf: StructuredToolFunction[AddRequest, AddResult] = makeAddStructuredTool
    val config: ToolsNodeConfig = ToolsNodeConfig.builder
      .withStructuredTool(stf)
      .build
    val registry: ToolRegistry = config.toToolRegistry
    assertEquals(registry.tools.length, 1)
    val headTool: ToolFunction[?, ?] = registry.tools.headOption.getOrElse(fail("expected non-empty"))
    assertEquals(headTool.name, "add")
  }

  // ═══════════════════════════════════════════════════════════════
  // Compile-Negative: ToolWrapper with old fields must not compile
  // ═══════════════════════════════════════════════════════════════

  test("ToolWrapper with originalToolFunction field does not compile") {
    // spec: tool-abstraction-dedup — Compile-Negative: ToolWrapper(originalToolFunction = ...)
    // After the refactor, the old constructor with originalToolFunction and
    // executable fields is gone. Using compileErrors to verify it fails.
    val errors: String = compileErrors("""
      val wrapper: ToolWrapper = ToolWrapper(
        originalToolFunction = None,
        executable = ???,
        name = "x",
        description = "y"
      )
    """)
    assert(errors.nonEmpty)
  }

  // ═══════════════════════════════════════════════════════════════
  // Property 1: All ToolWrappers appear in ToolRegistry
  // spec: tool-abstraction-dedup — Property: All ToolWrappers appear in ToolRegistry
  // ═══════════════════════════════════════════════════════════════

  property("all ToolWrappers appear in ToolRegistry") {
    // Generator strategy: constructive — genToolCount generates the number of
    // tools, then we build a config with that many ToolFunction-derived tools.
    // Classify by tool count.
    val toolCountGen: Gen[Int] = Gen.int(Range.linear(0, 10))
    toolCountGen.forAll.map { (count: Int) =>
      val tools: List[ToolFunction[String, String]] = (1 to count).toList.map { i =>
        makeEchoTool(s"tool-$i")
      }
      val config: ToolsNodeConfig = ToolsNodeConfig.fromToolFunctions(tools)
      val registry: ToolRegistry = config.toToolRegistry
      registry.tools.length ==== count
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Property 2: ToolWrapper execute matches toolFunction.execute
  // spec: tool-abstraction-dedup — Property: ToolWrapper execute matches toolFunction.execute
  // ═══════════════════════════════════════════════════════════════

  property("ToolWrapper.execute matches toolFunction.execute for all args") {
    // Generator strategy: constructive — genToolName generates tool names,
    // genArgs generates JSON values. Classify by arg type.
    val toolNameGen: Gen[String] = Gen.string(Gen.alpha, Range.linear(1, 10))
    val argsGen: Gen[Value] = Gen.element1[Value](
      ujson.Str("hello"),
      ujson.Num(42.0),
      ujson.Obj("key" -> ujson.Str("value")),
      ujson.Arr(ujson.Num(1.0), ujson.Num(2.0)),
      ujson.Null,
      ujson.Bool(true)
    )
    val pairGen: Gen[(String, Value)] = for
      name <- toolNameGen
      args <- argsGen
    yield (name, args)
    pairGen.forAll.map { (name: String, args: Value) =>
      val tf: ToolFunction[String, String] = makeEchoTool(name)
      val wrapper: ToolWrapper = ToolWrapper(tf)
      val wrapperResult: Either[Throwable, Value] = wrapper.execute(args)
      // Use tf.execute directly (ToolWrapper delegates to toolFunction.execute)
      val adapterResult: Either[ToolCallError, Value] = tf.execute(args)
      // Both should have the same Right/Left structure
      (wrapperResult.isRight ==== adapterResult.isRight)
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Property 3: Synthesized ToolFunction name matches StructuredToolFunction name
  // spec: tool-abstraction-dedup — Property: Synthesized ToolFunction name matches
  // ═══════════════════════════════════════════════════════════════

  property("synthesized ToolFunction name matches StructuredToolFunction name") {
    // Generator strategy: constructive — genToolName generates tool names.
    // Classify by name length.
    val toolNameGen: Gen[String] = Gen.string(Gen.alpha, Range.linear(1, 20))
    toolNameGen.forAll.map { (name: String) =>
      val stf: StructuredToolFunction[AddRequest, AddResult] =
        StructuredToolFunction.pure[AddRequest, AddResult](
          name = name,
          description = "test tool",
          inputSchema = summon[ToolSchema[AddRequest]],
          outputSchema = summon[ToolSchema[AddResult]],
          handler = req => AddResult(req.a + req.b)
        )
      val tf: ToolFunction[ujson.Value, ujson.Value] = stf.toToolFunction
      tf.name ==== stf.name
    }
  }
