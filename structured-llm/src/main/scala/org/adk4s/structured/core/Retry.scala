package org.adk4s.structured.core

import cats.effect.Async
import cats.effect.Temporal
import cats.syntax.all.*
import org.llm4s.error.LLMError
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
   *
   * Accepts any `Throwable` and inspects the error to find the underlying
   * `LLMError` via `getCause` (for `LLMErrorCause`) or direct pattern matching
   * on `StructuredLLMError.LLMCallFailed`. This makes the trigger wrapper-agnostic:
   * it works whether the error is a raw `LLMError` (wrapped in `LLMErrorCause`),
   * a `StructuredLLMError.LLMCallFailed`, or an `AdkError.LlmCallError` (whose
   * `getCause` returns `LLMErrorCause`).
   *
   * spec: error-hierarchy-dedup
   */
  def shouldRetry(error: Throwable): Boolean = this match
    case LLMError          => extractLLMError(error).isDefined
    case ParseFailure      => error match { case _: StructuredLLMError.ParseFailed => true; case _ => false }
    case ValidationFailure => error match { case _: StructuredLLMError.ValidationFailed => true; case _ => false }
    case All               => true

  /**
   * Extracts the underlying `LLMError` from a `Throwable` by inspecting
   * wrapper types and the cause chain.
   */
  private def extractLLMError(error: Throwable): Option[LLMError] =
    error match
      case llmCallFailed: StructuredLLMError.LLMCallFailed =>
        Some(llmCallFailed.underlying)
      case cause: LLMErrorCause =>
        Some(cause.error)
      case other =>
        other.getCause match
          case cause: LLMErrorCause => Some(cause.error)
          case _                    => None

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
    def attempt(remaining: Int, lastError: Option[Throwable]): F[A] =
      if remaining <= 0 then
        F.raiseError(
          lastError.getOrElse(
            StructuredLLMError.ParseFailed(List.empty, "Max retries exhausted")
          )
        )
      else
        operation.attempt.flatMap {
          case Right(value) => F.pure(value)
          case Left(error: Throwable) =>
            if trigger.shouldRetry(error) && remaining > 1 then F.sleep(delay) *> attempt(remaining - 1, Some(error))
            else F.raiseError(error)
        }
    attempt(maxAttempts, None)
