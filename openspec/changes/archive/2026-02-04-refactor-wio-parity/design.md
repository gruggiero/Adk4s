## Target Module
`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/`

## Context
The ADK4S wiograph module provides a typed graph layer (`WIOGraph[Ctx, In, Err, Out]`) that compiles to Workflows4s WIO. The core type signatures and compilation are already aligned with WIO semantics. Several WIO constructors (forEach, checkpoint, retry, interrupt) were missing, and `WIOForkNode` lacked an `otherwise` branch.

## Goals / Non-Goals
- Goals:
  - Add missing WIO constructor support: `WIOForEachNode` as a dedicated node type; checkpoint, retry, and interruption as graph-level modifiers (`WIONodeModifier`).
  - Add optional `otherwise` branch to `WIOForkNode` via `WIOForkNode.withOtherwise` factory method.
  - Update compilation to apply modifiers during `compileFromNode`.
- Non-Goals:
  - ~~GraphBuilder DSL with combinators (map/flatMap/andThen/transform/handleErrorWith)~~ — The current `WIOGraph` API + `WIONode` factories + `WIONodeModifier` system already provides equivalent functionality. See `graph-builder-dsl-equivalence.md`.
  - ~~ADK-specific node types (ChatModel, Tools, StructuredModel, StructuredTool, Merge)~~ — These are higher-level abstractions that belong in a separate module/layer built on top of `WIORunIONode`, NOT as first-class `WIONode` variants.
  - Backward compatibility or migration shims.
  - Changes to the separate `graph/` module (that is a different concern).

## Already Implemented
The following are already complete in wiograph:
- **`WIOGraph[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]]`**: fully typed, no type erasure.
- **`WIOGraph.toWIO`**: compiles graph to `WIO[In, Err, Out, Ctx]` with validation (cycles, entry, reachability).
- **`WIONode` sealed trait with `toWIO`** method on each variant.
- **Implemented node types**: `WIOPureNode`, `WIORunIONode`, `WIOForkNode`, `WIOLoopNode`, `WIOAwaitNode`, `WIOHandleSignalNode`, `WIOParallelNode`, `WIOSubGraphNode`, `WIOForEachNode`.
- **Factory methods on `WIONode` companion**: `pure`, `pureEither`, `error`, `runIO`, `runIOWithError`, `loop`, `loopEither`, `await`, `awaitDynamic`, `parallel`, `parallelWithState`, `subGraph`, `handleSignal`, `handleSignalPurely`, `forEach`.
- **Fork compilation**: `WIOForkNode` compiles to `WIO.Fork` with runtime predicates; `binaryFork` helper available. `WIOForkNode.withOtherwise` appends a catch-all branch.
- **Event-sourced execution**: `WIORunIONode`, `WIOHandleSignalNode`, `WIOAwaitNode` all use `EventHandler`/`SignalHandler` patterns, inheriting replay safety from Workflows4s runtime.
- **Modifier system**: `WIONodeModifier` sealed trait with `CheckpointModifier`, `RetryModifier`, `InterruptionModifier`. Modifiers are stored in `NodeEntry.modifiers` and applied via `applyModifiers` during `compileFromNode`.

## Decisions
- **WIO-native compilation**: `WIOGraph.toWIO` compiles `WIONode` instances to typed WIO steps — this is already implemented and working.
- **Fork with otherwise**: Implemented via `WIOForkNode.withOtherwise` factory that appends a catch-all branch with `predicate = _ => Some(_)`. During `compileFork`, trailing branches without edges use their workflow directly.
- **Checkpoint/Retry/Interruption as modifiers, NOT node types**: These cross-cutting concerns are implemented as `WIONodeModifier` instances that wrap any existing node's WIO. This is more flexible than dedicated node types — any node can have checkpoint, retry, or interruption behavior applied without creating a separate node type. Modifiers compose left-to-right via `applyModifiers`.
- **ForEach as a dedicated node type**: `WIOForEachNode` is a `WIONode` variant because it has unique structure (inner workflow, element extraction, event embedding) that doesn't fit the modifier pattern.
- **Event-sourced runtime**: checkpoint/retry rely on Workflows4s runtime behavior with explicit Event types, inherited from WIO semantics.
- **ADK-specific nodes**: ChatModel, Tools, StructuredModel, StructuredTool, and Merge are higher-level abstractions built on top of `WIORunIONode`, NOT first-class `WIONode` variants. This is out of scope for this change.

## Risks / Trade-offs
- **Modifier type safety**: Modifiers are typed `WIONodeModifier[Ctx, I, Err, O]` matching the node they modify. Because modifiers are stored per-node in `NodeEntry`, type safety is preserved at the point of modifier application. However, modifiers cannot be applied to fork nodes during compilation because the compiled fork produces `WIO[A, Err, Out, Ctx]` (widened from `B` to `Out`), while modifiers expect the original `B` type.
- **New WIO node types**: `WIOForEachNode` requires understanding of internal Workflows4s WIO constructors (ForEach, WorkflowEmbedding, SignalRouter).

## Migration Plan
- ~~Add new `WIONode` variants and factory methods~~ — DONE (WIOForEachNode)
- ~~Add `otherwise` to `WIOForkNode`~~ — DONE (via withOtherwise factory)
- ~~Add modifier system for checkpoint/retry/interruption~~ — DONE (WIONodeModifier)
- ~~Update tests to cover all new node types and modifier usage~~ — DONE (WIONodeModifierTest)

## Decisions (Resolved Questions)
- `WIOGraph` already encodes `WorkflowContext` at the type level as `WIOGraph[Ctx, In, Err, Out]`.
- `ErrorMeta` and `SignalDef` are imported from Workflows4s and used directly in wiograph.
- GraphBuilder DSL is NOT needed — current API provides equivalent coverage.
- ADK-specific nodes are NOT wiograph-level — they belong in a higher-level abstraction layer.
