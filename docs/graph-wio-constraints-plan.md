# Plan: Enforcing graph constraints for WIO compilation (Option A)

## Goal
Guarantee at *graph construction time* that every edge and node in `Graph[I, O, S]` is representable as a `WIO` step and that the entire graph can be compiled into a single `WIO[I, Nothing, O, Ctx]` without casts or runtime type checks.

## Current gaps (from implementation)
- `Graph` stores `Map[NodeKey, GraphNode[?, ?]]` and `Map[NodeKey, Set[NodeKey]]`, so the type relationship between connected nodes is not preserved beyond the `NodeRef` API at build time.
- `addEdgeInternal` only checks node existence and fan-in; it cannot validate that a target node’s input type equals a source node’s output type once the `NodeRef` is dropped.
- `GraphNode` does not expose a guaranteed WIO builder; `WIOExecutor.toWIO` cannot rely on all nodes being WIO-capable.
- Branch nodes are stored as `Map[NodeKey, Branch[?]]` without tying the branch input type to the node output type.

## Constraints needed for WIO compilation
### 1. Nodes must be WIO-capable
Introduce a `NodeExecutable[I, O]` algebra and require all `GraphNode` variants to wrap it:
```scala
trait NodeExecutable[I, O]:
  def invoke(input: I): IO[O]
  def toWIO[Ctx]: WIO[I, Nothing, O, Ctx]
```
Every `GraphNode[I, O]` must contain a `NodeExecutable[I, O]`. This removes non-WIO-capable nodes (or forces them to implement `toWIO`).

### 2. Edge typing must be retained inside the graph
Replace the `Map[NodeKey, Set[NodeKey]]` with typed edges that preserve input/output types:
```scala
final case class Edge[A, B, C](
  from: Graph.NodeRef[A, B],
  to: Graph.NodeRef[B, C]
)
```
Store edges as `Vector[Edge[?, ?, ?]]` (or `NonEmptyChain` once validated) so type equality is enforced by construction.

### 3. Branch typing must be retained
Replace `Map[NodeKey, Branch[?]]` with typed bindings:
```scala
final case class BranchBinding[A, B](
  from: Graph.NodeRef[A, B],
  branch: Branch[B]
)
```
Store as `Vector[BranchBinding[?, ?]]` and only create via the typed API.

### 4. Entry/end nodes must be typed as part of the graph state
Carry entry/end as typed refs (not `NodeKey`):
```scala
final case class EntryRef[I, A](ref: Graph.NodeRef[I, A])
final case class EndRef[A, B <: O](ref: Graph.NodeRef[A, B])
```
This ensures:
- Entry node input type is exactly `I`.
- End node output type is a subtype of `O`.

### 5. Fan-in must be explicit and typed
To make Option A deterministic and WIO-friendly, enforce **single predecessor per node** or require an explicit merge node:
- Keep the current fan-in error (`FanInError`) and document it as a *hard constraint* for WIO compilation.
- If merging is required, add an explicit `MergeNode[I1, I2, O]` and connect via a `WIO.parallel` builder at the graph DSL level, not in the executor.

#### MergeNode proposal
Introduce a dedicated graph node for fan-in that makes merge semantics explicit and compiler-friendly:
```scala
final case class MergeNode[A, B, C](combine: (A, B) => C) extends GraphNode[(A, B), C]
```
Semantics:
- **Inputs**: two predecessors producing `A` and `B`.
- **Execution**: build `WIO.parallel` for the two upstream nodes, producing `(A, B)`, then `map` with `combine`.
- **Typing**: keeps fan-in explicit at the node level (no implicit tuple construction).

Validation rule changes:
- Allow multiple incoming edges **only if** the target node is a `MergeNode`.
- For any other node, keep `FanInError` as a hard constraint.

Compilation impact:
- `WIOExecutor.toWIO` can detect `MergeNode` and emit `WIO.parallel` + `map` with `combine`.
- The compiler remains static because all branch paths and merge points are explicit in the graph structure.

