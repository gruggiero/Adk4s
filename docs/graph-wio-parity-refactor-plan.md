# Full WIO parity refactor plan (Graph + Nodes + Runtime)

## Goal
Refactor ADK4S Graph and orchestration to achieve **full parity** with Workflows4s `WIO` semantics and type signatures, including:
- Explicit `State`, `Event`, and `Err` types.
- All WIO constructors (pure, runIO, handleSignal, await, fork, loop, parallel, forEach).
- WIO combinators (map/flatMap/andThen/transform, handleErrorWith, retry, interruption, checkpoint).
- Deterministic graph → WIO compilation that preserves event‑sourcing semantics.

## References
- WIO constructors + combinators: @docs/dependencyDocs/workflows4s-core-api.md#93-744
- Graph + node inventory: @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala#20-231
- Graph validation rules: @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#13-179
- WIO compilation: @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/WIOExecutor.scala#10-281

---

## 1) Target type model (parity baseline)

### 1.1 New Graph signature
**Current**: `Graph[I, O, S]` (no error type, `S` for internal state only) [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala#20-31]

**Target**:
```scala
Graph[I, Err, O, State, Event]
```
- `I` matches WIO `In`.
- `Err` matches WIO `Err` (explicit type).
- `O` matches WIO `Out` (subtype of `State`).
- `State` and `Event` match `WorkflowContext.State` and `WorkflowContext.Event`.

**Design notes**:
- Make `State` and `Event` part of the graph type, not just runtime config. This is essential to map WIO builders and preserve event‑sourcing semantics.
- Introduce `WorkflowContext` at the graph level (e.g., `Graph[Ctx <: WorkflowContext, I, Err, O]`) so nodes can emit events and consume state consistently.

### 1.2 NodeExecutable signature
**Current**: `NodeExecutable[I, O]` with `invoke: I => IO[O]` and `toWIO: WIO[Any, Nothing, Any, GraphWorkflowContext]` [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/NodeExecutable.scala#16-41]

**Target**:
```scala
NodeExecutable[Ctx, I, Err, O <: State]
```
- `invoke: I => IO[Either[Err, O]]`
- `toWIO: WIO[I, Err, O, Ctx]`

**Reason**: preserve WIO error channel and state type; allow direct WIO compilation without erasing types to `Any`.

---

## 2) Graph node model (new nodes required)

### 2.1 Existing nodes (adapted)
- `LambdaNode`: now returns `Either[Err, O]` or uses typed error in `NodeExecutable`.
- `ChatModelNode`, `ToolsNode`, `StructuredModelNode`, `StructuredToolNode`: must be parameterized by `Err`, `State`, `Event` and return `Either[Err, O]`.
- `MergeNode`: preserved but outputs `O` with explicit `Err` (merge errors when combining).
- `SubGraphNode`: now references `Graph` with same `Ctx` and error type.

### 2.2 New nodes for WIO parity
1. **RunIONode**
   - Captures `f: In => IO[Evt]` and `handleEvent: (In, Evt) => Out`.
   - Compiles to `WIO.runIO(...).handleEvent(...)`.

2. **SignalNode**
   - Contains `SignalDef[Req, Resp]`, event generation and response handler.
   - Compiles to `WIO.handleSignal(...).produceResponse(...)`.

3. **AwaitNode**
   - Supports `await`, `awaitUntil`, `awaitDynamic`.
   - Compiles to WIO `await` builders.

4. **ForkNode**
   - Represents WIO `fork.on.branch.otherwise` directly.
   - Avoids current branch validation limitations by modeling fork as a first‑class node.

5. **LoopNode**
   - Represents WIO `loop.apply(...).stopWhen(...).restart(...)` without graph cycles.

6. **ParallelNode**
   - Stores `Seq[Graph]` + `collectResults` function.
   - Compiles to `WIO.parallel.apply(...).collectResults(...)`.

7. **ForEachNode**
   - Stores collection selector, element workflow, and output builder.
   - Compiles to `WIO.forEach(...).buildOutput(...)`.

8. **CheckpointNode**
   - Encodes `checkpointed` combinator: `genEvent`, `handleEvent`.

9. **RetryNode**
   - Represents WIO `retry` / `retryIn` combinators.

10. **InterruptNode**
    - Encodes `interruptWith` semantics and interruption handlers.

---

## 3) Graph builder & DSL (WIO‑shaped API)

### 3.1 GraphBuilder type
```scala
GraphBuilder[Ctx, In, Err, Out <: State]
```
Tracks:
- `graph: Graph[Ctx, In, Err, Out]`
- `entry: Option[NodeRef]`
- `last: Option[NodeRef]`
- `ends: Set[NodeRef]`

### 3.2 Mapping WIO constructors
**Implement one method for each WIO builder** with identical method names and fluent chaining:
- `pure`, `pure.makeFrom`, `pure.error`, `pure.makeFrom.error`, `pure.makeFrom.apply`.
- `runIO(...).handleEvent(...).handleEventWithError(...).done`.
- `handleSignal(...).using(...).purely/withSideEffects(...).handleEvent(...).produceResponse(...).done`.
- `await`, `awaitUntil`, `awaitDynamic`.
- `fork.on(...).branch(...).otherwise(...).done`.
- `loop.apply(...).stopWhen(...).restart(...).done`.
- `parallel.apply(...).collectResults(...).done`.
- `forEach(...).apply(...).buildOutput(...).done`.

### 3.3 Mapping WIO combinators
Expose combinators on builders:
- `map`, `flatMap`, `andThen`, `>>>`.
- `transformInput`, `provideInput`, `transformOutput`, `transform`.
- `handleErrorWith`.
- `retry`, `retryIn`.
- `interruptWith`.
- `checkpointed`.

**Implementation strategy**:
- Each combinator is a node transformer that wraps prior node(s) into a `Node` + edge chain, or yields a composite node (`CheckpointNode`, `RetryNode`, `InterruptNode`).

---

## 4) WIOExecutor refactor (typed compilation)

### 4.1 Stop using `GraphWorkflowContext` with `Any`
Current compilation erases types to `Any` [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/WIOExecutor.scala#22-36].

**Target**:
- `WIOExecutor.toWIO[Ctx, I, Err, O]` returns `WIO[I, Err, O, Ctx]`.
- Each node’s `toWIO` returns correctly typed WIO.

### 4.2 Compile nodes directly to WIO
- Use pattern matching on each node type; compile to the exact WIO constructor.
- Avoid `unsafeRunSync` for branch evaluation; rely on runtime WIO branch semantics.

### 4.3 Decompose graph vs WIO flow
- For nodes representing composite WIO structures (`LoopNode`, `ParallelNode`, etc.), compilation should construct WIO subgraphs directly without introducing synthetic edges.

---

## 5) GraphValidation refactor

### 5.1 Relax or reinterpret constraints
- **Cycles**: forbidden today [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#80-117]. Loops must be allowed **only** if represented by `LoopNode`, not by edges.
- **Fan‑in**: currently only permitted for `MergeNode` [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#147-162]. With `ParallelNode`, fan‑in should be disallowed at graph level and handled internally by `ParallelNode`.
- **Branch targets**: keep validation for edge‑based branches, but allow `ForkNode` without explicit outgoing edges.

### 5.2 New validation rules
- Ensure each composite node has valid nested graphs (entry/end set, valid types, no cycles) and consistent `Ctx`.
- Ensure combinator nodes (`RetryNode`, `CheckpointNode`, `InterruptNode`) reference valid downstream nodes.

---

## 6) Runtime & event‑sourcing parity

### 6.1 Persisted events
- Introduce an event‑aware runtime adapter in orchestration to ensure `runIO` events are persisted and replayed (aligning to Workflows4s guarantees).

### 6.2 Signal & timer handling
- Add orchestration runtime hooks for signals and timers (backed by Workflows4s runtime or a compatible adapter).
- Ensure signal IDs and names map to `SignalDef` (from WIO docs) and are stable across runs.

### 6.3 Graph execution path
- Deprecate or remove `Graph.compile` stub logic and ensure execution routes through `WIOExecutor` (or through a new `WorkflowRuntime` abstraction).

---

## 7) Migration plan for existing code

### 7.1 API updates
- Update all `Graph` usages to include `Err`, `State`, `Event` type params.
- Update all `GraphNode` constructors to accept new type params and return `Either[Err, O]`.
- Update `WIOExecutor` usages to new typed signature.

### 7.2 Tests
- Update existing tests (graph validation, WIO executor tests) to include explicit error types.
- Add new tests for each WIO constructor mapping and combinator behavior.

### 7.3 Docs and examples
- Update `adk4s-examples` to use the new DSL and typed graph definitions.
- Add a “WIO parity” example that mirrors a workflows4s example verbatim.

---

## 8) Phased implementation plan

### Phase 0 — OpenSpec proposal (required)
Because this is a **new capability** + **architecture refactor**, create an OpenSpec change proposal before coding (see @openspec/AGENTS.md).

### Phase 1 — Type system foundation
1. Add `Err`, `State`, `Event` to `Graph` and `GraphNode` signatures.
2. Update `NodeExecutable` to return `Either[Err, O]` and typed WIO.
3. Update `Graph` add methods to preserve new type params.

### Phase 2 — Node parity
1. Implement new node types: RunIONode, SignalNode, AwaitNode, ForkNode, LoopNode, ParallelNode, ForEachNode, CheckpointNode, RetryNode, InterruptNode.
2. Add graph builder methods to create each node.

### Phase 3 — Typed WIO compilation
1. Refactor `WIOExecutor.toWIO` to compile nodes directly to typed WIO without `Any`.
2. Remove `unsafeRunSync` branching in compilation.

### Phase 4 — Validation + runtime
1. Update `GraphValidation` for composite nodes.
2. Introduce runtime adapter for signals/timers/events.

### Phase 5 — DSL and migration
1. Implement `GraphBuilder` DSL with WIO‑named methods.
2. Update examples, tests, and docs.

---

## Known risks & mitigation
- **Large breaking change**: Mitigate by introducing compatibility shims (`GraphLegacy`) during migration.
- **Runtime scope**: Signals/timers require runtime support beyond graph compilation. Treat as a separate workstream if needed.
- **Error type propagation**: Use `Either` for transition period, then enforce explicit `Err` as native type.

---

## Deliverables checklist
- [ ] OpenSpec proposal + tasks
- [ ] Refactored `Graph`, `GraphNode`, `NodeExecutable`
- [ ] New node types for full WIO parity
- [ ] Typed `WIOExecutor` compilation
- [ ] Updated validation rules
- [ ] Event/signal/timer runtime support
- [ ] WIO‑aligned GraphBuilder DSL
- [ ] Updated examples + tests
