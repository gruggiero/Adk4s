package org.adk4s.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.toolapi.ToolRegistry
import ujson.Obj

// ============================================================================
// USAGE EXAMPLES: Structured ToolCall API
// ============================================================================
//
// This test file demonstrates how to use the Structured ToolCall API for
// type-safe tool calling with LLMs. The API provides:
//
// 1. ToolSchema[A] - Define JSON schemas with typed decoders/encoders
// 2. StructuredToolCall[F] - Execute tool calls with typed I/O
// 3. StructuredToolFunction[I, O] - Build typed tool functions
// 4. Error handling via StructuredToolCallError ADT
//
// EXAMPLE: Define a typed tool schema
// ------------------------------------
// case class MyRequest(name: String, count: Int)
//
// given ToolSchema[MyRequest] = ToolSchema.instance[MyRequest](
//   jsonSchema = ujson.Obj(
//     "type" -> "object",
//     "properties" -> ujson.Obj(
//       "name" -> ujson.Obj("type" -> "string"),
//       "count" -> ujson.Obj("type" -> "integer")
//     ),
//     "required" -> ujson.Arr("name", "count")
//   ),
//   description = Some("My request parameters")
// )(
//   decoder = json => for {
//     name <- json.obj.get("name").flatMap(_.strOpt)
//       .toRight(ToolSchemaError.MissingRequiredField("name", ""))
//     count <- json.obj.get("count").flatMap(_.numOpt).map(_.toInt)
//       .toRight(ToolSchemaError.MissingRequiredField("count", ""))
//   } yield MyRequest(name, count),
//   encoder = req => ujson.Obj("name" -> req.name, "count" -> req.count)
// )
//
// EXAMPLE: Create a typed tool function
// -------------------------------------
// val myTool = StructuredToolFunction.pure[MyRequest, MyResult](
//   name = "my_tool",
//   description = "Does something useful",
//   inputSchema = summon[ToolSchema[MyRequest]],
//   outputSchema = summon[ToolSchema[MyResult]],
//   handler = req => MyResult(req.name.toUpperCase, req.count * 2)
// )
//
// EXAMPLE: Use with ToolsNodeConfig
// ---------------------------------
// import org.adk4s.core.tools.StructuredToolFunction.*
//
// val config = ToolsNodeConfig.builder
//   .withStructuredTool(myTool)
//   .parallel(maxConcurrency = 5)
//   .build
//
// val toolsNode = ToolsNode.fromConfig[IO](config)
// val results = toolsNode.executeTools(toolInputs)
//
// ============================================================================

/** Example domain types for testing. */
case class WeatherRequest(location: String, unit: String)
case class WeatherResult(location: String, temperature: Double, unit: String)

