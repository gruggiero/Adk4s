## ADDED Requirements
### Requirement: Cast-Free Orchestration Builders
The system SHALL implement orchestration builders (Chain, Graph, Workflow) without using `asInstanceOf` or `isInstanceOf`, relying on pattern matching and typed helpers to compose runnables and execute graphs.

#### Scenario: Compile chain without runtime casts
- **GIVEN** a Chain with multiple steps
- **WHEN** `chain.compile` is called
- **THEN** the Chain compiles via typed composition without `asInstanceOf` or `isInstanceOf`

#### Scenario: Compile workflow without runtime casts
- **GIVEN** a Workflow with nodes and an end node
- **WHEN** `workflow.compile` is called
- **THEN** the Workflow compiles without `asInstanceOf` or `isInstanceOf`

#### Scenario: Execute graph without runtime casts
- **GIVEN** a Graph with an entry node and end nodes
- **WHEN** graph execution is invoked
- **THEN** the execution path avoids `asInstanceOf` and uses typed handlers or pattern matching
