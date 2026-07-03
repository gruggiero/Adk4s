package org.adk4s.core.error.typecontract

/** Typed contract for spec: error-hierarchy-dedup
  *
  * This file is a COMPILE-ONLY contract. It declares the new type signatures
  * that the implementation must honor.
  *
  * IMPORTANT DEVIATION FROM SPEC:
  * `LLMError` is NOT a `Throwable` (it extends `Product, Serializable`).
  * Therefore, `Throwable.getCause` cannot return an `LLMError` directly.
  * Instead, we introduce a wrapper `LLMErrorCause(error: LLMError)
  * extends RuntimeException` that is set as the `cause` via the super
  * constructor. `getCause` returns the `LLMErrorCause`, and the `LLMError`
  * is accessible via `.error`. The `underlying` field is kept for source
  * compatibility.
  *
  * MODULE DEPENDENCY NOTE:
  * `LLMErrorCause` is defined in `structured-llm` (core package) so both
  * `structured-llm` and `adk4s-core` (which depends on structured-llm)
  * can access it. `RetryTrigger` in `structured-llm` checks `getCause`
  * for `LLMErrorCause` to handle `AdkError.LlmCallError` without importing
  * `adk4s-core` types.
  *
  * spec: error-hierarchy-dedup
  */

import org.adk4s.core.error.LlmCallError
import org.adk4s.structured.core.Prompt
import org.adk4s.structured.core.RetryTrigger
import org.adk4s.structured.core.StructuredLLMError
import org.llm4s.error.LLMError

// ─────────────────────────────────────────────────────────────────
// CONTRACT 1: LLMErrorCause — wrapper for getCause
//
// Since LLMError is not Throwable, we wrap it in a RuntimeException
// so it can be set as the cause of AdkError.LlmCallError and
// StructuredLLMError.LLMCallFailed.
//
// This class will live in org.adk4s.structured.core (structured-llm)
// so both modules can access it.
// ─────────────────────────────────────────────────────────────────

/** Wrapper exception that holds an LLMError, enabling getCause to return
  * a Throwable from which the LLMError can be recovered.
  */
final class LLMErrorCause(val error: LLMError) extends RuntimeException(error.toString)

// ─────────────────────────────────────────────────────────────────
// CONTRACT 2: RetryTrigger.shouldRetry widened to Throwable
//
// shouldRetry now accepts Throwable and inspects the error to find
// the underlying LLMError via:
// 1. Pattern matching on LLMCallFailed → _.underlying
// 2. Checking getCause for LLMErrorCause → _.error
// 3. For StructuredLLMError.ParseFailed/ValidationFailed → no LLMError
//
// Note: LlmCallError (in adk4s-core) is handled via getCause → LLMErrorCause,
// not via direct pattern matching (since structured-llm can't import adk4s-core).
// ─────────────────────────────────────────────────────────────────

object RefactoredRetryTriggerContract:

  /** Widened shouldRetry — accepts any Throwable. */
  def shouldRetry(trigger: RetryTrigger, error: Throwable): Boolean =
    trigger match
      case RetryTrigger.LLMError =>
        extractLLMError(error).isDefined
      case RetryTrigger.ParseFailure =>
        error match
          case _: StructuredLLMError.ParseFailed => true
          case _                                  => false
      case RetryTrigger.ValidationFailure =>
        error match
          case _: StructuredLLMError.ValidationFailed => true
          case _                                       => false
      case RetryTrigger.All => true

  /** Extracts LLMError from a Throwable by inspecting wrapper types
    * and the cause chain. */
  def extractLLMError(error: Throwable): Option[LLMError] =
    error match
      case llmCallFailed: StructuredLLMError.LLMCallFailed =>
        Some(llmCallFailed.underlying)
      case cause: LLMErrorCause =>
        Some(cause.error)
      case other =>
        other.getCause match
          case cause: LLMErrorCause => Some(cause.error)
          case llmCallError: LlmCallError => Some(llmCallError.underlying)
          case _                     => None

// ─────────────────────────────────────────────────────────────────
// PROPERTY OBLIGATIONS (structured comments — implemented in test oracle)
//
// Property 1: getCause returns LLMErrorCause wrapping the original LLMError
//   forAll { (e: LLMError, prompt: Prompt) =>
//     LlmCallError(e).getCause.isInstanceOf[LLMErrorCause] &&
//     LLMCallFailed(e, prompt).getCause.isInstanceOf[LLMErrorCause]
//   }
//
// Property 2: RetryTrigger classification is wrapper-agnostic
//   forAll { (e: LLMError, prompt: Prompt) =>
//     val trigger = RetryTrigger.LLMError
//     trigger.shouldRetry(LlmCallError(e)) == true &&
//     trigger.shouldRetry(LLMCallFailed(e, prompt)) == true
//   }
//
// Property 3: ParseFailed does not trigger LLMError retry
//   forAll { (parseError: StructuredLLMError.ParseFailed) =>
//     !RetryTrigger.LLMError.shouldRetry(parseError)
//   }
// ─────────────────────────────────────────────────────────────────
