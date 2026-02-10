# Change: Refactor WIOGraph for Full WIO Parity

## Target Module
`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/`

This change targets the **wiograph** module (NOT the `graph/` module). The wiograph module contains the WIO-native graph implementation: `WIOGraph`, `WIONode`, `WIONodeRef`, `WIONodeModifier`, and `WIOGraphError`.

## Why
The ADK4S wiograph module did not cover all Workflows4s WIO constructors (forEach, checkpoint, retry, interrupt were missing), lacked an optional `otherwise` branch on `WIOForkNode`, and needed a consistent approach for cross-cutting concerns like checkpoint/retry/interruption.

## What Changes
- Added `WIOForEachNode` as a dedicated `WIONode` variant for collection iteration.
- Added `WIONodeModifier` system (`CheckpointModifier`, `RetryModifier`, `InterruptionModifier`) to apply checkpoint, retry, and interruption behavior as graph-level modifiers on any existing node.
- Added `WIOForkNode.withOtherwise` factory method for optional fallback branches.
- Updated `WIOGraph` compilation (`compileFromNode`) to apply modifiers via `applyModifiers`.
- Updated `NodeEntry` to store modifiers per-node.
- Added `WIOGraph.withCheckpoint`, `WIOGraph.withRetry`, `WIOGraph.withInterruption` convenience methods.

## Out of Scope
- **GraphBuilder DSL**: A separate DSL with combinators (map/flatMap/andThen/transform/handleErrorWith) is not needed. The current `WIOGraph` API + `WIONode` factories + `WIONodeModifier` system provides equivalent functionality. See `graph-builder-dsl-equivalence.md` for a detailed mapping.
- **ADK-specific node types**: ChatModel, Tools, StructuredModel, StructuredTool, and Merge are higher-level abstractions that belong in a separate module/layer built on top of `WIORunIONode`, NOT as first-class `WIONode` variants in wiograph.

## Impact
- Affected specs: `orchestration-builders`, `branching-routing`, `state-management`.
- Affected code: `adk4s-orchestration/wiograph/` — `WIOGraph`, `WIONode`, `WIONodeRef`, `WIONodeModifier` (new), `WIOGraphError`; tests.
