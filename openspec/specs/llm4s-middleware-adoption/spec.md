# Spec: llm4s Middleware Adoption

<!-- This is a DELTA spec. Use ## ADDED Requirements for new content.
     Use ## MODIFIED Requirements to change existing requirements.

     This spec adopts the llm4s LLMMiddleware stack as the composition point
     for retry, logging, and rate-limiting, replacing the hand-rolled
     RetryStructuredLLM wrapper. -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `LLMMiddleware` | trait | `org.llm4s.llmconnect.middleware` |
| `MiddlewareClient` | class | `org.llm4s.llmconnect.middleware` |
| `ReliableClient` | final class extends LLMClient | `org.llm4s.reliability` |
| `RetryPolicy` | sealed trait | `org.llm4s.reliability` |
| `LoggingMiddleware` | class | `org.llm4s.llmconnect.middleware` |
| `RateLimitingMiddleware` | class | `org.llm4s.llmconnect.middleware` |
| `LLMClient` | trait | `org.llm4s.llmconnect` |
| `LLMError` | sealed trait | `org.llm4s.error` |
| `StructuredLLM[F[_]]` | trait | `org.adk4s.structured.core` |
| `StructuredLLMError` | sealed trait | `org.adk4s.structured.core` |
| `RetryTrigger` | enum | `org.adk4s.structured.core` |
| `SchemaAlignedParser` | object | `org.adk4s.structured.sap` |
| `Constraint` | case class | `org.adk4s.structured.core` |
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `Prompt` | case class | `org.adk4s.structured.core` |
| `RetryStructuredLLM` | private class | `org.adk4s.structured.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `StructuredOutputMiddleware` | trait (extends `LLMMiddleware`) | `LLMMiddleware` that injects schema into the conversation, calls the inner `LLMClient`, parses via SAP, evaluates constraints; the new home for the structured-output concern |
| `ParseRetryTrigger` | enum | Controls parse-failure retry within the structured layer: `ParseFailed`, `ValidationFailed`, `All` — distinct from LLM-error retry handled by `ReliableClient` |
| `StructuredLLM.fromClient(client, middlewares)` | factory method | Composes an `LLMMiddleware` stack via `MiddlewareClient`, then wraps in `StructuredOutputMiddleware` |
| `StructuredLLM.fromClientWithMiddleware` | factory method | Alias for `fromClient` with a single middleware argument for ergonomic call sites |

## ADDED Requirements

### Requirement: StructuredOutputMiddleware implements LLMMiddleware

The system SHALL provide a `StructuredOutputMiddleware` that extends the llm4s `LLMMiddleware` trait and performs schema injection, SAP parsing, and constraint evaluation within the middleware chain rather than as a wrapper around `StructuredLLM`.

**Given** an `LLMClient` wrapped by `StructuredOutputMiddleware` with a `Schema[A]` and a `Prompt`
**When** the middleware's wrapped client is called with a conversation
**Then** the schema block is appended to the last user message before the inner client is called, and the response is parsed via `SchemaAlignedParser.parse[A]` before being returned

**Rationale**: Per gap analysis §4.13.3, the structured-output concern belongs in the middleware chain so that retry/logging/rate-limiting compose around it uniformly, rather than as a bespoke wrapper at the `StructuredLLM` level.

#### Scenario: Successful structured completion via middleware

**Given** an `LLMClient` that returns a valid JSON response for `BankTransaction`, wrapped by `StructuredOutputMiddleware` with `Schema[BankTransaction]`
**When** the wrapped client is called with a conversation containing a user message
**Then** the response is parsed into a `BankTransaction` value and returned as `Right(value)`

#### Scenario: Parse failure surfaces as StructuredLLMError.ParseFailed

**Given** an `LLMClient` that returns malformed JSON, wrapped by `StructuredOutputMiddleware`
**When** the wrapped client is called
**Then** the result is `Left(StructuredLLMError.ParseFailed(errors, rawResponse))` with the raw response preserved

#### Scenario: Constraint evaluation failure surfaces as ValidationFailed

**Given** an `LLMClient` that returns valid JSON violating an `@assert` constraint, wrapped by `StructuredOutputMiddleware` with a `Schema[A]` carrying a failing `Constraint`
**When** the wrapped client is called
**Then** the result is `Left(StructuredLLMError.ValidationFailed(failedAsserts))` listing the failed assert labels

### Requirement: Middleware-composed factory

The system SHALL provide `StructuredLLM.fromClient(client: LLMClient, middlewares: List[LLMMiddleware])` that composes the supplied middleware stack via llm4s `MiddlewareClient` and then wraps the result in `StructuredOutputMiddleware`, producing a `StructuredLLM[F]`.

**Given** an `LLMClient` and a list of `LLMMiddleware` (e.g. `ReliableClient` config, `LoggingMiddleware`, `RateLimitingMiddleware`)
**When** `fromClient(client, middlewares)` is called
**Then** the resulting `StructuredLLM[F]` calls through the middleware stack in the supplied order, with `StructuredOutputMiddleware` applied last (innermost)

**Rationale**: The composition order (retry outermost, structured innermost) must be enforced by the factory so callers cannot accidentally place the structured middleware outside retry.

#### Scenario: Retry middleware wraps structured middleware

**Given** a `ReliableClient` configured with `RetryPolicy.ExponentialBackoff` and a `StructuredOutputMiddleware`
**When** the inner `LLMClient` returns an `LLMError` on the first call and succeeds on the second
**Then** the `ReliableClient` retries and the final result is the parsed value from the second call

#### Scenario: Empty middleware list preserves current behavior

**Given** an `LLMClient` and an empty middleware list
**When** `fromClient(client, Nil)` is called
**Then** the resulting `StructuredLLM[F]` behaves identically to the current `StructuredLLM.fromClient(client)` — no retry, no logging, no rate limiting

### Requirement: Parse-failure retry via ParseRetryTrigger

The system SHALL retry on parse failures using a `ParseRetryTrigger` that is distinct from the llm4s `RetryPolicy` (which only retries on `LLMError`), and the retry loop SHALL re-invoke the inner `LLMClient` (not just re-parse the existing response).

**Given** a `StructuredOutputMiddleware` with `ParseRetryTrigger.ParseFailed` and `maxAttempts = 3`
**When** the first call returns a response that fails to parse and the second call returns a parseable response
**Then** the middleware retries by calling the inner client again, and the final result is the parsed value from the second call

**Rationale**: Parse failures require a fresh LLM call (the model may produce different output), not just a re-parse. This is why parse-failure retry cannot be handled by `ReliableClient` alone.

#### Scenario: Parse failure retried successfully

**Given** a `StructuredOutputMiddleware` with `ParseRetryTrigger.ParseFailed`, `maxAttempts = 3`, and an inner client that returns malformed JSON once then valid JSON
**When** a structured completion is requested
**Then** the result is the parsed value and the inner client was called exactly 2 times

#### Scenario: Parse failure retries exhausted

**Given** a `StructuredOutputMiddleware` with `ParseRetryTrigger.ParseFailed`, `maxAttempts = 2`, and an inner client that always returns malformed JSON
**When** a structured completion is requested
**Then** the result is `Left(StructuredLLMError.ParseFailed(...))` from the last attempt, and the inner client was called exactly 2 times

#### Scenario: ParseRetryTrigger.All retries on both LLMError and ParseFailed

**Given** a `StructuredOutputMiddleware` with `ParseRetryTrigger.All`, `maxAttempts = 3`, and an inner client that returns `LLMError` once then malformed JSON once then valid JSON
**When** a structured completion is requested
**Then** the result is the parsed value from the third call

### Requirement: fromClientWithRetry delegates to middleware factory

The system SHALL deprecate `StructuredLLM.fromClientWithRetry` and reimplement it as a delegate to `fromClient` with a `ReliableClient` middleware plus `StructuredOutputMiddleware` with `ParseRetryTrigger`, preserving identical observable behavior to the current `RetryStructuredLLM`.

**Given** the deprecated `fromClientWithRetry(client, maxAttempts, delay, trigger, options)`
**When** called with the same parameters as the current implementation
**Then** the observable behavior (retry count, delay, which errors trigger retry) is identical to the current `RetryStructuredLLM`

**Rationale**: Binary compatibility for existing callers while the implementation moves to the middleware stack.

#### Scenario: Deprecated factory produces same retry count

**Given** the deprecated `fromClientWithRetry` with `maxAttempts = 3` and an inner client that always fails with `LLMError`
**When** a structured completion is requested
**Then** the inner client is called exactly 3 times (same as current `RetryStructuredLLM`)

### Requirement: Logging and rate-limiting as opt-in middlewares

The system SHALL wire `LoggingMiddleware` and `RateLimitingMiddleware` as opt-in middlewares passed to `fromClient`, with defaults (logging off, no rate limiting) that preserve current behavior.

**Given** a `fromClient` call with `LoggingMiddleware` in the middleware list
**When** a structured completion is requested
**Then** the log output includes the request and response (subject to `ContentRedactor`), and the structured result is unaffected

**Rationale**: These middlewares already exist in llm4s and are production-grade; wiring them as opt-in avoids changing default behavior while making them available.

#### Scenario: LoggingMiddleware does not alter the structured result

**Given** a `fromClient` call with `LoggingMiddleware` enabled
**When** a structured completion is requested
**Then** the returned value is identical to the same call without `LoggingMiddleware`

#### Scenario: RateLimitingMiddleware throttles calls

**Given** a `fromClient` call with `RateLimitingMiddleware` configured at 1 request/second
**When** 3 structured completions are requested in rapid succession
**Then** the calls are spaced at least 1 second apart

## MODIFIED Requirements

### Requirement: Retry behavior preservation

The system SHALL preserve the observable retry behavior of the current `RetryStructuredLLM` when using the middleware-composed factory with equivalent parameters.

**Given** a `RetryTrigger.All` with `maxAttempts = 3` and `delay = 100.millis`
**When** an operation fails with `StructuredLLMError.ParseFailed` on the first two attempts and succeeds on the third
**Then** the middleware-composed factory retries exactly twice with 100ms delay and returns the third result, identical to `RetryStructuredLLM`

**Rationale**: The refactor must not change retry semantics for existing callers.

## Properties (Ring 3)

### Property: Retry count bounded by maxAttempts

**Invariant**: For all `maxAttempts >= 1` and all failure sequences, the inner `LLMClient` is called at most `maxAttempts` times.

**Generator strategy**: `genMaxAttempts` (constructive: `Gen.int(Range.linear(1, 10))`), `genFailureSequence` (constructive: `Gen.list(Range.linear(1, 10), genFailureType)` where `genFailureType` is `Gen.element1(LLMError, ParseFailed)`). Classify by `maxAttempts` value.

```
forAll { (maxAttempts: Int, failures: List[FailureType]) =>
  val (result, callCount) = runWithFailures(maxAttempts, failures)
  callCount <= maxAttempts
}
```

### Property: No retry on success

**Invariant**: For all `maxAttempts >= 1`, if the first call succeeds, the inner `LLMClient` is called exactly once.

**Generator strategy**: `genMaxAttempts` (constructive: `Gen.int(Range.linear(1, 10))`), `genSuccessValue` (constructive: `Gen.element1(validBankTransaction)`). Classify by `maxAttempts`.

```
forAll { (maxAttempts: Int, successValue: BankTransaction) =>
  val (result, callCount) = runWithSuccess(maxAttempts, successValue)
  callCount == 1 && result == Right(successValue)
}
```

### Property: Middleware composition order preserves innermost-first execution

**Invariant**: For all middleware lists `[m1, m2, ..., mn]`, the `StructuredOutputMiddleware` is applied innermost (closest to the raw `LLMClient`), and `m1` is outermost.

**Generator strategy**: `genMiddlewareList` (constructive: `Gen.list(Range.linear(0, 5), genMiddlewareKind)` where `genMiddlewareKind` is `Gen.element1(Retry, Logging, RateLimiting)`). Classify by list length.

```
forAll { (middlewares: List[MiddlewareKind]) =>
  val composed = StructuredLLM.fromClient(client, middlewares.map(toMiddleware))
  composed.executionOrder.last == StructuredOutputMiddleware
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `new RetryStructuredLLM(...)` from outside `structured-llm` core | `RetryStructuredLLM` is `private` and deprecated; callers must use `fromClient` with middleware | `assertDoesNotCompile("new RetryStructuredLLM(...)")` |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| StructuredOutputMiddleware extends LLMMiddleware | Requirement: StructuredOutputMiddleware implements LLMMiddleware | Type system (trait inheritance) + compile test | MiddlewareAdoptionSpec, TypeContract |
| Schema injection happens before inner client call | Requirement: StructuredOutputMiddleware | Scenario test (assert last user message contains schema block) | MiddlewareAdoptionSpec |
| Parse failure retried by re-calling inner client | Requirement: Parse-failure retry | Scenario test (assert call count) | MiddlewareAdoptionSpec |
| Retry count ≤ maxAttempts | Property: Retry count bounded | Hedgehog property | MiddlewareAdoptionSpec |
| No retry on success | Property: No retry on success | Hedgehog property | MiddlewareAdoptionSpec |
| Composition order: structured innermost | Requirement: Middleware-composed factory + Property | Hedgehog property + scenario test | MiddlewareAdoptionSpec |
| fromClientWithRetry behavior identical | Requirement: fromClientWithRetry delegates | Regression scenario test (compare call counts) | MiddlewareAdoptionSpec |
| LoggingMiddleware does not alter result | Requirement: Logging/rate-limiting opt-in | Scenario test | MiddlewareAdoptionSpec |
| RetryStructuredLLM not constructible externally | Compile-Negative | assertDoesNotCompile | TypeContract |
