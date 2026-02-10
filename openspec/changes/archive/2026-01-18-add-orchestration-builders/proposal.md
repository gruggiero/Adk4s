# Change: Add Chain/Graph/Workflow Orchestration Builders

## Why
Implement three levels of abstraction for composing nodes into executable pipelines - linear chains for simple workflows, DAGs for complex branching, and workflows with field-level data mapping.

## What Changes
- Add Chain builder for linear pipeline composition
- Add Graph builder for DAG-based pipelines with branching and validation
- Add Workflow builder with field-level data mapping between nodes
- Add Workflows4s WIO integration for graph execution
- Add supporting types: ChainStep, ChainBranch, GraphNode, FieldMapping, WorkflowNode, WIOExecutor

## Impact
- Affected specs: New spec `orchestration-builders`
- Affected code: `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/`
