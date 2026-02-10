package org.adk4s.core.tools

import cats.effect.IO
import munit.CatsEffectSuite

class TypedToolTest extends CatsEffectSuite:

  // Test case classes
  final case class BookingArgs(destination: String, passengers: Int)
  final case class BookingResult(confirmation: String, price: Double)

  // Derive schemas
  given ToolSchema[BookingResult] = ToolSchema.derive[BookingResult]

  test("createTool creates a working TypedTool") {
    val bookTool: TypedTool[IO, BookingArgs, BookingResult] =
      StructuredToolCall.createTool[IO, BookingArgs, BookingResult](
        "book_trip",
        "Book a trip to a destination"
      ) { (args: BookingArgs) =>
        IO.pure(BookingResult(
          confirmation = s"Confirmed trip to ${args.destination}",
          price = args.passengers * 200.0
        ))
      }

    // Test typed execution
    val result: IO[BookingResult] = bookTool.execute(BookingArgs("Paris", 2))

    result.map { (res: BookingResult) =>
      assertEquals(res.confirmation, "Confirmed trip to Paris")
      assertEquals(res.price, 400.0)
    }
  }

  test("TypedTool.asInvokableTool converts to InvokableTool") {
    val bookTool: TypedTool[IO, BookingArgs, BookingResult] =
      StructuredToolCall.createTool[IO, BookingArgs, BookingResult](
        "book_trip",
        "Book a trip to a destination"
      ) { (args: BookingArgs) =>
        IO.pure(BookingResult(
          confirmation = s"Confirmed trip to ${args.destination}",
          price = args.passengers * 200.0
        ))
      }

    val invokable: org.adk4s.core.component.InvokableTool[IO] = bookTool.asInvokableTool

    // Verify tool info
    assertEquals(invokable.info.name, "book_trip")
    assertEquals(invokable.info.description, "Book a trip to a destination")

    // Test execution with JSON arguments
    val arguments: ujson.Value = ujson.Obj(
      "destination" -> "Tokyo",
      "passengers" -> 3
    )

    val result: IO[ujson.Value] = invokable.run(arguments)

    result.map { (json: ujson.Value) =>
      assertEquals(json.obj("confirmation").str, "Confirmed trip to Tokyo")
      assertEquals(json.obj("price").num, 600.0)
    }
  }

  test("TypedTool handles errors gracefully") {
    val failingTool: TypedTool[IO, BookingArgs, BookingResult] =
      StructuredToolCall.createTool[IO, BookingArgs, BookingResult](
        "book_trip",
        "Book a trip to a destination"
      ) { (_: BookingArgs) =>
        IO.raiseError(new RuntimeException("Booking system down"))
      }

    val result: IO[BookingResult] = failingTool.execute(BookingArgs("Paris", 2))

    result.attempt.map { (either: Either[Throwable, BookingResult]) =>
      assert(either.isLeft)
      either match
        case Left(err) => assert(err.getMessage.contains("Booking system down"))
        case Right(_) => fail("Expected error")
    }
  }

  test("asInvokableTool handles invalid JSON arguments") {
    val bookTool: TypedTool[IO, BookingArgs, BookingResult] =
      StructuredToolCall.createTool[IO, BookingArgs, BookingResult](
        "book_trip",
        "Book a trip to a destination"
      ) { (args: BookingArgs) =>
        IO.pure(BookingResult(
          confirmation = s"Confirmed trip to ${args.destination}",
          price = args.passengers * 200.0
        ))
      }

    val invokable: org.adk4s.core.component.InvokableTool[IO] = bookTool.asInvokableTool

    // Missing required field "destination"
    val invalidArgs: ujson.Value = ujson.Obj("passengers" -> 2)

    val result: IO[ujson.Value] = invokable.run(invalidArgs)

    result.attempt.map { (either: Either[Throwable, ujson.Value]) =>
      assert(either.isLeft)
      either match
        case Left(err) => assert(err.getMessage.contains("decode"))
        case Right(_) => fail("Expected decode error")
    }
  }

  test("TypedTool properties match constructor arguments") {
    val bookTool: TypedTool[IO, BookingArgs, BookingResult] =
      StructuredToolCall.createTool[IO, BookingArgs, BookingResult](
        "book_trip",
        "Book a trip to a destination"
      ) { (args: BookingArgs) =>
        IO.pure(BookingResult(
          confirmation = s"Confirmed trip to ${args.destination}",
          price = args.passengers * 200.0
        ))
      }

    assertEquals(bookTool.name, "book_trip")
    assertEquals(bookTool.description, "Book a trip to a destination")
  }
