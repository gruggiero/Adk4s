package org.adk4s.core.tools

import cats.effect.IO
import cats.data.Kleisli
import scala.concurrent.duration.FiniteDuration

type ToolMiddleware = ToolEndpoint => ToolEndpoint

object ToolMiddleware:
  val identity: ToolMiddleware = endpoint => endpoint

  def logging(log: String => IO[Unit]): ToolMiddleware = endpoint =>
    Kleisli { input =>
      for
        _ <- log(s"Tool call: ${input.name}(${input.arguments.take(100)}${if input.arguments.length > 100 then "..." else ""})")
        result <- endpoint.run(input).attempt
        _ <- result match
          case Right(output) =>
            log(s"Tool result: ${output.result.take(100)}${if output.result.length > 100 then "..." else ""}")
          case Left(error) =>
            log(s"Tool error: ${error.getMessage}")
        output <- IO.fromEither(result)
      yield output
    }

  def timing(onTiming: (String, Long) => IO[Unit]): ToolMiddleware = endpoint =>
    Kleisli { input =>
      for
        start <- IO.realTime
        result <- endpoint.run(input)
        end <- IO.realTime
        _ <- onTiming(input.name, (end - start).toMillis)
      yield result
    }

  def validation(validate: ToolInput => IO[Either[String, Unit]]): ToolMiddleware = endpoint =>
    Kleisli { input =>
      validate(input).flatMap {
        case Right(_) => endpoint.run(input)
        case Left(error) => IO.pure(ToolOutput(input.name, s"Validation error: $error", input.callId, isError = true))
      }
    }

  def retry(maxRetries: Int, initialDelay: FiniteDuration): ToolMiddleware = endpoint =>
    Kleisli { input =>
      def attempt(retriesLeft: Int, delay: FiniteDuration): IO[ToolOutput] =
        endpoint.run(input).handleErrorWith { error =>
          if retriesLeft > 0 then
            IO.sleep(delay) *> attempt(retriesLeft - 1, delay * 2)
          else
            IO.raiseError(error)
        }
      attempt(maxRetries, initialDelay)
    }

  def compose(middlewares: List[ToolMiddleware]): ToolMiddleware =
    middlewares.foldRight(identity)((m, acc) => endpoint => m(acc(endpoint)))

  extension (self: ToolMiddleware)
    def >>(next: ToolMiddleware): ToolMiddleware =
      endpoint => next(self(endpoint))
