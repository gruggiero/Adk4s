package org.adk4s.structured.core

/**
 * Controls parse-failure retry within the structured layer.
 *
 * Distinct from llm4s `RetryPolicy` (which only retries on `LLMError`).
 * Parse failures require a fresh LLM call (the model may produce different
 * output), not just a re-parse.
 *
 * spec: llm4s-middleware-adoption
 */
enum ParseRetryTrigger:
  case ParseFailed
  case ValidationFailed
  case All

  /**
   * Check if a given error should trigger a parse-failure retry.
   */
  def shouldRetry(error: Throwable): Boolean = this match
    case ParseFailed       => error match
      case _: StructuredLLMError.ParseFailed => true
      case _                                 => false
    case ValidationFailed  => error match
      case _: StructuredLLMError.ValidationFailed => true
      case _                                       => false
    case All               => error match
      case _: StructuredLLMError.ParseFailed       => true
      case _: StructuredLLMError.ValidationFailed  => true
      case _: StructuredLLMError.LLMCallFailed     => true
      case _                                        => false
