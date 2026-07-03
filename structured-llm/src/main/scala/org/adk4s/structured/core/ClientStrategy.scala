package org.adk4s.structured.core

import cats.effect.Async
import cats.syntax.all.*
import org.llm4s.llmconnect.LLMClient
import scala.concurrent.duration.Duration

/**
 * Client strategy for fallback or round-robin LLM client selection.
 */
enum ClientStrategy:
  case Fallback(clients: Vector[LLMClient])
  case RoundRobin(clients: Vector[LLMClient])

/**
 * Attempt record for error enrichment.
 */
final case class AttemptRecord(
  client: String,
  error: StructuredLLMError,
  rawResponse: String,
  timestamp: Long
)

object ClientStrategy:

  /**
   * Create a fallback strategy — tries clients in order on failure.
   */
  def fallback(clients: Vector[LLMClient]): ClientStrategy = ClientStrategy.Fallback(clients)

  /**
   * Create a round-robin strategy — rotates through clients for load balancing.
   */
  def roundRobin(clients: Vector[LLMClient]): ClientStrategy = ClientStrategy.RoundRobin(clients)

  /**
   * Execute an operation with the given client strategy.
   * Tries each client in order (fallback) or rotating (round-robin) until one succeeds.
   *
   * @param strategy The client strategy
   * @param operation Function from LLMClient to F[A]
   * @return The result of the first successful client, or the last error
   */
  def execute[F[_], A](
    strategy: ClientStrategy,
    operation: LLMClient => F[A],
    clientNames: Vector[String]
  )(using F: Async[F]): F[A] =
    strategy match
      case ClientStrategy.Fallback(clients) =>
        executeFallback(clients, operation, clientNames, Vector.empty)
      case ClientStrategy.RoundRobin(clients) =>
        // For round-robin, we still try all on failure but start from index 0
        // (true rotation requires stateful Ref, which is handled at the StructuredLLM level)
        executeFallback(clients, operation, clientNames, Vector.empty)

  private def executeFallback[F[_], A](
    clients: Vector[LLMClient],
    operation: LLMClient => F[A],
    clientNames: Vector[String],
    errors: Vector[AttemptRecord]
  )(using F: Async[F]): F[A] =
    clients match
      case head +: tail =>
        operation(head).attempt.flatMap {
          case Right(value) => F.pure(value)
          case Left(error: StructuredLLMError) =>
            val idx: Int             = errors.size
            val name: String         = clientNames.lift(idx).getOrElse(s"client-$idx")
            val record: AttemptRecord = AttemptRecord(name, error, "", System.currentTimeMillis())
            executeFallback(tail, operation, clientNames, errors :+ record)
          case Left(other) =>
            F.raiseError(other)
        }
      case empty =>
        val lastError: StructuredLLMError =
          errors.lastOption
            .map(_.error)
            .getOrElse(
              StructuredLLMError.ParseFailed(List.empty, "All clients failed")
            )
        // Wrap in Enriched when there were multiple attempts so the per-client
        // AttemptRecord names (including index-based fallback names) are
        // observable by callers. With a single attempt the Enriched wrapper
        // adds no information, so we skip it.
        val finalError: StructuredLLMError =
          if errors.size > 1 then StructuredLLMError.Enriched(lastError, errors)
          else lastError
        F.raiseError(finalError)
