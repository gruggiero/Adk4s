# Spec: Error Enrichment with Fallback History

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `StructuredLLMError` | sealed trait | `org.adk4s.structured.core` |
| `ParseError` | sealed trait | `org.adk4s.structured.core` |
| `Prompt` | case class | `org.adk4s.structured.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `AttemptRecord` | case class | Single attempt: client, error, rawResponse, timestamp |
| `AggregatedError` | case class | StructuredLLMError variant with full attempt history |

## ADDED Requirements

### Requirement: Attempt history in errors

The system SHALL enrich errors with a complete history of all attempts when retry or fallback is used, including the client name, error, raw response, and timestamp for each attempt.

**Given** a retry/fallback sequence where 3 attempts were made (2 failures, 1 success)
**When** the final result is returned
**Then** if the final attempt fails, the error contains `Vector[AttemptRecord]` with all 3 attempts

**Rationale**: BAML's `detailed_message` includes the complete attempt history, which is essential for debugging structured output failures.

#### Scenario: All attempts fail — aggregated error

**Given** 3 attempts: OpenAI (ParseFailed), Anthropic (ParseFailed), OpenAI-mini (LLMError)
**When** all fail
**Then** the error is `AggregatedError(attempts=[3 records], finalError=LLMError(...))`

#### Scenario: Some attempts succeed — no aggregation

**Given** 2 attempts: OpenAI (ParseFailed), Anthropic (Success)
**When** the second succeeds
**Then** the result is `Success` with no error aggregation

#### Scenario: Single attempt — no aggregation

**Given** 1 attempt that fails
**When** no retry is configured
**Then** the error is the original `StructuredLLMError` (not `AggregatedError`)

### Requirement: AttemptRecord contains diagnostic information

The system SHALL include `client: String`, `error: StructuredLLMError`, `rawResponse: String`, and `timestamp: Long` in each `AttemptRecord`.

**Given** an attempt that failed with a parse error
**When** recorded as an `AttemptRecord`
**Then** the record contains the client name, the parse error, the raw LLM response, and the epoch timestamp

#### Scenario: Parse failure record

**Given** an attempt by "openai/gpt-4o" that returned unparseable JSON
**When** recorded
**Then** `AttemptRecord(client="openai/gpt-4o", error=ParseFailed(...), rawResponse="{invalid json", timestamp=...)`

## Properties (Ring 3)

### Property: Aggregated error contains all attempts

**Invariant**: For any sequence of N failed attempts, `AggregatedError.attempts.size == N`.

**Generator strategy**: `Gen` of failure sequences (1-5 attempts), classify by attempt-count.

```
forAll { (failures: List[StructuredLLMError]) =>
  val error = aggregateErrors(failures.map(f => AttemptRecord("client", f, "", 0L)))
  error.attempts.size == failures.size
}
```

### Property: AggregatedError is only produced when multiple attempts fail

**Invariant**: A single failed attempt produces the original error, not `AggregatedError`.

**Generator strategy**: `Gen` of single StructuredLLMError, classify by error-type.

```
forAll { (error: StructuredLLMError) =>
  val result = withRetry(List(error), RetryPolicy.noRetry, RetryTrigger.All)
  result.isLeft && result.left.get != AggregatedError(...)
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Attempt history in errors | Requirement 1 | Scenario test | ErrorEnrichmentSpec |
| AttemptRecord fields | Requirement 2 | Scenario test | ErrorEnrichmentSpec |
| All attempts recorded | Property 1 | Hedgehog property | ErrorEnrichmentSpec |
| No aggregation for single attempt | Property 2 | Hedgehog property | ErrorEnrichmentSpec |
