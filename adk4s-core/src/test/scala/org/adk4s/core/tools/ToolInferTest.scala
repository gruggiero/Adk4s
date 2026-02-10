package org.adk4s.core.tools

import cats.effect.IO
import munit.CatsEffectSuite

class ToolInferTest extends CatsEffectSuite:

  final case class BookingArgs(destination: String, passengers: Int, premium: Boolean)

  final case class SearchArgs(query: String, maxResults: Option[Int])

  test("schemaFor derives correct JSON Schema for flat case class") {
    val schema: ujson.Value = ToolInfer.schemaFor[BookingArgs]
    assertEquals(schema("type").str, "object")
    val props: ujson.Value = schema("properties")
    assertEquals(props("destination")("type").str, "string")
    assertEquals(props("passengers")("type").str, "integer")
    assertEquals(props("premium")("type").str, "boolean")
    val required: List[String] = schema("required").arr.map(_.str).toList
    assertEquals(required.sorted, List("destination", "passengers", "premium"))
  }

  test("schemaFor handles optional fields") {
    val schema: ujson.Value = ToolInfer.schemaFor[SearchArgs]
    val props: ujson.Value = schema("properties")
    assertEquals(props("query")("type").str, "string")
    assertEquals(props("maxResults")("type").str, "integer")
    val required: List[String] = schema("required").arr.map(_.str).toList
    // maxResults is Option, so not required
    assertEquals(required, List("query"))
  }

  test("infer creates a working tool") {
    val tool: org.adk4s.core.component.InvokableTool[IO] = ToolInfer.infer[BookingArgs](
      "book_trip",
      "Book a trip"
    ) { (args: BookingArgs) =>
      IO.pure(Right(ujson.Str(s"Booked ${args.passengers} to ${args.destination}, premium=${args.premium}")))
    }
    assertEquals(tool.info.name, "book_trip")
    assertEquals(tool.info.description, "Book a trip")

    val input: ujson.Value = ujson.Obj(
      "destination" -> "Tokyo",
      "passengers" -> 2,
      "premium" -> true
    )
    tool.run(input).map { (result: ujson.Value) =>
      assertEquals(result.str, "Booked 2 to Tokyo, premium=true")
    }
  }

  test("infer tool handles decode errors gracefully") {
    val tool: org.adk4s.core.component.InvokableTool[IO] = ToolInfer.infer[BookingArgs](
      "book_trip",
      "Book a trip"
    ) { (_: BookingArgs) =>
      IO.pure(Right(ujson.Str("ok")))
    }
    // Missing required field
    val badInput: ujson.Value = ujson.Obj("destination" -> "Tokyo")
    tool.run(badInput).attempt.map { (result: Either[Throwable, ujson.Value]) =>
      assert(result.isLeft)
    }
  }

  test("infer tool with optional fields decodes correctly") {
    val tool: org.adk4s.core.component.InvokableTool[IO] = ToolInfer.infer[SearchArgs](
      "search",
      "Search for items"
    ) { (args: SearchArgs) =>
      IO.pure(Right(ujson.Str(s"query=${args.query}, max=${args.maxResults.getOrElse(-1)}")))
    }

    // With optional field present
    val input1: ujson.Value = ujson.Obj("query" -> "cats", "maxResults" -> 10)
    tool.run(input1).map { (result: ujson.Value) =>
      assertEquals(result.str, "query=cats, max=10")
    }
  }

  test("infer tool with optional field absent") {
    val tool: org.adk4s.core.component.InvokableTool[IO] = ToolInfer.infer[SearchArgs](
      "search",
      "Search for items"
    ) { (args: SearchArgs) =>
      IO.pure(Right(ujson.Str(s"query=${args.query}, max=${args.maxResults.getOrElse(-1)}")))
    }

    // Without optional field
    val input2: ujson.Value = ujson.Obj("query" -> "dogs")
    tool.run(input2).map { (result: ujson.Value) =>
      assertEquals(result.str, "query=dogs, max=-1")
    }
  }

  test("schema includes parameters in tool info") {
    val tool: org.adk4s.core.component.InvokableTool[IO] = ToolInfer.infer[BookingArgs](
      "book",
      "Book"
    ) { (_: BookingArgs) => IO.pure(Right(ujson.Null)) }

    val params: ujson.Value = tool.info.parameters
    assertEquals(params("type").str, "object")
    assert(params("properties").obj.contains("destination"))
  }
