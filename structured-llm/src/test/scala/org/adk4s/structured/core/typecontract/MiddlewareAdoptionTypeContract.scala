package org.adk4s.structured.core.typecontract

// spec: llm4s-middleware-adoption — Typed contract (Step 1)
// This file defines the type signatures of the refactored middleware architecture.
// It is a compile-time artifact: it must compile against the llm4s API surface
// and the existing StructuredLLM trait, but the implementations it references
// (StructuredOutputMiddleware, ParseRetryTrigger, new factory methods) do NOT
// exist yet. The contract is the human-gate checkpoint before writing the test
// oracle (Step 2) and implementation (Step 3).

import cats.effect.Async
import org.adk4s.structured.core.{
  Prompt,
  Schema,
  StructuredLLM,
  StructuredLLMError,
  ValidationResult
}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.middleware.LLMMiddleware
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation }
import scala.concurrent.duration.Duration

/**
 * Controls parse-failure retry within the structured layer.
 * Distinct from llm4s RetryPolicy (which only retries on LLMError).
 *
 * spec: llm4s-middleware-adoption
 */
enum ParseRetryTrigger:
  case ParseFailed
  case ValidationFailed
  case All

/**
 * Typed contract for StructuredOutputMiddleware.
 *
 * This trait extends LLMMiddleware and is the new home for the structured-output
 * concern: schema injection, SAP parsing, and constraint evaluation.
 *
 * spec: llm4s-middleware-adoption
 */
trait StructuredOutputMiddlewareContract extends LLMMiddleware:
  /**
   * The schema to inject into the conversation.
   * Set per-call via the `complete[A]` method on StructuredLLM.
   */
  def schemaBlock: String

  /**
   * The parse retry trigger (controls whether parse failures are retried).
   */
  def parseRetryTrigger: ParseRetryTrigger

  /**
   * Maximum parse retry attempts.
   */
  def maxParseAttempts: Int

  /**
   * Delay between parse retries.
   */
  def parseRetryDelay: Duration

/**
 * Typed contract for the new factory methods on StructuredLLM.
 *
 * spec: llm4s-middleware-adoption
 */
object StructuredLLMMiddlewareFactoryContract:

  /**
   * Composes a middleware stack via MiddlewareClient, then wraps in
   * StructuredOutputMiddleware. StructuredOutputMiddleware is applied
   * innermost (closest to the raw LLMClient).
   *
   * spec: llm4s-middleware-adoption — Requirement: Middleware-composed factory
   */
  def fromClientWithMiddlewares[F[_]: Async](
    client: LLMClient,
    middlewares: List[LLMMiddleware],
    defaultOptions: CompletionOptions = CompletionOptions()
  ): StructuredLLM[F] = ???

  /**
   * Ergonomic alias for fromClient with a single middleware.
   *
   * spec: llm4s-middleware-adoption — Requirement: Middleware-composed factory
   */
  def fromClientWithMiddleware[F[_]: Async](
    client: LLMClient,
    middleware: LLMMiddleware,
    defaultOptions: CompletionOptions = CompletionOptions()
  ): StructuredLLM[F] = ???

  /**
   * Deprecated: delegates to fromClient with a ReliableClient middleware.
   *
   * spec: llm4s-middleware-adoption — Requirement: fromClientWithRetry delegates
   */
  @deprecated("Use fromClient with ReliableClient middleware", "llm4s-middleware-adoption")
  def fromClientWithRetryContract[F[_]: Async](
    client: LLMClient,
    maxAttempts: Int,
    delay: Duration,
    trigger: ParseRetryTrigger,
    defaultOptions: CompletionOptions = CompletionOptions()
  ): StructuredLLM[F] = ???

/**
 * Compile-negative obligation: RetryStructuredLLM must not be constructible
 * from outside structured-llm core.
 *
 * spec: llm4s-middleware-adoption — Compile-Negative
 */
object MiddlewareCompileNegative:
  // This should NOT compile if uncommented:
  // new RetryStructuredLLM[IO](null, 0, Duration.Zero, RetryTrigger.All)
  // RetryStructuredLLM is private[core] and deprecated.
  def check(): Unit = ()
