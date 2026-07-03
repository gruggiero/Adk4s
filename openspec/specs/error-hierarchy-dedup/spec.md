# Spec: Error Hierarchy Deduplication

<!-- This is a DELTA spec. It thins the AdkError/StructuredLLMError wrappers
     so they expose the underlying llm4s error via getCause, and makes
     RetryTrigger match on the underlying LLMError category. -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `AdkError` | sealed trait (extends Throwable) | `org.adk4s.core.error` |
| `LlmCallError` | case class (extends AdkError) | `org.adk4s.core.error` |
| `StructuredOutputError` | case class (extends AdkError) | `org.adk4s.core.error` |
| `StructuredLLMError` | sealed trait (extends Throwable) | `org.adk4s.structured.core` |
| `StructuredLLMError.LLMCallFailed` | case class | `org.adk4s.structured.core` |
| `StructuredLLMError.ParseFailed` | case class | `org.adk4s.structured.core` |
| `StructuredLLMError.ValidationFailed` | case class | `org.adk4s.structured.core` |
| `StructuredLLMError.Enriched` | case class | `org.adk4s.structured.core` |
| `LLMError` (llm4s) | sealed trait | `org.llm4s.error` |
| `RetryTrigger` | enum | `org.adk4s.structured.core` |
| `AttemptRecord` | case class | `org.adk4s.structured.core` |
| `StructuredToolCallError` | sealed trait | `org.adk4s.core.tools` |
| `ToolSchemaError` | sealed trait | `org.adk4s.core.tools` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `RetryTrigger.shouldRetry(error: Throwable): Boolean` | method (refactored) | `shouldRetry` now inspects the underlying `LLMError` via `getCause`/`underlying` rather than matching on the wrapper variant, so retry works uniformly whether the error is raised by the middleware layer or the structured layer |
| `AdkError.LlmCallError(underlying: LLMError, cause: LLMError)` | case class (refactored) | `LlmCallError` now sets `cause` via `Throwable`'s constructor so `getCause` returns the original `LLMError`; the `underlying` field is kept for source compatibility |

## ADDED Requirements

### Requirement: LlmCallError exposes underlying LLMError via getCause

The system SHALL ensure `AdkError.LlmCallError` sets the `Throwable.cause` field to the underlying `LLMError` via the super constructor, so `getCause` returns the original `LLMError` without pattern-matching on the wrapper variant.

**Given** an `AdkError.LlmCallError(underlying = someLLMError)`
**When** `error.getCause` is called
**Then** the result is `someLLMError` (the original `LLMError` instance)

**Rationale**: Currently `LlmCallError(underlying: LLMError)` does not set `cause`, so `getCause` returns `null`. Callers must pattern-match on `LlmCallError` to recover the root cause, which couples retry logic to the wrapper shape.

#### Scenario: getCause returns original LLMError

**Given** `val err = AdkError.LlmCallError(LLMError.RateLimitError("too many requests"))`
**When** `err.getCause` is called
**Then** the result is an `LLMError.RateLimitError` with message "too many requests"

#### Scenario: underlying field preserved for source compatibility

**Given** `val err = AdkError.LlmCallError(someLLMError)`
**When** `err.underlying` is accessed
**Then** the result is `someLLMError` (same as before the change)

### Requirement: StructuredLLMError.LLMCallFailed exposes underlying via getCause

The system SHALL ensure `StructuredLLMError.LLMCallFailed` sets the `Throwable.cause` field to the underlying `LLMError` via the super constructor.

**Given** a `StructuredLLMError.LLMCallFailed(underlying = someLLMError, prompt = somePrompt)`
**When** `error.getCause` is called
**Then** the result is `someLLMError`

**Rationale**: Same as above — enables `RetryTrigger` to inspect the underlying error category without matching on the wrapper.

#### Scenario: getCause returns original LLMError

**Given** `val err = StructuredLLMError.LLMCallFailed(LLMError.TimeoutError("timed out"), prompt)`
**When** `err.getCause` is called
**Then** the result is an `LLMError.TimeoutError`

