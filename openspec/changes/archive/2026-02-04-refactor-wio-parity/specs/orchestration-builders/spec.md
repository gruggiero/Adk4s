## Target Module
`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/`

## IMPLEMENTED Requirements

### Requirement: WIOGraph Builder for DAG-based Workflows ✅
The system provides a context-aware WIOGraph builder with explicit error and event types, aligned to Workflows4s WIO semantics. `WIOGraph` is parameterized as `WIOGraph[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]]` and nodes may emit `Ctx#Event`. The builder supports pure (lambda), runIO, signal, await, fork, loop, parallel, sub-graph, and forEach nodes. Edges preserve type compatibility between node outputs and inputs; entry nodes accept `In`; end nodes output `Out`.

#### Scenario: Create empty graph with context ✅
- **WHEN** `WIOGraph[Ctx, In, Out]` or `WIOGraph.withError[Ctx, In, Err, Out]` is called
- **THEN** a graph instance is returned with empty nodes, edges, and branches
- **AND** the entry node is unset and end nodes are empty

#### Scenario: Add runIO node and connect edge ✅
- **GIVEN** a WIOGraph and a `WIORunIONode` returning `Evt` with a `handleEvent` producing `Out`
- **WHEN** the node is added via `addNode` and an edge is created via `addEdge` from an upstream `WIONodeRef` producing the required input
- **THEN** the edge is accepted and preserves typed input/output compatibility

#### Scenario: Compile graph to typed WIO ✅
- **GIVEN** a valid WIOGraph with entry and end nodes
- **WHEN** `WIOGraph.toWIO` is called
- **THEN** a `WIO[In, Err, Out, Ctx]` is produced (wrapped in `Either[NonEmptyChain[WIOGraphError], ...]`)

### Requirement: Workflows4s WIO Execution Integration ✅
The system provides `WIOGraph.toWIO` that compiles a typed WIOGraph to a typed WIO without erasing types or evaluating branch conditions at compile time.

#### Scenario: Compile typed WIO ✅
- **GIVEN** a valid `WIOGraph[Ctx, In, Err, Out]`
- **WHEN** `WIOGraph.toWIO` is called
- **THEN** a `WIO[In, Err, Out, Ctx]` is returned
- **AND** it preserves the WIO error channel and context state type

#### Scenario: Fork compilation uses WIO fork semantics ✅
- **GIVEN** a graph containing a `WIOForkNode`
- **WHEN** the graph is compiled to WIO
- **THEN** the resulting WIO uses `WIO.Fork` with runtime condition evaluation

### Requirement: WIONode Types ✅
The system provides a `WIONode` sealed trait with variants for `WIOPureNode`, `WIORunIONode`, `WIOForkNode`, `WIOLoopNode`, `WIOAwaitNode`, `WIOHandleSignalNode`, `WIOParallelNode`, `WIOSubGraphNode`, and `WIOForEachNode`. Each variant implements `toWIO(using ErrorMeta[Err]): WIO[I, Err, O, Ctx]`.

#### Scenario: Create runIO node ✅
- **GIVEN** a `runIO: I => IO[Evt]` and `handleEvent: (I, Evt) => Either[Err, O]`
- **WHEN** `WIORunIONode(runIO, handleEvent)` is created
- **THEN** the node is typed as `WIONode[Ctx, I, Err, O]`

#### Scenario: Create loop node ✅
- **GIVEN** a loop body WIO and stop condition
- **WHEN** `WIOLoopNode(body, stopWhen, restart)` is created
- **THEN** the loop is represented without creating cyclic edges in the graph

#### Scenario: Create forEach node ✅
- **GIVEN** a collection extractor and a WIO body for processing each element
- **WHEN** `WIOForEachNode(...)` is created via `WIONode.forEach`
- **THEN** the node compiles to `WIO.ForEach` via `toWIO`

### Requirement: WIONodeModifier System ✅
The system provides a `WIONodeModifier` sealed trait with `CheckpointModifier`, `RetryModifier`, and `InterruptionModifier`. Modifiers are graph-level wrappers applied to any existing node's WIO during compilation. They are stored in `NodeEntry.modifiers` and applied left-to-right via `applyModifiers`.

#### Scenario: Apply checkpoint modifier ✅
- **GIVEN** a WIOGraph with a node and a checkpoint modifier applied via `WIOGraph.withCheckpoint`
- **WHEN** the graph is compiled via `toWIO`
- **THEN** the node's WIO is wrapped in `WIO.Checkpoint` with the provided event generator and handler

#### Scenario: Apply retry modifier ✅
- **GIVEN** a WIOGraph with a node and a retry modifier applied via `WIOGraph.withRetry`
- **WHEN** the graph is compiled via `toWIO`
- **THEN** the node's WIO is wrapped in `WIO.Retry` with the provided error handler

#### Scenario: Apply interruption modifier ✅
- **GIVEN** a WIOGraph with a node and an interruption modifier applied via `WIOGraph.withInterruption`
- **WHEN** the graph is compiled via `toWIO`
- **THEN** the node's WIO is wrapped in `WIO.HandleInterruption` with the provided interruption handler

#### Scenario: Compose multiple modifiers ✅
- **GIVEN** a node with both checkpoint and retry modifiers
- **WHEN** the graph is compiled
- **THEN** modifiers are applied left-to-right (e.g., checkpoint first, then retry wraps the result)

### Requirement: Fork Otherwise Branch ✅
`WIOForkNode` supports an optional otherwise branch via `WIOForkNode.withOtherwise`. The otherwise branch is appended as a catch-all branch with `predicate = _ => Some(_)`. When no earlier case predicate matches, the otherwise branch is selected.

#### Scenario: Add fork node with otherwise branch ✅
- **GIVEN** a `WIOForkNode` created via `WIOForkNode.withOtherwise` with two cases and an otherwise branch
- **WHEN** no case predicate matches
- **THEN** the otherwise branch is executed

#### Scenario: Fork with otherwise and edges ✅
- **GIVEN** a graph with a fork node (with otherwise) connected to downstream nodes
- **WHEN** the graph is compiled and executed
- **THEN** matching branches chain with downstream nodes, and the otherwise branch uses its workflow directly

## OUT OF SCOPE

### ~~Requirement: WIO-Aligned GraphBuilder DSL~~
A separate GraphBuilder DSL with constructors (pure, runIO, handleSignal, await, fork, loop, parallel, forEach, checkpoint, retry, interrupt) and combinators (map/flatMap/andThen/transform, handleErrorWith) is NOT needed.

**Rationale:** The current `WIOGraph` API + `WIONode` factories + `WIONodeModifier` system already covers all planned DSL operations without needing a separate abstraction layer. See `graph-builder-dsl-equivalence.md` for a detailed mapping of each planned DSL operation to its existing API equivalent.

### ~~Requirement: ADK-Specific Node Types~~
ChatModelNode, ToolsNode, StructuredModelNode, StructuredToolNode, and MergeNode are NOT first-class `WIONode` variants in wiograph.

**Rationale:** These are higher-level abstractions that should be built on top of `WIORunIONode` in a separate module/layer. This keeps wiograph focused on WIO parity and avoids coupling domain-specific concepts into the graph foundation.
