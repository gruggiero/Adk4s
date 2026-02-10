## Context
Graph compilation currently produces a stub Runnable and WIOExecutor.toWIO returns a placeholder. The orchestration builders spec requires a WIO-backed graph execution model with explicit entry/end nodes and branching. Option A recommends a static DAG compiler that maps each GraphNode to a WIO step, and composes them using Workflows4s primitives.

## Goals / Non-Goals
- Goals:
  - Implement a static DAG-to-WIO compiler that respects entry/end nodes and Branch routing.
  - Introduce structured graph node variants based on StructuredLLM and StructuredToolCall.
  - Require WIO-capable execution for all graph nodes via NodeExecutable.
  - Introduce MergeNode for explicit fan-in and keep fan-in deterministic.
  - Preserve typed edge/branch relationships and validate branch targets against outgoing edges.
- Non-Goals:
  - Support cycles or runtime graph traversal loops.
  - Introduce merge/join nodes beyond MergeNode.

## Decisions
- Decision: Use Option A static DAG compilation for WIOExecutor.
  - Rationale: Aligns with existing DAG validation, avoids runtime type erasure, and keeps execution deterministic.
  - Alternatives considered: Dynamic WIO loop interpreter (Option B) and WIO-first nodes (Option C).
- Decision: Require NodeExecutable for GraphNode and expose a WIO step for each node.
  - Rationale: Ensures every node can be compiled without casts and removes implicit execution logic from the compiler.
- Decision: Allow fan-in only when the target node is a MergeNode.
  - Rationale: Keeps WIO composition deterministic while supporting explicit join semantics.
- Decision: Validate branch targets against declared outgoing edges.
  - Rationale: Keeps topology explicit for static compilation and simplifies reachability checks.
- Decision: Add structured graph node variants as optional nodes.
  - Rationale: StructuredLLM and StructuredToolCall already exist; adding nodes enables typed pipelines without removing ToolsNode.

## Risks / Trade-offs
- Introducing MergeNode changes fan-in behavior and may be a breaking change for graphs that relied on implicit fan-in.
- Structured nodes introduce new dependencies in orchestration but keep optional usage.
- Validating branch targets against edges requires updating graph builders to declare edges for branch destinations.

## Migration Plan
1. Introduce NodeExecutable and wire GraphNode variants to expose WIO steps.
2. Add MergeNode and update GraphValidation fan-in rules to allow fan-in only for merge nodes.
3. Preserve typed edge/branch relationships and enforce branch targets match outgoing edges.
4. Implement WIOExecutor DAG compilation and update execute to run WIO via a Workflows4s runtime.
5. Update orchestration builder tests to cover DAG compilation, branching, merge nodes, structured nodes, and validation.

## Open Questions
- Should Workflow be reimplemented as a thin WIO composition layer?
