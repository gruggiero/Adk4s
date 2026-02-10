# hybrid-orchestration Specification

## Purpose
Synced from graph-orchestration-hybrid-approach to align with ADR-003: ADK4S `Graph` remains the primary orchestration API and integrates selected LLM4S orchestration capabilities via bridges and utilities.

## Requirements

### Requirement: Runnable policies

The system SHALL provide Cats Effect implementations of the LLM4S policy set as utilities on `Runnable[I, O]`.

#### Scenario: Retry policy on Runnable
- **GIVEN** a `Runnable[I, O]` that may fail
- **WHEN** `runnable.withRetry(...)` is applied
- **THEN** failures are retried according to the configured strategy
- **AND** the resulting `Runnable` preserves the `invoke`, `stream`, `collect`, and `transform` paradigms

#### Scenario: Timeout policy on Runnable
- **GIVEN** a `Runnable[I, O]` that may hang
- **WHEN** `runnable.withTimeout(...)` is applied
- **THEN** execution fails with a timeout error after the configured duration

#### Scenario: Fallback policy on Runnable
- **GIVEN** a primary `Runnable[I, O]` and a fallback `Runnable[I, O]`
- **WHEN** `primary.withFallback(fallback)` is applied
- **THEN** the fallback is used when the primary fails

### Requirement: TypedAgent bridge

The system SHALL provide an integration layer that allows LLM4S `TypedAgent[I, O]` to be used as an ADK4S `Runnable[I, O]`.

#### Scenario: Use TypedAgent as Graph node
- **GIVEN** a LLM4S `TypedAgent[I, O]`
- **WHEN** it is converted to a `Runnable[I, O]`
- **THEN** execution uses `IO.fromFuture` to bridge Future-based evaluation into Cats Effect IO
- **AND** the resulting runnable can be used in an ADK4S `Graph` node

### Requirement: Parallel DAG execution

The system SHALL enhance Graph execution to support parallel execution of independent nodes in DAG layers.

#### Scenario: Execute independent nodes in parallel
- **GIVEN** a DAG where multiple nodes are ready to run (no unmet dependencies)
- **WHEN** the graph executor executes the DAG
- **THEN** those nodes are executed concurrently
- **AND** the executor enforces a configurable maximum parallelism

### Requirement: Graph callbacks/aspects

The system SHALL provide a callback/aspect API integrated with graph execution.

#### Scenario: Observe node lifecycle
- **GIVEN** a configured callback implementation
- **WHEN** a node starts and completes (or fails)
- **THEN** the callback receives lifecycle events with node identity and timing metadata

### Requirement: Chain builder

The system SHALL provide a Chain builder for ergonomic construction of linear workflows that compile to an ADK4S `Graph`.

#### Scenario: Build a linear chain
- **GIVEN** a sequence of compatible steps
- **WHEN** a Chain is built
- **THEN** the chain compiles to a `Graph` with type-safe edges
