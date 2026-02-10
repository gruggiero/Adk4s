## MODIFIED Requirements

### Requirement: Chain builder for Graph

The system SHALL provide a fluent Chain builder for constructing linear workflows that compile to an ADK4S `Graph`.

#### Scenario: Build a chain with typed steps
- **GIVEN** a Chain builder with an initial input type `In`
- **WHEN** steps are appended where each step output matches the next step input
- **THEN** the chain compiles to a `Graph[In, Out]` with type-safe edges

#### Scenario: Build a chain using existing Graph node types
- **GIVEN** Chain steps that wrap existing Graph node types (ChatModel, Tools, Lambda)
- **WHEN** the chain is compiled
- **THEN** the resulting Graph uses the same node implementations as direct Graph construction

### Requirement: TypedAgent node factory

The system SHALL provide a builder/factory that allows creating Graph nodes from LLM4S `TypedAgent[I, O]` via the TypedAgent→Runnable bridge.

#### Scenario: Add TypedAgent as a node in Graph builder
- **GIVEN** a `TypedAgent[I, O]`
- **WHEN** it is added as a node in a Graph/Chain builder
- **THEN** it behaves as a `Runnable[I, O]` node in the graph

### Requirement: Apply policies and callbacks through builders

The system SHALL provide configuration hooks so users can apply policies and register callbacks when building orchestration structures.

#### Scenario: Apply retry/timeout/fallback to a built Runnable
- **GIVEN** a builder-produced `Runnable[I, O]`
- **WHEN** a policy is applied
- **THEN** the resulting Runnable preserves the streaming paradigms

#### Scenario: Register callbacks for execution
- **GIVEN** a graph built through builders
- **WHEN** callbacks are configured
- **THEN** Graph execution emits lifecycle events through the callback interface
