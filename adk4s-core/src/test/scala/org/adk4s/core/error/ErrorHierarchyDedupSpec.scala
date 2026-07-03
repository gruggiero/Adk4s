package org.adk4s.core.error

// spec: error-hierarchy-dedup — Test oracle (Step 2)
// Tests written from the spec BEFORE implementation.
// These tests reference the NEW API (cause-wired LlmCallError/LLMCallFailed,
// widened RetryTrigger.shouldRetry(Throwable)).
// They will NOT compile until Step 3 implements the changes.

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.structured.core.Prompt
import org.adk4s.structured.core.RetryTrigger
import org.adk4s.structured.core.StructuredLLMError
import org.adk4s.structured.core.LLMErrorCause
import org.llm4s.error.AuthenticationError
import org.llm4s.error.LLMError
import org.llm4s.error.NetworkError
import org.llm4s.error.RateLimitError
import org.llm4s.error.TimeoutError
import org.llm4s.error.UnknownError
import scala.concurrent.duration.Duration

class ErrorHierarchyDedupSpec extends HedgehogSuite:

  // ───────────────────────────────────────────────────────────────
  // Helpers
  // ───────────────────────────────────────────────────────────────

  private val testPrompt: Prompt = Prompt.empty

  // ───────────────────────────────────────────────────────────────
  // Requirement: LlmCallError exposes underlying LLMError via getCause
  // Scenario: getCause returns LLMErrorCause wrapping original LLMError
  // ───────────────────────────────────────────────────────────────

  test("LlmCallError.getCause returns LLMErrorCause wrapping original LLMError") {
    // spec: error-hierarchy-dedup — Scenario: getCause returns original LLMError
    val underlying: LLMError = RateLimitError("too many requests")
    val error: LlmCallError = LlmCallError(underlying)
    val cause: Throwable = error.getCause
    cause match
      case llmCause: LLMErrorCause =>
        assertEquals(llmCause.error, underlying)
      case other =>
        fail(s"Expected LLMErrorCause, got ${other.getClass.getName}")
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: LlmCallError exposes underlying LLMError via getCause
  // Scenario: underlying field preserved for source compatibility
  // ───────────────────────────────────────────────────────────────

  test("LlmCallError.underlying field preserved for source compatibility") {
    // spec: error-hierarchy-dedup — Scenario: underlying field preserved
    val underlying: LLMError = NetworkError("Connection timeout", None, "openai")
    val error: LlmCallError = LlmCallError(underlying)
    assertEquals(error.underlying, underlying)
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: StructuredLLMError.LLMCallFailed exposes underlying via getCause
  // Scenario: getCause returns LLMErrorCause wrapping original LLMError
  // ───────────────────────────────────────────────────────────────

  test("LLMCallFailed.getCause returns LLMErrorCause wrapping original LLMError") {
    // spec: error-hierarchy-dedup — Scenario: getCause returns original LLMError
    val underlying: LLMError = TimeoutError("timed out", Duration.Zero, "openai", None, Map.empty)
    val error: StructuredLLMError.LLMCallFailed = StructuredLLMError.LLMCallFailed(underlying, testPrompt)
    val cause: Throwable = error.getCause
    cause match
      case llmCause: LLMErrorCause =>
        assertEquals(llmCause.error, underlying)
      case other =>
        fail(s"Expected LLMErrorCause, got ${other.getClass.getName}")
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: RetryTrigger inspects underlying LLMError
  // Scenario: Retry on wrapped LLMError (LLMCallFailed)
  // ───────────────────────────────────────────────────────────────

  test("RetryTrigger.LLMError.shouldRetry returns true for LLMCallFailed") {
    // spec: error-hierarchy-dedup — Scenario: Retry on wrapped LLMError
    val underlying: LLMError = RateLimitError("too many requests")
    val error: StructuredLLMError.LLMCallFailed = StructuredLLMError.LLMCallFailed(underlying, testPrompt)
    val result: Boolean = RetryTrigger.LLMError.shouldRetry(error)
    assert(result, "Expected shouldRetry to return true for LLMCallFailed")
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: RetryTrigger inspects underlying LLMError
  // Scenario: No retry on ParseFailed
  // ───────────────────────────────────────────────────────────────

  test("RetryTrigger.LLMError.shouldRetry returns false for ParseFailed") {
    // spec: error-hierarchy-dedup — Scenario: No retry on non-LLM error
    val error: StructuredLLMError.ParseFailed = StructuredLLMError.ParseFailed(List.empty, "bad response")
    val result: Boolean = RetryTrigger.LLMError.shouldRetry(error)
    assert(!result, "Expected shouldRetry to return false for ParseFailed")
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: RetryTrigger handles both StructuredLLMError and raw LLMError
  // Scenario: AdkError.LlmCallError triggers LLMError retry
  // ───────────────────────────────────────────────────────────────

  test("RetryTrigger.LLMError.shouldRetry returns true for AdkError.LlmCallError") {
    // spec: error-hierarchy-dedup — Scenario: AdkError.LlmCallError triggers LLMError retry
    val underlying: LLMError = TimeoutError("timed out", Duration.Zero, "openai", None, Map.empty)
    val error: LlmCallError = LlmCallError(underlying)
    val result: Boolean = RetryTrigger.LLMError.shouldRetry(error)
    assert(result, "Expected shouldRetry to return true for LlmCallError")
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: RetryTrigger handles both StructuredLLMError and raw LLMError
  // Scenario: RetryTrigger.All returns true for all error types
  // ───────────────────────────────────────────────────────────────

  test("RetryTrigger.All.shouldRetry returns true for LlmCallError") {
    // spec: error-hierarchy-dedup — Scenario: All triggers for all wrappers
    val underlying: LLMError = RateLimitError("rate limited")
    val error: LlmCallError = LlmCallError(underlying)
    val result: Boolean = RetryTrigger.All.shouldRetry(error)
    assert(result)
  }

  test("RetryTrigger.All.shouldRetry returns true for LLMCallFailed") {
    // spec: error-hierarchy-dedup — Scenario: All triggers for all wrappers
    val underlying: LLMError = NetworkError("network error", None, "openai")
    val error: StructuredLLMError.LLMCallFailed = StructuredLLMError.LLMCallFailed(underlying, testPrompt)
    val result: Boolean = RetryTrigger.All.shouldRetry(error)
    assert(result)
  }

  test("RetryTrigger.All.shouldRetry returns true for ParseFailed") {
    // spec: error-hierarchy-dedup — Scenario: All triggers for all wrappers
    val error: StructuredLLMError.ParseFailed = StructuredLLMError.ParseFailed(List.empty, "bad")
    val result: Boolean = RetryTrigger.All.shouldRetry(error)
    assert(result)
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: RetryTrigger.shouldRetry signature widened
  // Scenario: shouldRetry accepts AdkError.LlmCallError as Throwable
  // ───────────────────────────────────────────────────────────────

  test("RetryTrigger.LLMError.shouldRetry accepts AdkError.LlmCallError as Throwable") {
    // spec: error-hierarchy-dedup — Scenario: shouldRetry accepts AdkError.LlmCallError
    val underlying: LLMError = AuthenticationError("invalid key", "openai")
    val error: Throwable = LlmCallError(underlying)
    val result: Boolean = RetryTrigger.LLMError.shouldRetry(error)
    assert(result)
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: RetryTrigger.shouldRetry signature widened
  // Scenario: shouldRetry accepts generic RuntimeException (no LLMError cause)
  // ───────────────────────────────────────────────────────────────

  test("RetryTrigger.LLMError.shouldRetry returns false for generic RuntimeException") {
    // spec: error-hierarchy-dedup — shouldRetry with non-LLM Throwable
    val error: Throwable = new RuntimeException("unrelated error")
    val result: Boolean = RetryTrigger.LLMError.shouldRetry(error)
    assert(!result, "Expected shouldRetry to return false for non-LLM error")
  }

  // ═══════════════════════════════════════════════════════════════
  // Property 1: getCause returns LLMErrorCause for all wrapper variants
  // spec: error-hierarchy-dedup — Property: getCause returns underlying
  // ═══════════════════════════════════════════════════════════════

  property("getCause returns LLMErrorCause wrapping original LLMError for all wrappers") {
    // Generator strategy: constructive — genLLMError generates LLMError variants.
    // Classify by LLMError variant.
    val llmErrorGen: Gen[LLMError] = Gen.element1[LLMError](
      RateLimitError("rate limited"),
      TimeoutError("timed out", Duration.Zero, "openai", None, Map.empty),
      NetworkError("network error", None, "openai"),
      AuthenticationError("auth error", "openai"),
      UnknownError("unknown", new Exception("cause"))
    )
    llmErrorGen.forAll.map { (e: LLMError) =>
      val adkError: LlmCallError = LlmCallError(e)
      val structuredError: StructuredLLMError.LLMCallFailed = StructuredLLMError.LLMCallFailed(e, testPrompt)
      val adkCause: Throwable = adkError.getCause
      val structuredCause: Throwable = structuredError.getCause
      // Use pattern matching to extract LLMError from cause (avoids isInstanceOf)
      val adkExtracted: Option[LLMError] = adkCause match
        case llmCause: LLMErrorCause => Some(llmCause.error)
        case _                       => None
      val structuredExtracted: Option[LLMError] = structuredCause match
        case llmCause: LLMErrorCause => Some(llmCause.error)
        case _                       => None
      (adkExtracted ==== Some(e))
        .and(structuredExtracted ==== Some(e))
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Property 2: RetryTrigger classification is wrapper-agnostic
  // spec: error-hierarchy-dedup — Property: wrapper-agnostic classification
  // ═══════════════════════════════════════════════════════════════

  property("RetryTrigger.LLMError classification is wrapper-agnostic") {
    // Generator strategy: constructive — genLLMError generates LLMError variants.
    // Classify by LLMError variant.
    val llmErrorGen: Gen[LLMError] = Gen.element1[LLMError](
      RateLimitError("rate limited"),
      TimeoutError("timed out", Duration.Zero, "openai", None, Map.empty),
      NetworkError("network error", None, "openai"),
      AuthenticationError("auth error", "openai"),
      UnknownError("unknown", new Exception("cause"))
    )
    llmErrorGen.forAll.map { (e: LLMError) =>
      val trigger: RetryTrigger = RetryTrigger.LLMError
      val adkResult: Boolean = trigger.shouldRetry(LlmCallError(e))
      val structuredResult: Boolean = trigger.shouldRetry(StructuredLLMError.LLMCallFailed(e, testPrompt))
      (adkResult ==== true).and(structuredResult ==== true).and(adkResult ==== structuredResult)
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Property 3: ParseFailed does not trigger LLMError retry
  // spec: error-hierarchy-dedup — Property: ParseFailed no retry
  // ═══════════════════════════════════════════════════════════════

  property("ParseFailed does not trigger LLMError retry") {
    // Generator strategy: constructive — genRawResponse generates response strings.
    // Classify by response length.
    val rawResponseGen: Gen[String] = Gen.string(Gen.alpha, Range.linear(0, 100))
    rawResponseGen.forAll.map { (raw: String) =>
      val parseError: StructuredLLMError.ParseFailed = StructuredLLMError.ParseFailed(List.empty, raw)
      val result: Boolean = RetryTrigger.LLMError.shouldRetry(parseError)
      result ==== false
    }
  }
