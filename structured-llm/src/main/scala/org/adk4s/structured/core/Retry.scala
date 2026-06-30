package org.adk4s.structured.core

import cats.effect.Async
import cats.effect.Temporal
import cats.syntax.all.*
import scala.concurrent.duration.Duration

/**
 * Controls which failure types trigger retries.
 */
enum RetryTrigger:
  case LLMError
  case ParseFailure
  case ValidationFailure
  case All

  /**
   * Check if a given error should trigger a retry.
   */
  def shouldRetry(error: StructuredLLMError): Boolean = this match
    case LLMError          => error match { case _: StructuredLLMError.LLMCallFailed     => true; case _ => false }
    case ParseFailure      => error match { case _: StructuredLLMError.ParseFailed       => true; case _ => false }
    case ValidationFailure => error match { case _: StructuredLLMError.ValidationFailed  => true; case _ => false }
    case All               => true

object Retry:

  /**
   * Retry a structured LLM operation with the given retry policy.
   *
   * @param maxAttempts Maximum number of attempts (including the first)
   * @param delay Delay between attempts
   * @param trigger Which failure types trigger retries
   * @param operation The operation to retry
   * @return The result of the operation, or the last error if all attempts fail
   */
  def withRetry[F[_], A](
    maxAttempts: Int,
    delay: Duration,
    trigger: RetryTrigger
  )(operation: F[A])(using F: Temporal[F]): F[A] =
    def attempt(remaining: Int, lastError: Option[StructuredLLMError]): F[A] =
      if remaining <= 0 then
        F.raiseError(lastError.getOrElse(
          StructuredLLMError.ParseFailed(List.empty, "Max retries exhausted")
        ))
      else
        operation.attempt.flatMap {
          case Right(value) => F.pure(value)
          case Left(error: StructuredLLMError) =>
            if trigger.shouldRetry(error) && remaining > 1 then
              F.sleep(delay) *> attempt(remaining - 1, Some(error))
            else
              F.raiseError(error)
          case Left(other) =>
            F.raiseError(other)
        }
    attempt(maxAttempts, None)
