# Spec: Fallback and Round-Robin Client Strategies

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `LLMMiddleware` (llm4s) | trait | `org.llm4s.llmconnect.middleware` |
| `RetryPolicy` (llm4s) | sealed trait | `org.llm4s.reliability` |
| `StructuredLLM[F[_]]` | trait | `org.adk4s.structured.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `FallbackStrategy` | case class | Ordered client list — tries each in order on failure |
| `RoundRobinStrategy` | case class | Rotating client list — distributes calls for load balancing |
| `ClientStrategyMiddleware` | class | LLMMiddleware implementing fallback or round-robin |
| `withFallback` | method | StructuredLLM factory for fallback clients |
| `withRoundRobin` | method | StructuredLLM factory for round-robin clients |

## ADDED Requirements

### Requirement: Fallback client strategy

The system SHALL support a fallback strategy that tries LLM clients in order, moving to the next on failure.

**Given** a list of LLM clients `[clientA, clientB, clientC]`
**When** `StructuredLLM.withFallback(clients)` is used and `clientA` fails
**Then** the system tries `clientB`, then `clientC`, until one succeeds or all fail

#### Scenario: First client succeeds

**Given** fallback clients `[clientA, clientB]` and `clientA` succeeds
**When** `complete` is called
**Then** the result is from `clientA` and `clientB` is not called

#### Scenario: First fails, second succeeds

**Given** fallback clients `[clientA, clientB]` and `clientA` raises `LLMError`
**When** `complete` is called
**Then** the result is from `clientB`

#### Scenario: All clients fail

**Given** fallback clients `[clientA, clientB]` and both fail
**When** `complete` is called
**Then** the result is `Failure` with the last error

### Requirement: Round-robin client strategy

The system SHALL support a round-robin strategy that rotates through LLM clients for load balancing.

**Given** a list of LLM clients `[clientA, clientB, clientC]`
**When** `StructuredLLM.withRoundRobin(clients)` is used for multiple calls
**Then** calls are distributed: call 1 → clientA, call 2 → clientB, call 3 → clientC, call 4 → clientA, etc.

#### Scenario: Sequential calls rotate

**Given** round-robin clients `[clientA, clientB, clientC]`
**When** 3 sequential `complete` calls are made
**Then** each client handles exactly 1 call

#### Scenario: Failed call falls through to next

**Given** round-robin clients `[clientA, clientB]` and `clientA` fails on the current call
**When** `complete` is called
**Then** `clientB` is tried as fallback, and the next call starts from `clientB`

## Properties (Ring 3)

### Property: Fallback tries clients in order

**Invariant**: For any ordered client list, fallback tries clients in the given order and returns the first success.

**Generator strategy**: `Gen` of client lists (2-5 clients) with random success/failure, classify by success-position.

```
forAll { (clients: List[MockClient], target: Int) =>
  val result = executeFallback(clients)
  val firstSuccess = clients.indexWhere(_.succeeds)
  result == (if firstSuccess >= 0 then Right(clients(firstSuccess).result) else Left(clients.last.error))
}
```

### Property: Round-robin distributes evenly over successful calls

**Invariant**: For N successful sequential calls with C clients, each client handles approximately N/C calls (±1).

**Generator strategy**: `Gen` of call counts (1-20), `Gen` of client counts (1-5), classify by N%C.

```
forAll { (numCalls: Int, numClients: Int) =>
  val distribution = executeRoundRobin(numCalls, numClients)
  distribution.forall(count => count == numCalls / numClients || count == numCalls / numClients + 1)
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Fallback tries in order | Requirement 1 | Hedgehog property | FallbackSpec |
| All-clients-fail returns last error | Requirement 1 | Scenario test | FallbackSpec |
| Round-robin rotates | Requirement 2 | Hedgehog property | FallbackSpec |
| Round-robin falls through on failure | Requirement 2 | Scenario test | FallbackSpec |
