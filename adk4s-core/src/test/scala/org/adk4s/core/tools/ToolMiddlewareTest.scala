package org.adk4s.core.tools

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite
import scala.concurrent.duration.DurationInt

class ToolMiddlewareTest extends CatsEffectSuite:

  test("identity middleware does nothing") {
    val endpoint: ToolEndpoint = Kleisli((input: ToolInput) =>
      IO.pure(ToolOutput(input.name, "result", "call_1"))
    )
    val wrapped: ToolEndpoint = ToolMiddleware.identity(endpoint)
    val input: ToolInput = ToolInput("test", "{}", "call_1")

    wrapped.run(input).map { (result: ToolOutput) =>
      assertEquals(result.result, "result")
    }
  }

  test("timing middleware records execution time") {
    val input: ToolInput = ToolInput("test", "{}", "call_1")

    Ref.of[IO, Option[(String, Long)]](None).flatMap { (recordedTimeRef: Ref[IO, Option[(String, Long)]]) =>
      val timingFn: (String, Long) => IO[Unit] = (name: String, ms: Long) =>
        recordedTimeRef.set(Some((name, ms)))
      val endpoint: ToolEndpoint = Kleisli((input: ToolInput) =>
        IO.sleep(10.millis).flatMap((_: Unit) => IO.pure(ToolOutput(input.name, "result", "call_1")))
      )
      val wrapped: ToolEndpoint = ToolMiddleware.timing(timingFn)(endpoint)

      wrapped.run(input).flatMap { (result: ToolOutput) =>
        recordedTimeRef.get.map { (recordedTime: Option[(String, Long)]) =>
          assertEquals(result.result, "result")
          assertEquals(recordedTime.map(_._1), Some("test"))
          assert(recordedTime.exists(_._2 >= 10))
        }
      }
    }
  }

  test("validation middleware passes valid input") {
    val validate: ToolInput => IO[Either[String, Unit]] = (_: ToolInput) =>
      IO.pure(Right(()))
    val endpoint: ToolEndpoint = Kleisli((input: ToolInput) =>
      IO.pure(ToolOutput(input.name, "result", "call_1"))
    )
    val wrapped: ToolEndpoint = ToolMiddleware.validation(validate)(endpoint)
    val input: ToolInput = ToolInput("test", "{}", "call_1")

    wrapped.run(input).map { (result: ToolOutput) =>
      assertEquals(result.result, "result")
    }
  }

  test("validation middleware returns error on invalid input") {
    val validate: ToolInput => IO[Either[String, Unit]] = (_: ToolInput) =>
      IO.pure(Left("Invalid argument"))
    val endpoint: ToolEndpoint = Kleisli((input: ToolInput) =>
      IO.pure(ToolOutput(input.name, "result", "call_1"))
    )
    val wrapped: ToolEndpoint = ToolMiddleware.validation(validate)(endpoint)
    val input: ToolInput = ToolInput("test", "{}", "call_1")

    wrapped.run(input).map { (result: ToolOutput) =>
      assert(result.isError)
      assert(result.result.contains("Validation error"))
    }
  }

  test("retry middleware retries on failure") {
    val input: ToolInput = ToolInput("test", "{}", "call_1")

    Ref.of[IO, Int](0).flatMap { (attemptsRef: Ref[IO, Int]) =>
      val endpoint: ToolEndpoint = Kleisli((input: ToolInput) =>
        attemptsRef.get.flatMap { (attempts: Int) =>
          val next: Int = attempts + 1
          attemptsRef.set(next).flatMap { (_: Unit) =>
            if next < 3 then
              IO.raiseError(new RuntimeException("temporary error"))
            else
              IO.pure(ToolOutput(input.name, "result", "call_1"))
          }
        }
      )
      val wrapped: ToolEndpoint = ToolMiddleware.retry(3, 10.millis)(endpoint)

      wrapped.run(input).flatMap { (result: ToolOutput) =>
        attemptsRef.get.map { (attempts: Int) =>
          assertEquals(result.result, "result")
          assertEquals(attempts, 3)
        }
      }
    }
  }

  test("retry middleware exhausts retries") {
    val endpoint: ToolEndpoint = Kleisli((_: ToolInput) =>
      IO.raiseError(new RuntimeException("permanent error"))
    )
    val wrapped: ToolEndpoint = ToolMiddleware.retry(2, 10.millis)(endpoint)
    val input: ToolInput = ToolInput("test", "{}", "call_1")

    wrapped.run(input).attempt.map { (result: Either[Throwable, ToolOutput]) =>
      assert(result.isLeft)
    }
  }

  test("middleware composition applies in order") {
    val input: ToolInput = ToolInput("test", "{}", "call_1")

    Ref.of[IO, List[String]](List.empty).flatMap { (orderRef: Ref[IO, List[String]]) =>
      val log: String => IO[Unit] = (message: String) =>
        orderRef.update((current: List[String]) => current :+ message)
      val timing: (String, Long) => IO[Unit] = (_: String, _: Long) =>
        orderRef.update((current: List[String]) => current :+ "timing")
      val endpoint: ToolEndpoint = Kleisli((input: ToolInput) =>
        orderRef
          .update((current: List[String]) => current :+ "endpoint")
          .flatMap((_: Unit) => IO.pure(ToolOutput(input.name, "result", "call_1")))
      )

      val wrapped: ToolEndpoint = ToolMiddleware.logging(log)(ToolMiddleware.timing(timing)(endpoint))

      wrapped.run(input).flatMap { (_: ToolOutput) =>
        orderRef.get.map { (order: List[String]) =>
          assert(order.exists(_.contains("Tool call:")), "Should contain logging")
          assert(order.contains("timing"), "Should contain timing")
          assert(order.contains("endpoint"), "Should contain endpoint")
        }
      }
    }
  }