/** Tests for the Structured ToolCall API demonstrating usage patterns. */
class StructuredToolCallTest extends CatsEffectSuite:

  given ToolSchema[WeatherRequest] = ToolSchema.instance[WeatherRequest](
    jsonSchema = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "location" -> ujson.Obj("type" -> "string"),
        "unit" -> ujson.Obj("type" -> "string")
      ),
      "required" -> ujson.Arr("location", "unit")
    ),
    description = Some("Weather request parameters")
  )(
    decoder = json =>
      for
        location <- json.obj.get("location").toRight(
          ToolSchemaError.MissingRequiredField("location", "")
        ).flatMap { v =>
          v.strOpt.toRight(
            ToolSchemaError.TypeMismatch("string", v, "location")
          )
        }
        unit <- json.obj.get("unit").toRight(
          ToolSchemaError.MissingRequiredField("unit", "")
        ).flatMap { v =>
          v.strOpt.toRight(
            ToolSchemaError.TypeMismatch("string", v, "unit")
          )
        }
      yield WeatherRequest(location, unit),
    encoder = req => ujson.Obj("location" -> req.location, "unit" -> req.unit)
  )

  given ToolSchema[WeatherResult] = ToolSchema.instance[WeatherResult](
    jsonSchema = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "location" -> ujson.Obj("type" -> "string"),
        "temperature" -> ujson.Obj("type" -> "number"),
        "unit" -> ujson.Obj("type" -> "string")
      ),
      "required" -> ujson.Arr("location", "temperature", "unit")
    ),
    description = Some("Weather result")
  )(
    decoder = json =>
      for
        location <- json.obj.get("location").toRight(
          ToolSchemaError.MissingRequiredField("location", "")
        ).flatMap { v =>
          v.strOpt.toRight(
            ToolSchemaError.TypeMismatch("string", v, "location")
          )
        }
        temperature <- json.obj.get("temperature").toRight(
          ToolSchemaError.MissingRequiredField("temperature", "")
        ).flatMap { v =>
          v.numOpt.toRight(
            ToolSchemaError.TypeMismatch("number", v, "temperature")
          )
        }
        unit <- json.obj.get("unit").toRight(
          ToolSchemaError.MissingRequiredField("unit", "")
        ).flatMap { v =>
          v.strOpt.toRight(
            ToolSchemaError.TypeMismatch("string", v, "unit")
          )
        }
      yield WeatherResult(location, temperature, unit),
    encoder = res => ujson.Obj("location" -> res.location, "temperature" -> res.temperature, "unit" -> res.unit)
  )

  test("4.1 Decode valid arguments successfully") {
    val json = ujson.Obj("location" -> "San Francisco", "unit" -> "celsius")
    val result = summon[ToolSchema[WeatherRequest]].decoder(json)
    assert(result == Right(WeatherRequest("San Francisco", "celsius")))
  }

  test("4.1 Decode arguments with missing required field fails") {
    val json = ujson.Obj("location" -> "San Francisco")
    val result = summon[ToolSchema[WeatherRequest]].decoder(json)
    assert(result.isLeft)
  }

  test("4.1 Decode arguments with invalid type fails") {
    val json = ujson.Obj("location" -> "San Francisco", "unit" -> 123)
    val result = summon[ToolSchema[WeatherRequest]].decoder(json)
    assert(result.isLeft)
    result match
      case Left(_: ToolSchemaError.TypeMismatch) => ()
      case Left(other) => fail(s"Expected TypeMismatch error, got $other")
      case Right(_) => fail("Expected TypeMismatch error")
  }

  test("4.2 Encode typed value to JSON") {
    val result = summon[ToolSchema[WeatherRequest]].encoder(WeatherRequest("San Francisco", "celsius"))
    assertEquals(result, ujson.Obj("location" -> "San Francisco", "unit" -> "celsius"))
  }

  test("4.2 Decode valid result successfully") {
    val json = ujson.Obj("location" -> "San Francisco", "temperature" -> 22.5, "unit" -> "celsius")
    val result = summon[ToolSchema[WeatherResult]].decoder(json)
    assert(result == Right(WeatherResult("San Francisco", 22.5, "celsius")))
  }

  test("4.2 Decode result with invalid JSON fails") {
    val json = ujson.Obj("location" -> "San Francisco", "temperature" -> "hot", "unit" -> "celsius")
    val result = summon[ToolSchema[WeatherResult]].decoder(json)
    assert(result.isLeft)
    result match
      case Left(_: ToolSchemaError.TypeMismatch) => ()
      case Left(other) => fail(s"Expected TypeMismatch error, got $other")
      case Right(_) => fail("Expected TypeMismatch error")
  }

  test("3.2 Create StructuredToolFunction") {
    given ToolSchema[Int] = ToolSchema.instance[Int](
      jsonSchema = ujson.Obj("type" -> "integer"),
      description = None
    )(
      decoder = json => json.numOpt.map(_.toInt).toRight(ToolSchemaError.TypeMismatch("integer", json, "")),
      encoder = n => ujson.Num(n)
    )

    val structuredFunc = StructuredToolFunction.pure[Int, Int](
      name = "triple",
      description = "Triple the input",
      inputSchema = summon[ToolSchema[Int]],
      outputSchema = summon[ToolSchema[Int]],
      handler = n => n * 3
    )

    assertEquals(structuredFunc.name, "triple")
    assertEquals(structuredFunc.description, "Triple the input")

    val result = structuredFunc.handler(10)
    assert(result == Right(30))
  }

  test("3.2 Create StructuredToolFunction with failing handler") {
    given ToolSchema[Int] = ToolSchema.instance[Int](
      jsonSchema = ujson.Obj("type" -> "integer"),
      description = None
    )(
      decoder = json => json.numOpt.map(_.toInt).toRight(ToolSchemaError.TypeMismatch("integer", json, "")),
      encoder = n => ujson.Num(n)
    )

    val structuredFunc = StructuredToolFunction.fromHandler[Int, Int](
      name = "failing",
      description = "Failing tool",
      inputSchema = summon[ToolSchema[Int]],
      outputSchema = summon[ToolSchema[Int]]
    )(
      handler = n => Left(ToolSchemaError.DecodingFailed(msg = "Failed", underlying = None))
    )

    val result = structuredFunc.handler(10)
    assert(result.isLeft)
  }

  test("3.2 Convert StructuredToolFunction to ToolWrapper") {
    import org.adk4s.core.tools.StructuredToolFunction.*

    val structuredFunc = StructuredToolFunction.pure[WeatherRequest, WeatherResult](
      name = "get_weather",
      description = "Get weather for location",
      inputSchema = summon[ToolSchema[WeatherRequest]],
      outputSchema = summon[ToolSchema[WeatherResult]],
      handler = req => WeatherResult(req.location, 22.5, req.unit)
    )

    val wrapper = structuredFunc.toToolWrapper
    assertEquals(wrapper.name, "get_weather")
    assertEquals(wrapper.description, "Get weather for location")

    val validArgs = ujson.Obj("location" -> "Rome", "unit" -> "celsius")
    val result = wrapper.execute(validArgs)
    assert(result.isRight)
    result match
      case Right(json) =>
        assertEquals(json("location").str, "Rome")
        assertEquals(json("temperature").num, 22.5)
        assertEquals(json("unit").str, "celsius")
      case Left(err) => fail(s"Expected success, got $err")
  }

  test("3.2 ToolWrapper returns error for invalid arguments") {
    import org.adk4s.core.tools.StructuredToolFunction.*

    val structuredFunc = StructuredToolFunction.pure[WeatherRequest, WeatherResult](
      name = "get_weather",
      description = "Get weather for location",
      inputSchema = summon[ToolSchema[WeatherRequest]],
      outputSchema = summon[ToolSchema[WeatherResult]],
      handler = req => WeatherResult(req.location, 22.5, req.unit)
    )

    val wrapper = structuredFunc.toToolWrapper
    val invalidArgs = ujson.Obj("location" -> "Rome") // missing "unit"
    val result = wrapper.execute(invalidArgs)
    assert(result.isLeft)
  }
