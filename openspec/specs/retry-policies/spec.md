# Spec: Retry Policies

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `StructuredLLM[F[_]]` | trait | `org.adk4s.structured.core` |
| `StructuredLLMError` | sealed trait | `org.adk4s.structured.core` |
| `ParseError` | sealed trait | `org.adk4s.structured.core` |
| `RetryPolicy` (llm4s) | sealed trait | `org.llm4s.reliability` |
| `ReliableClient` (llm4s) | class | `org.llm4s.reliability` |
| `LLMMiddleware` (llm4s) | trait | `org.llm4s.llmconnect.middleware` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `RetryTrigger` | enum | LLMError / ParseFailure / ValidationFailure / All |
| `ParseRetryAdapter` | class | Adapts StructuredLLM to retry on parse failures, not just LLM errors |
| `fromClientWithRetry` | method | New StructuredLLM factory with retry policy |

## ADDED Requirements

### Requirement: Retry on parse failures

The system SHALL retry LLM calls when parsing fails (not just when LLM errors occur), using a configurable `RetryTrigger` to determine which failure types trigger retries.

**Given** an LLM call that succeeds at the API level but produces unparseable output
**When** `RetryTrigger` includes `ParseFailure`
**Then** the system retries the LLM call up to `maxRetries` times

**Rationale**: llm4s `ReliableClient` only retries on `LLMError`. Parse failures are equally retryable — the LLM may produce valid output on retry.

#### Scenario: Parse failure triggers retry

**Given** a mock LLM that returns invalid JSON on first call, valid JSON on second
**When** `fromClientWithRetry(client, RetryPolicy.fixedDelay(maxAttempts=3), RetryTrigger.All)` is used
**Then** the result is `Success` with the value from the second call

#### Scenario: Max retries exhausted

**Given** a mock LLM that always returns invalid JSON
**When** `fromClientWithRetry(client, RetryPolicy.fixedDelay(maxAttempts=2), RetryTrigger.ParseFailure)` is used
**Then** the result is `Failure(ParseFailed(...))` after 2 attempts

#### Scenario: LLM error — no retry when trigger is ParseFailure only

**Given** a mock LLM that raises `LLMError`
**When** `fromClientWithRetry(client, policy, RetryTrigger.ParseFailure)` is used
**Then** the error is returned immediately without retry

### Requirement: Configurable retry triggers

The system SHALL support `RetryTrigger` enum with `LLMError`, `ParseFailure`, `ValidationFailure`, and `All` to control which failure types trigger retries.

**Given** a retry configuration with `RetryTrigger.ParseFailure`
**When** an LLM error occurs
**Then** no retry is attempted and the error is returned

**Rationale**: Different use cases may want to retry only on certain failure types (e.g., retry on parse failure but not on authentication errors).

## Properties (Ring 3)

### Property: Retry count never exceeds maxAttempts

**Invariant**: For any failure sequence, the total number of attempts never exceeds `maxAttempts`.

**Generator strategy**: `Gen` of failure sequences (List of LLMError | ParseFailed), `Gen` of maxAttempts (1 to 5), classify by failure-count vs maxAttempts.

```
forAll { (failures: List[FailureType], maxAttempts: Int) =>
  val attempts = executeWithRetry(failures, RetryPolicy.fixedDelay(maxAttempts), RetryTrigger.All)
  attempts.size <= maxAttempts
}
```

### Property: Success on first attempt does not retry

**Invariant**: When the first attempt succeeds, no additional attempts are made.

**Generator strategy**: `Gen` of successful results, classify by result-type.

```
forAll { (success: A) =>
  val attempts = executeWithRetry(List(success), policy, trigger)
  attempts.size == 1
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Parse failure triggers retry | Requirement 1 | Scenario test | RetrySpec |
| Max retries not exceeded | Requirement 1 | Hedgehog property | RetrySpec |
| RetryTrigger controls which failures retry | Requirement 2 | Scenario test | RetrySpec |
| No retry on success | Property 2 | Hedgehog property | RetrySpec |
