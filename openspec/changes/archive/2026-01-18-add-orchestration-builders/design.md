## Context
ADK4S needs orchestration capabilities to compose nodes (Lambda, ChatModel, Tools) into executable pipelines. Three abstraction levels are required:
- **Chain**: Simple linear composition for straightforward workflows
- **Graph**: Complex DAGs with conditional routing for advanced use cases
- **Workflow**: Field-level data mapping for precise control over data flow

The implementation leverages Workflows4s WIO for robust workflow execution.

## Goals / Non-Goals
- Goals:
  - Provide type-safe, composable builders for pipelines
  - Support linear, DAG-based, and field-mapped workflows
  - Validate graph structure before execution
  - Integrate with existing Runnable/Lambda abstractions
  - Use Workflows4s WIO for execution

- Non-Goals:
  - Visual workflow editing UI
  - Distributed execution across machines
  - Workflow persistence/durability
  - Workflow monitoring/observability

## Decisions

### Decision 1: Three separate builders instead of one unified API
**What**: Create Chain, Graph, and Workflow as distinct builder types.
**Why**: Each serves different use cases with different complexity:
- Chain: Simple, linear, zero configuration overhead
- Graph: Complex, DAG-based, needs validation
- Workflow: Fine-grained control with field mapping
**Alternatives considered**: Single builder with mode flags (rejected - API confusion), only Graph with simplification helpers (rejected - Chain too common case)

### Decision 2: Workflows4s WIO for execution engine
**What**: Use workflows4s WIO as the underlying execution engine for Graph.
**Why**: Already listed as project dependency, provides battle-tested workflow runtime, supports branching and state management.
**Alternatives considered**: Custom recursive execution (rejected - reinventing wheel), fs2 streams (rejected - no graph semantics)

### Decision 3: ValidatedNec for Graph mutations
**What**: Graph operations (addNode, addEdge, addBranch) return `ValidatedNec[AdkError, Graph[I, O, S]]`.
**Why**: Enables accumulation of multiple validation errors, allows compile-time error handling, functional approach to builder state.
**Alternatives considered**: Throwing exceptions (rejected - not functional), Try (rejected - only one error), Either (rejected - only one error)

### Decision 4: FieldPath for Workflow data mapping
**What**: Use FieldPath type for specifying source/destination fields in Workflow.
**Why**: Type-safe path references, composable (can nest paths), enables precise data flow control.
**Alternatives considered**: String paths (rejected - no type safety), lens libraries (rejected - additional complexity)

### Decision 5: Stateful Graphs via optional stateGen
**What**: Graph accepts optional `IO[StateRef[IO, S]]` for shared state across nodes.
**Why**: Some workflows need shared state (e.g., conversation history, counters), optional to avoid overhead for stateless workflows.
**Alternatives considered**: Always require state (rejected - unnecessary overhead), no state support (rejected - limits use cases)

## Risks / Trade-offs

- **Risk**: Workflows4s learning curve
  - Mitigation: Provide clear abstractions (Chain/Graph/Workflow), document examples

- **Risk**: Graph validation complexity
  - Mitigation: Separate GraphValidation module, comprehensive unit tests

- **Trade-off**: Chain is Graph subset
  - Rationale: Chain provides simpler API for common linear case, worth the small code duplication

- **Risk**: Workflow field mapping overhead
  - Mitigation: Provide defaults (passthrough mapping), optimize common cases

## Migration Plan
N/A - this is a new capability with no existing migration path.

## Open Questions
- Should Workflow support sub-graphs as nodes?
- Should Chain have a simplified syntax for 2-3 node cases?
- Should Graph validation be opt-out (skip for trusted graphs)?
