# GraphBuilder DSL → WIO constructor alignment plan

## Purpose
Create a `GraphBuilder` DSL that mirrors Workflows4s `WIO` constructors so users can express graph workflows using WIO-like methods, while still producing an ADK4S `Graph` and compiling to WIO via `WIOExecutor`.

## References
- WIO constructor APIs: @docs/dependencyDocs/workflows4s-core-api.md#93-397
- Graph and node definitions: @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala#20-231
- Graph validation constraints (entry/end, fan‑in, branch targets): @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#13-179
- Current node inventory: @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala#13-61
- WIO compilation/execution constraints: @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/WIOExecutor.scala#10-167

## High‑level design goals
1. **WIO‑shaped API**: Mirror WIO constructors (`pure`, `runIO`, `handleSignal`, `await`, `fork`, `loop`, `parallel`, `forEach`) with the same method names and fluent chaining.
2. **Graph‑native output**: The DSL yields a `Graph` (plus entry/end) with typed `NodeRef`s and validation constraints respected (no cycles, fan‑in only via `MergeNode`).
3. **Deterministic translation to WIO**: Every builder method maps to a fixed set of graph nodes that `WIOExecutor.toWIO` can compile.
4. **Pure, immutable builders**: Each builder step returns a new immutable builder state (no mutation), aligning with project rules.

