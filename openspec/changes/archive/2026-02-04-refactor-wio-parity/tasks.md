## Target Module
`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/`

## 1. Implementation

### Already Done
- [x] 1.1 Design `WIOGraph[Ctx, In, Err, Out]` type signature with WorkflowContext, State, Event, Err
- [x] 1.2 Implement `WIOGraph.toWIO` compilation (no type erasure, no `Any`, no `unsafeRunSync` branching)
- [x] 1.3 Implement core WIONode types: WIOPureNode, WIORunIONode, WIOForkNode, WIOLoopNode, WIOAwaitNode, WIOHandleSignalNode, WIOParallelNode, WIOSubGraphNode
- [x] 1.4 Implement WIONode factory methods (pure, pureEither, error, runIO, runIOWithError, loop, loopEither, await, awaitDynamic, parallel, parallelWithState, subGraph, handleSignal, handleSignalPurely)
- [x] 1.5 Implement graph validation (cycle detection, entry node check, end node reachability)
- [x] 1.6 Implement fork compilation with runtime predicate evaluation (compileFork, binaryFork)
- [x] 1.7 Implement event-sourced execution for RunIO, Signal, Await nodes via EventHandler/SignalHandler patterns
- [x] 1.8 Implement WIOGraphError ADT (CycleDetected, MissingEntry, UnreachableEnd)
- [x] 1.9 Write tests for all implemented node types, graph building, validation, and compilation

### Remaining
- [x] 2.1 Add WIOForEachNode variant and factory method
- [x] 2.2 Add checkpoint support as graph-level modifier (`CheckpointModifier` + `WIOGraph.withCheckpoint`)
  - *Architectural note:* Implemented as a `WIONodeModifier` applied to any existing node, NOT as a dedicated `WIOCheckpointNode`. This is more flexible — any node can be checkpointed without creating a separate node type.
- [x] 2.3 Add retry support as graph-level modifier (`RetryModifier` + `WIOGraph.withRetry`)
  - *Architectural note:* Implemented as a `WIONodeModifier`, NOT as a dedicated `WIORetryNode`. Same rationale as checkpoint.
- [x] 2.4 Add interruption support as graph-level modifier (`InterruptionModifier` + `WIOGraph.withInterruption`)
  - *Architectural note:* Implemented as a `WIONodeModifier`, NOT as a dedicated `WIOInterruptNode`. Same rationale as checkpoint.
- [x] 2.5 Add optional `otherwise` branch to WIOForkNode via `WIOForkNode.withOtherwise` factory method
- [x] 2.7 Update WIOGraph compilation to handle new node types and modifiers (forEach via `toWIO`, modifiers via `applyModifiers`)
- [x] 2.10 Write tests for modifier wrapping, modifier composition, graph+modifier execution, fork otherwise

### Out of Scope
- ~~2.6 Implement WIO-aligned GraphBuilder DSL with combinators (map/flatMap/andThen/transform/handleErrorWith)~~
  - *Rationale:* The current `WIOGraph` API + `WIONode` factories + `WIONodeModifier` system already covers all planned DSL operations without needing a separate DSL layer. See `graph-builder-dsl-equivalence.md` for a detailed mapping showing how each planned DSL operation maps to the existing API.
- ~~2.8 Update graph validation for composite nodes and fork/loop semantics~~
  - *Rationale:* Current validation (cycle detection, entry check, reachability) is sufficient. Composite nodes (loop, parallel, forEach) are self-contained and do not introduce additional DAG constraints that need validation.
- ~~2.9 Decide and implement ADK-specific nodes (ChatModel, Tools, StructuredModel, StructuredTool, Merge)~~
  - *Rationale:* These are higher-level abstractions that belong in a separate module/layer built on top of `WIORunIONode`, NOT as first-class `WIONode` variants in wiograph. This keeps wiograph focused on WIO parity.
