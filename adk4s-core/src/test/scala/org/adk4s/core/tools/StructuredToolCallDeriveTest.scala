package org.adk4s.core.tools

import cats.effect.IO
import cats.MonadError
import munit.CatsEffectSuite
import org.adk4s.core.component.Tool
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.llmconnect.model.ToolCall

class StructuredToolCallDeriveTest extends CatsEffectSuite:

  // Input/Output types with derived schemas
  final case class WeatherQuery(city: String, unit: String)
  final case class WeatherResult(temperature: Double, condition: String, city: String)

  given ToolSchema[WeatherQuery] = ToolSchema.derive[WeatherQuery]
  given ToolSchema[WeatherResult] = ToolSchema.derive[WeatherResult]

  // Create a tool using ToolInfer
  val weatherTool: org.adk4s.core.component.InvokableTool[IO] =
    ToolInfer.infer[WeatherQuery]("get_weather", "Get weather for a city") { query =>
      // Simulate weather API call
      val result: WeatherResult = WeatherResult(
        temperature = 22.5,
        condition = "sunny",
        city = query.city
      )
      // Encode result using derived ToolSchema
      val encoded: ujson.Value = summon[ToolSchema[WeatherResult]].encoder(result)
      IO.pure(Right(encoded))
    }

  test("ToolSchema.derive works with tool argument parsing and result encoding") {
    // Create a tool call with JSON arguments
    val toolCallArguments: ujson.Value = ujson.Obj(
      "city" -> "Tokyo",
      "unit" -> "celsius"
    )

    // Test 1: Decode arguments using derived ToolSchema
    val querySchema: ToolSchema[WeatherQuery] = summon[ToolSchema[WeatherQuery]]
    val decodedQuery: Either[ToolSchemaError, WeatherQuery] = querySchema.decoder(toolCallArguments)

    assert(decodedQuery.isRight)
    val query: WeatherQuery = decodedQuery.toOption.getOrElse(fail("expected Right"))
    assertEquals(query.city, "Tokyo")
    assertEquals(query.unit, "celsius")

    // Test 2: Execute tool with decoded arguments
    val toolResult: Either[String, ujson.Value] = weatherTool.run(toolCallArguments).attempt.unsafeRunSync() match
      case Right(value) => Right(value)
      case Left(err) => Left(err.getMessage)

    assert(toolResult.isRight)

    // Test 3: Encode result using derived ToolSchema
    val resultSchema: ToolSchema[WeatherResult] = summon[ToolSchema[WeatherResult]]
    val decodedResult: Either[ToolSchemaError, WeatherResult] = resultSchema.decoder(toolResult.toOption.getOrElse(fail("expected Right")))

    assert(decodedResult.isRight)
    val result: WeatherResult = decodedResult.toOption.getOrElse(fail("expected Right"))
    assertEquals(result.city, "Tokyo")
    assertEquals(result.temperature, 22.5)
    assertEquals(result.condition, "sunny")
  }

  test("derived ToolSchema encoder/decoder round-trip works") {
    val original: WeatherResult = WeatherResult(
      temperature = 15.5,
      condition = "cloudy",
      city = "London"
    )

    val schema: ToolSchema[WeatherResult] = summon[ToolSchema[WeatherResult]]

    // Encode
    val encoded: ujson.Value = schema.encoder(original)

    // Decode
    val decoded: Either[ToolSchemaError, WeatherResult] = schema.decoder(encoded)

    assert(decoded.isRight)
    assertEquals(decoded.toOption.getOrElse(fail("expected Right")), original)
  }

  test("derived ToolSchema generates compatible JSON schema") {
    val querySchema: ToolSchema[WeatherQuery] = summon[ToolSchema[WeatherQuery]]
    val jsonSchema: ujson.Value = querySchema.jsonSchema

    // Verify structure matches ToolInfer expectations
    assertEquals(jsonSchema("type").str, "object")

    val properties: ujson.Obj = jsonSchema("properties").obj
    assertEquals(properties("city")("type").str, "string")
    assertEquals(properties("unit")("type").str, "string")

    val required: ujson.Arr = jsonSchema("required").arr
    assertEquals(required.value.size, 2)
    assert(required.value.exists(_.str == "city"))
    assert(required.value.exists(_.str == "unit"))
  }
