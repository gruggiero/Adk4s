# Graph executor implementation options (Workflows4s)

## Context and references

This report targets the incomplete WIO executor implementation and the graph/chain/workflow builders that are meant to mirror Eino's orchestration model (see @docs/implementation-plan/08-graph-chain-workflow.md). The current implementation has a stubbed `WIOExecutor.toWIO` and placeholder graph execution logic (see @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/WIOExecutor.scala#1-27 and @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala#71-103). Graph validation for entry/end nodes and DAG integrity is already implemented (see @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#12-144), and graph nodes are typed as an ADT including `ToolsNode` (see @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala#1-15).

Eino's reference behavior emphasizes:
- Graphs with explicit entry/end nodes and type-safe edges.
- Runtime branching that selects next nodes based on outputs.
- ToolsNode as a first-class node for ReAct-style flows.
- Workflows4s-like composition as an alternative execution engine.

(References: @docs/eino-analysis/02-core-features.md, @docs/eino-analysis/04-architectural-patterns.md, @docs/eino-analysis/07-key-types.md, @docs/eino-analysis/09-usage-examples.md.)

## Current ADK4S state

1. **Graph building**
   - Graph is immutable and returns `ValidatedNec` for each mutation (see @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala#29-151).
   - `NodeRef` keeps compile-time edge compatibility by typing `addEdge` with `NodeRef[A, B]` -> `NodeRef[B, C]` (see @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala#49-59).

2. **WIO executor stub**
   - `WIOExecutor.toWIO` only sets up a `WIO.build` that returns the input state unchanged (see @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/WIOExecutor.scala#8-20).

3. **Tooling**
   - `ToolsNode` is already a full executor for llm4s and ADK tools (see @adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala#1-142).
   - Typed tool support exists via `StructuredToolCall` and `StructuredToolFunction` (see @adk4s-core/src/main/scala/org/adk4s/core/tools/StructuredToolCall.scala#1-180 and @adk4s-core/src/main/scala/org/adk4s/core/tools/StructuredToolFunction.scala#1-151; design notes in @docs/structured-toolcall-design.md).

4. **Workflow builder stub**
   - Workflow compile is a placeholder that returns the input (see @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/workflow/Workflow.scala#19-23).

## Requirements from OpenSpec deltas

- Graph MUST enforce explicit entry and end nodes before compile and WIO translation (see @openspec/changes/update-graph-explicit-entry-end/specs/orchestration-builders/spec.md#3-81).
- WIO conversion must start at the entry node and terminate at configured end nodes.
- Edge compatibility must remain type-safe (compile-time, via `NodeRef`).

## Implementation options for a real WIO-based graph executor

### Option A: Static WIO DAG compiler (preferred for DAG-only graphs)

**Summary**: Compile the graph into a static `WIO` by topologically ordering nodes from the entry node and composing WIO steps for each node.

**Core idea**
- Each `GraphNode[I, O]` becomes a `WIO[I, Nothing, O, Ctx]`.
- Edges are encoded as `flatMap` / `>>>` composition.
- Branches are encoded using `WIOBranch.fork` or `WIO.Fork`.
- Subgraphs are embedded by reusing `WIOExecutor.toWIO` for the nested graph.

**Where it helps**
- Full static typing through `NodeRef` and `GraphNode` types.
- No runtime type casts in the executor.
- WIO remains the canonical executable representation.

**Main challenges**
- **Fan-in semantics**: when a node has multiple predecessors, a decision is needed:
  - Option A1: last-writer-wins (use the latest predecessor output as input).
  - Option A2: join via `WIO.parallel` and combine via a lambda that merges results.
  - Option A3: enforce single-predecessor per node and validate it.
- **Branching outputs**: branch must return a WIO that represents all outgoing edges. `Branch[I]` provides a `NodeKey` selection; map each target to its WIO and use `WIO.Fork` or `WIOBranch.branch`.

**Implications**
- Graph stays DAG only (matches current validation).
- `GraphConfig.maxRunSteps` is a safety valve but should not be primary control.

### Option B: Dynamic WIO loop interpreter (closer to Eino runtime engine)

**Summary**: Build a `WIO` that interprets the graph at runtime using a loop over a graph execution state.

**Core idea**
- Use `WIO.loop` to repeatedly execute the node indicated by a runtime `GraphRuntimeState`.
- The state carries `currentNode: NodeKey` and `outputs: Map[NodeKey, EncodedValue]`.
- `Branch` selects next `NodeKey`; end nodes terminate the loop.

**Where it helps**
- Supports dynamic traversal and more complex branching logic.
- Easier to support cycles if they become allowed later.

**Main challenges**
- Requires heterogenous output storage. To avoid `Any`, store a serialized value (`ujson.Value`) plus a `ToolSchema` or `Schema` for decoding.
- Additional encode/decode overhead for each edge.

**Implications**
- More flexible but less type-safe at compile time.
- Works well if you align nodes with structured serialization (see next section).

### Option C: WIO-first graph nodes (Graph as a typed DSL for WIO)

**Summary**: Make `GraphNode` itself the builder for `WIO`, so each node knows how to produce a WIO step. The graph is then just wiring and validation.

**Core idea**
- Introduce a `GraphNode.WioNode[I, O]` that stores `WIO[I, Nothing, O, Ctx]`.
- Provide constructors for Lambda, ChatModel, ToolsNode, Structured nodes, and SubGraph that each expose a `toWIO` method.

**Where it helps**
- Keeps WIO composition as the primary mechanism.
- Avoids duplicating execution logic between Graph and WIO executor.

**Main challenges**
- Requires a context-bound `Ctx` type or a higher-kinded `GraphNode` that can produce WIO for any context.

## Do more structured GraphNode types help?

Yes, more structured node types would simplify the executor and reduce unsafe conversions. Suggested evolution:

1. **Node capability split**
   - Introduce a `NodeExecutable[I, O]` algebra with explicit methods:
     - `invoke: I => IO[O]`
     - `toWIO[Ctx]: WIO[I, Nothing, O, Ctx]`
   - Each `GraphNode` variant wraps a `NodeExecutable`.

2. **Streaming-aware node types**
   - Eino distinguishes invokable vs stream/collect/transform; ADK4S can adopt a similar structure using `Runnable` as the underlying interface.

3. **WIO-friendly node metadata**
   - Add metadata like `nodeName`, optional `preHandler`, and `postHandler` to align with Eino patterns.

This keeps Graph execution pure and eliminates the current `asInstanceOf` placeholders in Graph execution (see @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala#98-103).

## Should we use StructuredLLM / StructuredToolCall to restrict graph nodes?

**Short answer**: yes, but as *optional* node types rather than a hard restriction.

### StructuredLLM integration
- Introduce `GraphNode.StructuredModelNode[I, O]` that wraps `StructuredLLM[IO]` with an explicit `Schema[O]` and `PromptTemplate[I]`.
- This yields a strongly typed output at the graph level and removes the need for field-mapping workflows in some flows.
- It also makes Option B (runtime interpreter) viable since outputs are serializable via schema.

### StructuredToolCall integration
- Introduce `GraphNode.StructuredToolNode[I, O]` built from `StructuredToolCall[IO]` and `ToolSchema` instances, returning typed results.
- This avoids `List[ToolCall]` / `List[ToolMessage]` plumbing when the graph is about business data rather than conversation protocol.

### Compatibility with current tooling
- `ToolsNode` already supports `StructuredToolFunction` via `ToolsNodeConfig.builder.withStructuredTool` (see @adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNodeConfig.scala#72-99), so an immediate upgrade path exists without removing `ToolsNode`.

## Do we still need ToolsNode?

**Recommendation: keep it.**

Reasons:
1. **Eino parity**: Eino's graph orchestration relies on `ToolsNode` for ReAct loops (see @docs/eino-analysis/09-usage-examples.md#442-648).
2. **Separation of concerns**: `ToolsNode` isolates tool execution and middleware; many workflows will still operate directly on `ToolCall`/`ToolMessage` flows (see @adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala#1-142).
3. **Interoperability**: `ToolsNodeRunnable` bridges `ToolsNode` to `Runnable` for graph integration (see @adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNodeRunnable.scala#8-30).

When ToolsNode might be bypassed:
- If an orchestration flow is fully typed and uses `StructuredToolCall` directly, a dedicated structured tool node could replace a ToolsNode step.
- For ReAct-style agents, LLM4S `Agent` can fully manage tool loops; in that case, you may embed the agent as a node instead of manually wiring `ChatModel + ToolsNode + Branch`.

## Recommended path forward

1. **Choose Option A (static WIO DAG compiler)** for a first complete implementation. It aligns with the current DAG validation and minimizes runtime type tricks.
2. **Add structured node variants**:
   - `StructuredModelNode` using `StructuredLLM`.
   - `StructuredToolNode` using `StructuredToolCall`.
3. **Clarify fan-in semantics**:
   - Enforce single-predecessor per node *or* require explicit merge nodes for fan-in.
4. **Update `WIOExecutor.execute`** to compile WIO and run it via a Workflows4s runtime (InMemory or configured runtime), so the graph execution aligns with the spec requirement that WIO is the executor.

## Open questions

1. Should graphs allow cycles in the future (Eino supports them), or keep DAG-only semantics?
2. Do we want to make `Workflow` a thin typed wrapper over WIO composition instead of field-mapping (string-based) mappings?
3. Should `GraphConfig` grow a `nodeTriggerMode` similar to Eino to clarify fan-in behavior?
