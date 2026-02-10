# Change: Explicit Graph Entry/End Nodes and Typed ToolsNode

## Why
Graph-to-WIO conversion needs a deterministic entry and end definition, and the current Graph structure relies on implicit topology. The current ToolsNode type is `Any`, which prevents type-safe conversion and violates project type-safety constraints.

## What Changes
- **BREAKING** Add explicit entry node and end nodes to Graph, with builder methods to set them.
- **BREAKING** Update Graph validation to require valid entry/end nodes before compile.
- **BREAKING** Type GraphNode.ToolsNode as `org.adk4s.agent.tools.ToolsNode`.
- Update WIOExecutor to use explicit entry/end nodes for WIO construction.
- Update orchestration tests to use the new Graph API and typed ToolsNode.

## Impact
- Affected specs: `orchestration-builders`
- Affected code: `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`, `GraphNode.scala`, `GraphValidation.scala`, `execution/WIOExecutor.scala`, related tests