### Requirement: RetryTrigger inspects underlying LLMError

The system SHALL refactor `RetryTrigger.shouldRetry` to inspect the underlying `LLMError` category via `getCause` (or the `underlying` field) rather than matching on the wrapper variant (`StructuredLLMError.LLMCallFailed`), so retry works uniformly whether the error is raised by the middleware layer (raw `LLMError`) or the structured layer (`StructuredLLMError.LLMCallFailed`).

**Given** a `RetryTrigger.LLMError` and an `LLMError.RateLimitError` raised directly (not wrapped in `StructuredLLMError`)
**When** `trigger.shouldRetry(llmError)` is called
**Then** the result is `true` (the trigger matches the underlying error category, not the wrapper)

**Rationale**: Currently `RetryTrigger.LLMError` only matches `StructuredLLMError.LLMCallFailed`, so raw `LLMError`s from the middleware layer are not retried. After the middleware adoption, errors may arrive as raw `LLMError` or wrapped — the trigger must handle both.

#### Scenario: Retry on raw LLMError

**Given** `RetryTrigger.LLMError` and a raw `LLMError.RateLimitError`
**When** `trigger.shouldRetry(rateLimitError)` is called
**Then** the result is `true`

#### Scenario: Retry on wrapped LLMError

**Given** `RetryTrigger.LLMError` and a `StructuredLLMError.LLMCallFailed(LLMError.RateLimitError(...), prompt)`
**When** `trigger.shouldRetry(wrappedError)` is called
**Then** the result is `true` (same as the raw case)

#### Scenario: No retry on non-LLM error

**Given** `RetryTrigger.LLMError` and a `StructuredLLMError.ParseFailed(...)`
**When** `trigger.shouldRetry(parseError)` is called
**Then** the result is `false` (ParseFailed does not have an `LLMError` cause)

### Requirement: RetryTrigger handles both StructuredLLMError and raw LLMError

The system SHALL ensure `RetryTrigger.shouldRetry` accepts a `Throwable` parameter (not just `StructuredLLMError`) and correctly classifies errors that are either raw `LLMError`, `StructuredLLMError`, or `AdkError` wrappers.

**Given** a `RetryTrigger.All` and any of: raw `LLMError`, `StructuredLLMError.LLMCallFailed`, `StructuredLLMError.ParseFailed`, `AdkError.LlmCallError`
**When** `trigger.shouldRetry(error)` is called
**Then** the result is `true` for all of them

**Rationale**: After middleware adoption, errors flow through different layers and may be wrapped or unwrapped. The trigger must be shape-agnostic.

#### Scenario: AdkError.LlmCallError triggers LLMError retry

**Given** `RetryTrigger.LLMError` and an `AdkError.LlmCallError(LLMError.TimeoutError(...))`
**When** `trigger.shouldRetry(adkError)` is called
**Then** the result is `true` (the underlying `LLMError` is detected via `getCause`)

## MODIFIED Requirements

### Requirement: RetryTrigger.shouldRetry signature widened

The system SHALL change `RetryTrigger.shouldRetry` from `def shouldRetry(error: StructuredLLMError): Boolean` to `def shouldRetry(error: Throwable): Boolean`, and the implementation SHALL inspect `getCause`/`underlying` to classify the error.

**Given** the refactored `RetryTrigger` with `shouldRetry(error: Throwable)`
**When** called with any `Throwable` that is or wraps an `LLMError`
**Then** `RetryTrigger.LLMError` returns `true` if and only if the error (or its cause chain) contains an `LLMError`

**Rationale**: The signature widening is necessary because the middleware layer raises raw `LLMError`, not `StructuredLLMError`.

#### Scenario: shouldRetry accepts raw LLMError

**Given** `RetryTrigger.LLMError`
**When** `trigger.shouldRetry(LLMError.RateLimitError("..."): Throwable)` is called
**Then** the result is `true`

