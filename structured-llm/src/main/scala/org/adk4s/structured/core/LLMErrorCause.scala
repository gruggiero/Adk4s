package org.adk4s.structured.core

import org.llm4s.error.LLMError

/**
 * Wrapper exception that holds an `LLMError`, enabling `Throwable.getCause`
 * to return a `Throwable` from which the `LLMError` can be recovered.
 *
 * `LLMError` is NOT a `Throwable` (it extends `Product, Serializable`), so
 * it cannot be set directly as a `Throwable` cause. This wrapper bridges
 * the gap: `AdkError.LlmCallError` and `StructuredLLMError.LLMCallFailed`
 * set `super(LLMErrorCause(underlying))` so `getCause` returns an
 * `LLMErrorCause` whose `.error` field provides access to the original
 * `LLMError`.
 *
 * spec: error-hierarchy-dedup
 */
final class LLMErrorCause(val error: LLMError) extends RuntimeException(error.toString)