## Constraint analysis (impacting DSL design)
1. **Fan‑in constraint**: Graph validation forbids fan‑in except into `MergeNode` [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#147-162]. This is stricter than WIO’s `parallel.collectResults`, which allows arbitrary aggregation. The DSL must force merge operations through explicit `MergeNode` adapters.
2. **Branch target validation**: Branch targets must match outgoing edges [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#164-179]. The DSL must add edges for all branch targets, not just return a `Branch`.
3. **No cycles**: Graph validation forbids cycles [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#80-117]. WIO’s `loop` cannot be expressed without either relaxing validation or introducing a dedicated `LoopNode` that encodes looping internally (without edges that form cycles).
4. **WIO compile semantics**: WIO compilation evaluates branch conditions synchronously via `unsafeRunSync` [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/WIOExecutor.scala#125-156]. This limits support for effectful branch conditions (e.g., randomness, IO). DSL documentation must warn about this, and builder methods should discourage side effects inside branching.
5. **Error channel mismatch**: WIO supports an explicit `Err` type, while ADK4S graph nodes only express errors via `IO.raiseError` with no typed error channel [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala#32-61]. Mapping `WIO.pure.error` and `handleEventWithError` needs either (a) a new graph error type parameter or (b) a conventional `Either` output with explicit error handling nodes.

## Proposed GraphBuilder structure
### Core types
- `GraphBuilder[I, O, S]` holds:
  - `graph: Graph[I, O, S]`
  - `lastNode: Option[Graph.NodeRef[?, ?]]`
  - `entry: Option[Graph.NodeRef[I, ?]]`
  - `ends: Set[Graph.NodeRef[?, ?]]`

### Builder shape
- `GraphBuilder` is a **pure immutable builder**: each method returns a new builder with an updated graph and node references.
- Each method adds node(s) and edges **immediately**, minimizing invalid intermediate states.
- The DSL exposes WIO‑named methods in the same order as the Workflows4s builders (`pure`, `runIO`, etc.) and a final `.done` that produces the completed `Graph` with entry/end set.

## Method‑by‑method plan (WIO constructors → graph nodes)

### 1) `WIO.pure` and `WIO.pure.makeFrom` (PureBuilder)
**Goal**: create pure nodes without side effects.

**GraphBuilder API**:
- `pure[Out](value: => Out): GraphBuilder[Any, Out, S]`
- `pure.makeFrom[In].value[Out](f: In => Out): GraphBuilder[In, Out, S]`
- `pure.makeFrom[In].apply[Err, Out](f: In => Either[Err, Out])` → **problem** (see below)
- `pure.error[Err]` → **problem** (see below)

**Implementation**:
- Translate to `GraphNode.LambdaNode` wrapping a pure function (`Lambda` + `IO.pure`), then append an edge from `lastNode` if present. Use `Graph.addLambdaNode` and `Graph.addEdge` [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala#33-81].
- Set entry if this is the first node.

**Problems**:
- `pure.error` and `pure.makeFrom[In].error` require a typed error channel. Graph has no `Err` type parameter. **Plan**: represent errors as `Either` outputs, or extend `Graph` with a new error type parameter (breaking change). If not extending, document that `pure.error` is unsupported and provide a builder method like `pureEither` returning `Either[Err, Out]`.

### 2) `WIO.runIO` (RunIOBuilder)
**Goal**: wrap side effects and handle events.

**GraphBuilder API**:
- `runIO[In, Evt](f: In => IO[Evt])` then `handleEvent` / `handleEventWithError`.

**Implementation**:
- Since `GraphNode` has no event model, map `runIO` to `LambdaNode` producing the output of `handleEvent` immediately:
  1. Add `LambdaNode` that does `f(input)` and immediately maps to `Out` via the `handleEvent` function.
  2. This ignores Workflows4s event‑sourcing semantics but preserves output behavior.
- This is aligned with `NodeExecutable.fromLambda` which uses `IO` only [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/NodeExecutable.scala#26-41].

**Problems**:
- Loss of event‑sourcing semantics (no persisted `Evt`). If full parity is required, introduce new node type `EventNode[In, Evt, Out]` that stores `Evt` and a `handleEvent` function, and update `WIOExecutor` to convert it to `WIO.runIO`.
- `handleEventWithError` again conflicts with missing error channel.

### 3) `WIO.handleSignal` (HandleSignalBuilder)
**Goal**: wait for external signals and transform state.

**GraphBuilder API**:
- `handleSignal(signalDef).using[Intermediate].purely/withSideEffects(...).handleEvent(...).produceResponse(...)`

**Implementation**:
- Requires a **new graph node type** because existing `GraphNode` has no signal support [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala#13-61].
- Add `SignalNode[In, Req, Resp, Evt, Out]` that stores signal metadata and event handlers.
- Extend `WIOExecutor` to translate `SignalNode` into the WIO `handleSignal` builder.

**Problems**:
- There is currently no orchestration layer for signals. This is a larger feature gap. The plan should explicitly flag that signal support is not possible without new runtime wiring beyond graph compilation.

### 4) `WIO.await` / `awaitUntil` / `awaitDynamic` (AwaitBuilder)
**Goal**: time‑based waits.

**Implementation**:
- Requires a new `AwaitNode` in the graph model.
- Extend `WIOExecutor` to emit `WIO.await` or `WIO.awaitDynamic` steps.

**Problems**:
- No timer integration exists in ADK4S graph runtime. This is a missing capability that needs runtime support (e.g., workflow engine with timers). Must be called out as a plan dependency.

### 5) `WIO.fork` (ForkBuilder)
**Goal**: conditional branching with multiple branches and `otherwise`.

**Implementation**:
- Use `Branch` and `addBranch` on the graph; create outgoing edges for each branch target [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala#79-90; @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#164-179].
- `fork.on` registers a condition → maps to `Branch` returning the `NodeKey` of the selected branch.
- `otherwise` can map to a default branch target; for parity with WIO, define an explicit “otherwise” node and include it in outgoing edges.

**Problems**:
- Branch conditions in WIO are pure and evaluate at runtime; `WIOExecutor` currently evaluates them synchronously during compilation [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/WIOExecutor.scala#125-156]. This is unsafe for effectful conditions. The DSL must either disallow effectful conditions or require changes to `WIOExecutor`.

### 6) `WIO.loop` (LoopBuilder)
**Goal**: repeat until condition.

**Implementation**:
- Current graph validation forbids cycles [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#80-117].
- To support loop, introduce a `LoopNode` that encapsulates:
  - body `Graph` or `Runnable`
  - stop condition
  - restart logic
- `LoopNode` executes internally without creating a cycle in graph edges.

**Problems**:
- Without adding `LoopNode` and supporting execution semantics, loops cannot be represented in the graph model. This is a required extension.

### 7) `WIO.parallel` (ParallelBuilder)
**Goal**: run sub‑workflows in parallel and aggregate results.

**Implementation**:
- Current graph has no explicit parallel node. `MergeNode` can combine two outputs but does not provide concurrency semantics [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala#59-60].
- Add a `ParallelNode` that stores a vector of sub‑graphs and a result aggregation function.
- `WIOExecutor` translates `ParallelNode` to `WIO.parallel` with `collectResults`.

**Problems**:
- Fan‑in constraint means you cannot join many branches unless you use `MergeNode` chains. This becomes unwieldy for `parallel.apply(Seq(...))`. A dedicated `ParallelNode` is required to preserve WIO semantics and avoid violating fan‑in rules [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#147-162].

### 8) `WIO.forEach` (ForEachBuilder)
**Goal**: map a workflow over a collection.

**Implementation**:
- Add a `ForEachNode` capturing:
  - selector `In => Set[Elem]`
  - element workflow `Graph[Elem, ElemOut]`
  - output builder `(In, Map[Elem, ElemOut]) => Out`
- `WIOExecutor` compiles this into `WIO.forEach`.

**Problems**:
- No existing graph support. This is new node type and runtime support.

## GraphNode‑by‑node mapping plan

### `LambdaNode`
- **Used by**: `pure`, `pure.makeFrom`, simplified `runIO + handleEvent` (when losing event semantics).
- **Implementation**: translate WIO pure / runIO to `Lambda` returning `IO` [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala#32-34].
- **Limitations**: cannot express typed `Err` channel; must return `Either` or throw.

### `ChatModelNode`
- **Used by**: dedicated DSL method `chatModel(model)`; not a WIO constructor, but part of graph‑specific extensions.
- **Mapping**: wrap conversation building in `LambdaNode`, then `ChatModelNode` consumes `Conversation` and outputs `Completion` [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala#35-36].
- **Problem**: none for WIO parity; it is ADK‑specific.

### `ToolsNode`
- **Used by**: dedicated DSL method `tools(toolsNode)`.
- **Mapping**: add a `ToolsNode` graph node directly [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala#38-40].
- **Problem**: no WIO equivalent; keep as ADK‑specific extension.

### `StructuredModelNode`
- **Used by**: DSL method `structuredModel(model, template)`.
- **Mapping**: directly to `StructuredModelNode` [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala#42-45].
- **Problem**: WIO parity not required; still ADK‑specific.

### `StructuredToolNode`
- **Used by**: DSL method `structuredTool(structuredToolCall, toolName)`.
- **Mapping**: directly to `StructuredToolNode` [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala#47-54].
- **Problem**: no WIO equivalent; keep as ADK‑specific extension.

### `SubGraphNode`
- **Used by**: DSL method `subGraph(graph)`.
- **Mapping**: adds a `SubGraphNode` and delegates to `WIOExecutor.execute` [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala#56-57].
- **Problem**: for WIO parity, ensure nested graph compilation respects entry/end and has no cycles. Can be used to model WIO `loop` or `forEach` bodies but only if a wrapper node is introduced.

### `MergeNode`
- **Used by**: internal DSL method for join/fan‑in; aligns with WIO `parallel.collectResults` when used as a binary merge.
- **Implementation**: generate merge nodes for `parallel` when only 2 branches; for >2, either chain merges or replace with `ParallelNode` to avoid fan‑in constraint [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#147-162].
- **Problem**: merge is binary; WIO parallel is N‑ary.

## Implementation plan (phased)

### Phase 1 — DSL foundation (no new node types)
1. Introduce `GraphBuilder` and builder state types.
2. Implement WIO‑named methods for `pure` and `runIO` mapped to `LambdaNode`.
3. Implement `fork` using `Branch` with explicit edge wiring.
4. Provide ADK‑specific methods for `chatModel`, `tools`, `structuredModel`, `structuredTool`.
5. Document unsupported methods (`await`, `loop`, `parallel`, `forEach`, `handleSignal`) in the DSL as `NotImplemented`.

### Phase 2 — Add missing node types
1. Add `SignalNode`, `AwaitNode`, `LoopNode`, `ParallelNode`, `ForEachNode` to `GraphNode`.
2. Extend `Graph.add...Node` methods for these nodes.
3. Update `WIOExecutor` to compile new nodes to their WIO constructors.
4. Update `GraphValidation` if needed (e.g., allow `LoopNode` without cycles).

### Phase 3 — Error channel alignment
1. Decide between: (a) add `Err` type parameter to `Graph` and `GraphNode`, or (b) standardize on `Either` outputs with helper nodes.
2. Implement `pure.error` and `handleEventWithError` semantics using chosen error model.
3. Update DSL docs and examples accordingly.

## Known gaps / explicit problems
- **Typed error channel**: WIO’s `Err` has no direct representation in `Graph`. This blocks `pure.error` and `handleEventWithError` without an extension.
- **Loop support**: Graph validation prohibits cycles; `loop` requires a dedicated node type or validation changes.
- **Parallel/forEach**: Without a `ParallelNode`/`ForEachNode`, `parallel` and `forEach` cannot be expressed without fan‑in violations or unwieldy merge chains.
- **Signal and timer runtime**: No runtime support exists in ADK4S graph execution for signals or timers; this is larger than the DSL layer.
- **Branch evaluation**: `WIOExecutor` evaluates branch conditions during compilation; effectful or non‑deterministic conditions will not behave correctly unless compilation is changed.

## Next steps for implementation work
1. Agree on error modeling strategy (typed error channel vs `Either`).
2. Decide if signal/timer support is in scope or if DSL should expose them as stubs.
3. Prototype `GraphBuilder` with `pure`, `runIO`, and `fork` to validate ergonomics and type flow.
4. Add tests mirroring WIO examples to ensure the DSL produces a valid graph and `WIOExecutor.toWIO` compiles successfully.