#### Scenario: shouldRetry accepts AdkError.LlmCallError

**Given** `RetryTrigger.LLMError`
**When** `trigger.shouldRetry(AdkError.LlmCallError(LLMError.TimeoutError("...")): Throwable)` is called
**Then** the result is `true`

## Properties (Ring 3)

### Property: getCause returns underlying LLMError for all wrapper variants

**Invariant**: For all `LLMError` values `e`, both `AdkError.LlmCallError(e).getCause == e` and `StructuredLLMError.LLMCallFailed(e, prompt).getCause == e`.

**Generator strategy**: `genLLMError` (constructive: `Gen.element1(LLMError.RateLimitError("..."), LLMError.TimeoutError("..."), LLMError.AuthenticationError("..."), LLMError.ModelError("..."))`), `genPrompt` (constructive: `genConversation.map(Prompt(_))`). Classify by LLMError variant.

```
forAll { (e: LLMError, prompt: Prompt) =>
  AdkError.LlmCallError(e).getCause == e &&
  StructuredLLMError.LLMCallFailed(e, prompt).getCause == e
}
```

### Property: RetryTrigger classification is wrapper-agnostic

**Invariant**: For all `LLMError` values `e` and all `RetryTrigger.LLMError`, `trigger.shouldRetry(e) == trigger.shouldRetry(StructuredLLMError.LLMCallFailed(e, prompt)) == trigger.shouldRetry(AdkError.LlmCallError(e))`.

**Generator strategy**: `genLLMError` (constructive, as above), `genPrompt` (constructive, as above). Classify by LLMError variant.

```
forAll { (e: LLMError, prompt: Prompt) =>
  val trigger = RetryTrigger.LLMError
  trigger.shouldRetry(e) == trigger.shouldRetry(StructuredLLMError.LLMCallFailed(e, prompt)) &&
  trigger.shouldRetry(e) == trigger.shouldRetry(AdkError.LlmCallError(e))
}
```

### Property: ParseFailed does not trigger LLMError retry

**Invariant**: For all `StructuredLLMError.ParseFailed` values, `RetryTrigger.LLMError.shouldRetry(parseError) == false`.

**Generator strategy**: `genParseFailed` (constructive: `genErrorsList.flatMap(errors => genRawResponse.map(raw => StructuredLLMError.ParseFailed(errors, raw)))`). Classify by error count.

```
forAll { (parseError: StructuredLLMError.ParseFailed) =>
  !RetryTrigger.LLMError.shouldRetry(parseError)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `RetryTrigger.LLMError.shouldRetry(error: StructuredLLMError)` with old signature | The signature is widened to `Throwable`; old-specific calls must update | `assertDoesNotCompile("val t = RetryTrigger.LLMError; t.shouldRetry(structuredError: StructuredLLMError)")` — note: this compiles because `StructuredLLMError` is a `Throwable`; the negative test is on the *narrowing* case: calling with a raw `LLMError` must compile, which was previously impossible |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| LlmCallError.getCause returns underlying | Requirement: LlmCallError getCause + Property | Hedgehog property + scenario test | ErrorHierarchyDedupSpec |
| LLMCallFailed.getCause returns underlying | Requirement: LLMCallFailed getCause + Property | Hedgehog property + scenario test | ErrorHierarchyDedupSpec |
| shouldRetry accepts Throwable | Requirement: shouldRetry signature widened | Compile test (raw LLMError compiles) | TypeContract |
| shouldRetry wrapper-agnostic | Requirement: RetryTrigger inspects underlying + Property | Hedgehog property | ErrorHierarchyDedupSpec |
| ParseFailed does not trigger LLMError retry | Property: ParseFailed no retry | Hedgehog property | ErrorHierarchyDedupSpec |
| AdkError.LlmCallError triggers LLMError retry | Requirement: handles both + Scenario | Scenario test | ErrorHierarchyDedupSpec |
| underlying field preserved | Requirement: LlmCallError source compat | Scenario test (field access) | ErrorHierarchyDedupSpec |