## Proposed API changes (builder-level)
### 1. Replace internal storage with typed structures
- `nodes: Map[NodeKey, GraphNode[?, ?]]` can stay, but edges/branches/entry/end must keep their typed `NodeRef` associations.
- Update `Graph` constructor to:
```scala
case class Graph[I, O, S] private (
  private val nodes: Map[NodeKey, GraphNode[?, ?]],
  private val edges: Vector[Graph.Edge[?, ?, ?]],
  private val branches: Vector[Graph.BranchBinding[?, ?]],
  private val entry: Option[Graph.EntryRef[I, ?]],
  private val ends: Vector[Graph.EndRef[?, O]],
  private val stateGen: Option[IO[StateRef[IO, S]]],
  private val compiled: Boolean = false
)
```
Each mutation produces a new `Graph` with a typed edge/branch.

### 2. Keep typed `NodeRef` as the only edge/branch entry point
- `addEdge` already takes `NodeRef[A, B]` to `NodeRef[B, C]`; update it to store a typed `Edge[A, B, C]`.
- `addBranch` to store a `BranchBinding[A, B]`.

### 3. Promote a `GraphNode` capability split
- Replace `sealed trait GraphNode[I, O]` with:
```scala
sealed trait GraphNode[I, O]:
  def executable: NodeExecutable[I, O]
```
- Provide concrete node constructors (`LambdaNode`, `ChatModelNode`, `StructuredModelNode`, `StructuredToolNode`, `SubGraphNode`) that each implement `NodeExecutable`.
- If a node cannot provide `toWIO`, remove it from the graph DSL and keep it as a workflow-only concept.

### 4. Enforce branch compatibility with outgoing edges
Add validation that every branch target key exists as a node and that each branch path corresponds to an outgoing edge from the branching node (typed via `NodeRef`).

## WIO compile guardrails (Option A)
### 1. Validate entry/end and branch/edge completeness
Add a compile-time validation layer (pure function) that checks:
- Entry node is defined.
- At least one end node is defined.
- Every edge and branch refers to an existing node.
- No dangling nodes that are unreachable from entry.

### 2. Define a typed topological ordering
During compile, walk the graph from the typed entry ref, using the typed edges to build a WIO chain. Since edges carry type information, each `flatMap`/`>>>` is fully typed.

### 3. Subgraph nodes as WIO
`SubGraphNode[I, O, S]` must expose a `toWIO` by delegating to `WIOExecutor.toWIO` once it is implemented. This is only safe if the subgraph itself already satisfies the constraints above.

## Migration plan (minimal churn)
1. **Introduce `NodeExecutable[I, O]`** and update `GraphNode` variants to wrap it.
2. **Add typed edge/branch storage** while leaving `nodes` map unchanged.
3. **Refactor validation** to use typed edges/branches instead of `NodeKey` pairs.
4. **Update `WIOExecutor.toWIO`** to consume typed edges and call `GraphNode.executable.toWIO`.
5. **Update builders/tests** to construct graphs via `NodeRef` only (no new public APIs required).

## Risks & decisions
- **Risk**: Typed edges stored as `Vector[Edge[?, ?, ?]]` still have existential types at storage time. **Mitigation**: all construction happens through `NodeRef` APIs, so edges are type-safe by construction and cannot be created otherwise.
- **Decision**: Keep fan-in forbidden for the initial WIO compiler (consistent with current `FanInError`) and require explicit merge nodes in the DSL.
- **Decision**: Keep `ToolsNode` and `ChatModelNode` in the graph DSL because they already map cleanly to `WIO` steps.

## Open questions to resolve before coding
1. Should we allow “merge nodes” explicitly (e.g., `MergeNode[A, B, C]`) or rely on user-provided `LambdaNode[(A, B), C]`? If merge nodes are added, they must also be `NodeExecutable`.
2. Do we want an explicit `GraphConstraint` or `GraphValidation` rule that enforces “branch targets must match outgoing edges”, or do we allow branch-only edges?
   - **Option A (enforce match)**: every `Branch.endNodes` element must appear as a declared outgoing edge for the branching node. This keeps the edge set as the single source of truth for topology, lets the WIO compiler enumerate all branch paths statically, and simplifies reachability/DAG validation.
   - **Option B (allow branch-only edges)**: a branch can return any existing `NodeKey` even without a declared edge. This is more flexible but breaks static WIO compilation unless the compiler treats branch targets as implicit edges or falls back to a runtime interpreter. It also weakens validation because topology is no longer fully described by edges.
   - **Recommendation for Option A WIO compiler**: enforce Option A via validation (branch targets ⊆ outgoing edges), so the static compiler can build `WIOBranch`/`WIO.Fork` without hidden transitions.
3. Should `Graph` expose the typed edge/branch collections publicly (read-only) to support diagnostics?
