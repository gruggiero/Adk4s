# Change: Add WIO Graph Executor (Option A)

## Why
The current WIOExecutor is a stub and does not implement the intended DAG execution model for Graph workflows. We need a concrete, deterministic Workflows4s-based executor aligned with the orchestration builders spec and the recommended Option A static DAG compilation.

## What Changes
- Implement a static DAG-to-WIO compiler for Graph (Option A) with deterministic entry/end handling, branch translation, and explicit MergeNode fan-in handling.
- Introduce WIO-capable GraphNode execution via a NodeExecutable abstraction and add a MergeNode variant for explicit fan-in.
- Preserve typed edge/branch relationships in Graph construction and enforce branch targets to match declared outgoing edges.
- Clarify fan-in semantics by rejecting multiple incoming edges unless the target node is a MergeNode.
- Execute graphs via a Workflows4s runtime from WIOExecutor.execute.

## Impact
- Affected specs: orchestration-builders
- Affected code: GraphNode, GraphValidation, WIOExecutor, orchestration tests
