package org.adk4s.examples.eino.components

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.tools.JsonFixMiddleware
import org.adk4s.core.tools.ToolInfer
import org.adk4s.core.tools.{StructuredToolCall, ToolSchema, TypedTool}
import org.adk4s.examples.eino.common.ExampleUtils

/**
 * Eino equivalent: components/tool (jsonschema + middlewares/jsonfix)
 *
 * Demonstrates:
 *   1. Tool schema inference from case classes using ToolInfer
 *   2. JSON fix middleware that repairs malformed LLM arguments
 *   3. StructuredToolCall for compile-time type-safe tool execution
 *
 * Eino uses Go reflection + jsonschema library to derive tool schemas.
 * In adk4s, we use Scala 3 Mirror + inline macros for compile-time derivation.
 */
object ToolSchemaExample extends IOApp.Simple:

  // --- Domain types ---

  final case class BookingArgs(
    destination: String,
    passengers: Int,
    premium: Boolean
  )

  final case class SearchArgs(
    query: String,
    maxResults: Option[Int]
  )

  final case class BookingResult(
    confirmation: String,
    premium: Boolean,
    price: Double
  )

  // --- Scenarios ---

  private def runSchemaInference: IO[Unit] =
    for
      _ <- ExampleUtils.printSubSection("Scenario 1: Schema Inference from Case Class")

      // Derive schema for BookingArgs
      bookingSchema = ToolInfer.schemaFor[BookingArgs]
      _ <- IO.println(s"   BookingArgs schema:")
      _ <- IO.println(s"   ${ujson.write(bookingSchema, indent = 2).split("\n").mkString("\n   ")}")

      // Derive schema for SearchArgs (with optional field)
      searchSchema = ToolInfer.schemaFor[SearchArgs]
      _ <- IO.println(s"\n   SearchArgs schema:")
      _ <- IO.println(s"   ${ujson.write(searchSchema, indent = 2).split("\n").mkString("\n   ")}")
    yield ()

  private def runToolCreation: IO[Unit] =
    for
      _ <- ExampleUtils.printSubSection("Scenario 2: Create Tool with Inferred Schema")

      tool = ToolInfer.infer[BookingArgs]("book_trip", "Book a trip to a destination") {
        (args: BookingArgs) =>
          IO.pure(Right(ujson.Obj(
            "confirmation" -> s"Booked ${args.passengers} passengers to ${args.destination}",
            "premium" -> args.premium,
            "price" -> (if args.premium then args.passengers * 500.0 else args.passengers * 200.0)
          )))
      }

      _ <- IO.println(s"   Tool name: ${tool.info.name}")
      _ <- IO.println(s"   Tool description: ${tool.info.description}")
      _ <- IO.println(s"   Tool parameters: ${ujson.write(tool.info.parameters)}")

      // Execute the tool
      input = ujson.Obj("destination" -> "Tokyo", "passengers" -> 3, "premium" -> true)
      result <- tool.run(input)
      _ <- IO.println(s"   Invocation result: ${ujson.write(result, indent = 2)}")
    yield ()

  private def runJsonFix: IO[Unit] =
    for
      _ <- ExampleUtils.printSubSection("Scenario 3: JSON Fix Middleware")

      testCases = List(
        ("Valid JSON", """{"name": "test", "value": 42}"""),
        ("Trailing comma", """{"name": "test", "value": 42,}"""),
        ("Single quotes", """{'name': 'test', 'value': 42}"""),
        ("LLM artifacts", """<think>{"name": "test"}</think>"""),
        ("Code fences", "```json\n{\"name\": \"test\"}\n```"),
        ("Surrounding noise", """Here is the JSON: {"name": "test"} hope that helps!"""),
        ("Missing brace", """"name": "test"}""")
      )

      _ <- testCases.foldLeft(IO.unit) { case (acc, (label, input)) =>
        acc *> {
          val fixed: String = JsonFixMiddleware.repair(input)
          IO.println(s"   $label:")
            *> IO.println(s"     Input:  $input")
            *> IO.println(s"     Output: $fixed")
        }
      }
    yield ()

  private def runStructuredToolCall: IO[Unit] =
    // Derive ToolSchema instances for type-safe execution
    given ToolSchema[BookingArgs] = ToolSchema.derive[BookingArgs]
    given ToolSchema[BookingResult] = ToolSchema.derive[BookingResult]

    for
      _ <- ExampleUtils.printSubSection("Scenario 4: Execute with StructuredToolCall")

      // Create a TypedTool with compile-time type safety
      typedTool: TypedTool[IO, BookingArgs, BookingResult] =
        StructuredToolCall.createTool[IO, BookingArgs, BookingResult](
          toolName = "book_trip_typed",
          toolDescription = "Type-safe booking with guaranteed input/output types"
        ) { (args: BookingArgs) =>
          val price: Double = if args.premium then args.passengers * 500.0 else args.passengers * 200.0
          val confirmation: String = s"Booked ${args.passengers} passengers to ${args.destination}"
          IO.pure(BookingResult(confirmation, args.premium, price))
        }

      _ <- IO.println(s"   Tool name: ${typedTool.name}")
      _ <- IO.println(s"   Tool description: ${typedTool.description}")

      // Execute with typed arguments - compile-time checked!
      typedInput = BookingArgs(destination = "Paris", passengers = 2, premium = false)
      _ <- IO.println(s"\n   Input: $typedInput")

      typedResult: BookingResult <- typedTool.execute(typedInput)
      _ <- IO.println(s"   Result: $typedResult")
      _ <- IO.println(s"   Confirmation: ${typedResult.confirmation}")
      _ <- IO.println(f"   Price: $$${typedResult.price}%.2f")

      // Demonstrate asInvokableTool conversion for registry compatibility
      _ <- IO.println(s"\n   Converting to InvokableTool for registry compatibility...")
      invokableTool = typedTool.asInvokableTool
      _ <- IO.println(s"   Converted successfully - can be added to ToolRegistry for dynamic dispatch")
    yield ()

  // --- Main ---

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("ToolSchema Example (Eino: components/tool)")
      _ <- runSchemaInference
      _ <- runToolCreation
      _ <- runJsonFix
      _ <- runStructuredToolCall
      _ <- IO.println("\n=== ToolSchema Example Completed ===")
    yield ()
