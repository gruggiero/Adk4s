package org.adk4s.agent.tools

import munit.CatsEffectSuite
import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import cats.data.Kleisli
import cats.implicits.*
import scala.concurrent.duration.*
import org.adk4s.core.tools.{ToolEndpoint, ToolOutput, ToolMiddleware, ToolInput}

class ToolMiddlewareTest extends CatsEffectSuite:

  test("identity middleware does nothing") {
    val endpoint: ToolEndpoint = Kleisli(input =>
      IO.pure(ToolOutput(input.name, "result", "call_1"))
    )

    val wrapped = ToolMiddleware.identity(endpoint)
    val result = wrapped.run(ToolInput("test", "{}", "call_1")).unsafeRunSync()

    assertEquals(result.result, "result")
  }

  test("timing middleware records execution time") {
    val recordedTime: Ref[IO, Option[(String, Long)]] = Ref.of[IO, Option[(String, Long)]](None).unsafeRunSync()
    val timingFn: (String, Long) => IO[Unit] = (name, ms) =>
      recordedTime.update(_ => Some((name, ms)))

    val endpoint: ToolEndpoint = Kleisli(input =>
      IO.sleep(10.millis) *> IO.pure(ToolOutput(input.name, "result", "call_1"))
    )

    val wrapped = ToolMiddleware.timing(timingFn)(endpoint)
    val result = wrapped.run(ToolInput("test", "{}", "call_1")).unsafeRunSync()

    val rt = recordedTime.get.unsafeRunSync()
    assertEquals(result.result, "result")
    assertEquals(rt.map(_._1), Some("test"))
    assert(rt.exists(_._2 >= 10))
  }

  test("validation middleware passes valid input") {
    val validate: ToolInput => IO[Either[String, Unit]] = _ => IO.pure(Right(()))

    val endpoint: ToolEndpoint = Kleisli(input =>
      IO.pure(ToolOutput(input.name, "result", "call_1"))
    )

    val wrapped = ToolMiddleware.validation(validate)(endpoint)
    val result = wrapped.run(ToolInput("test", "{}", "call_1")).unsafeRunSync()

    assertEquals(result.result, "result")
  }

  test("validation middleware returns error on invalid input") {
    val validate: ToolInput => IO[Either[String, Unit]] = _ =>
      IO.pure(Left("Invalid argument"))

    val endpoint: ToolEndpoint = Kleisli(input =>
      IO.pure(ToolOutput(input.name, "result", "call_1"))
    )

    val wrapped = ToolMiddleware.validation(validate)(endpoint)
    val result = wrapped.run(ToolInput("test", "{}", "call_1")).unsafeRunSync()

    assert(result.isError)
    assert(result.result.contains("Validation error"))
  }

  test("retry middleware retries on failure") {
    val attempts: Ref[IO, Int] = Ref.of[IO, Int](0).unsafeRunSync()
    val endpoint: ToolEndpoint = Kleisli { input =>
      attempts.update(_ + 1) *> attempts.get.flatMap { a =>
        if a < 3 then
          IO.raiseError(new RuntimeException("temporary error"))
        else
          IO.pure(ToolOutput(input.name, "result", "call_1"))
      }
    }

    val wrapped = ToolMiddleware.retry(3, 10.millis)(endpoint)
    val result = wrapped.run(ToolInput("test", "{}", "call_1")).unsafeRunSync()

    assertEquals(result.result, "result")
    assertEquals(attempts.get.unsafeRunSync(), 3)
  }

  test("retry middleware exhausts retries") {
    val endpoint: ToolEndpoint = Kleisli(_ =>
      IO.raiseError(new RuntimeException("permanent error"))
    )

    val wrapped = ToolMiddleware.retry(2, 10.millis)(endpoint)
    val result = wrapped.run(ToolInput("test", "{}", "call_1")).attempt.unsafeRunSync()

    assert(result.isLeft)
  }

  test("middleware composition applies in order") {
    val order: Ref[IO, List[String]] = Ref.of[IO, List[String]](Nil).unsafeRunSync()
    val log = (s: String) => order.update(_ :+ s)
    val timing = (_: String, _: Long) => order.update(_ :+ "timing")

    val endpoint: ToolEndpoint = Kleisli(input =>
      order.update(_ :+ "endpoint") *> IO.pure(ToolOutput(input.name, "result", "call_1"))
    )

    val wrapped = ToolMiddleware.logging(log)(ToolMiddleware.timing(timing)(endpoint))
    wrapped.run(ToolInput("test", "{}", "call_1")).unsafeRunSync()

    val o = order.get.unsafeRunSync()
    assert(o.exists(_.contains("Tool call:")), "Should contain logging")
    assert(o.contains("timing"), "Should contain timing")
    assert(o.contains("endpoint"), "Should contain endpoint")
  }
