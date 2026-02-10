# ADR-002: Graph Library Evaluation - scala-graph vs Custom Implementation

## Status

**Proposed**

## Date

2026-02-02

## Context

ADK4S implements workflow orchestration through two graph implementations:

1. **Generic Graph** (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/`) - Type-safe workflow graph for composition of diverse node types
2. **WIOGraph** (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/`) - Domain-specific graph that compiles directly to WIO (workflows4s primitives)

The question arose whether to adopt [scala-graph](https://github.com/scala-graph/scala-graph), a mature general-purpose graph library (~6,500 lines of code), to replace or augment the current implementations (~1,100 lines combined).

### Current Architecture

```
Graph[In, Out] / WIOGraph[Ctx, In, Err, Out]
       ↓
   NodeRef[I, O] / WIONodeRef[Ctx, I, O]  (phantom typed)
       ↓
   Map[NodeKey, GraphNode[?, ?]]  (node storage)
   Map[NodeKey, Set[NodeKey]]     (edge storage)
       ↓
   WIO[In, Err, Out, Ctx]  (compilation target)
```

### scala-graph Architecture

```
scalax.collection.Graph[N, E <: Edge[N]]
       ↓
   Inner/Outer Abstraction
       ↓
   NodeT (path-dependent) ←→ OuterNode[N]
   EdgeT (path-dependent) ←→ OuterEdge[N, E]
       ↓
   AdjacencyListGraph (representation)
```

### Requirements

ADK4S workflow graphs require:

| Requirement | Priority | Notes |
|-------------|----------|-------|
| Type-safe I/O edge matching | Critical | `NodeRef[A, B]` → `NodeRef[B, C]` enforced at compile-time |
| workflows4s integration | Critical | Direct compilation to `WIO` primitives |
| Context binding | Critical | `Ctx <: WorkflowContext` consistency across nodes |
| Error type propagation | High | Explicit `Err` type parameter on all nodes |
| DAG validation | High | Cycle detection, connectivity checks |
| State type constraints | High | `Out <: WCState[Ctx]` enforcement |
| Visualization/export | Medium | DOT format, debugging aids |
| Advanced algorithms | Low | Shortest path, spanning trees, etc. |

## Decision

**Keep the custom adk4s graph implementations (Graph and WIOGraph) as the primary execution path. Optionally integrate scala-graph for advanced analysis and visualization when needed.**

### Rationale

The fundamental difference between the two approaches is their purpose:

- **scala-graph**: General-purpose graph data structure with rich algorithms
- **adk4s WIOGraph**: Domain-specific workflow graph with type-safe compilation to WIO

scala-graph cannot provide the critical workflow-specific features that adk4s requires.

### Architecture (Recommended)

```
┌─────────────────────────────────────────────────────────────────┐
│                    Workflow Definition Layer                     │
│  WIOGraph[Ctx, In, Err, Out] ← Primary API                      │
│  - Phantom-typed NodeRef[I, O]                                  │
│  - Type-safe edge matching                                       │
│  - Context binding (Ctx <: WorkflowContext)                     │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Compilation Layer                             │
│  WIOGraph.toWIO → WIO[In, Err, Out, Ctx]                        │
│  - Validates cycles, connectivity, reachability                  │
│  - Recursive compilation to WIO.AndThen, WIO.Fork, etc.         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    workflows4s Execution                         │
│  ActiveWorkflow(wio) → Event Sourced Execution                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                 Optional Analysis Layer                          │
│  scala-graph (when advanced algorithms needed)                   │
│  - Conversion: WIOGraph → scalax.collection.Graph                │
│  - Use for: visualization, topological sort, path finding        │
└─────────────────────────────────────────────────────────────────┘
```

### Integration Pattern (Optional)

When advanced analysis is needed, convert to scala-graph:

```scala
import scalax.collection.immutable.Graph as ScalaGraph
import scalax.collection.edges.DiEdge

object WIOGraphAnalysis:

  /** Convert WIOGraph to scala-graph for analysis operations */
  def toScalaGraph[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
    wioGraph: WIOGraph[Ctx, In, Err, Out]
  ): ScalaGraph[NodeKey, DiEdge[NodeKey]] =
    val nodes: Iterable[NodeKey] = wioGraph.nodes.map(_.ref.key)
    val edges: Iterable[DiEdge[NodeKey]] = wioGraph.edges.map { e =>
      DiEdge(e.from.key, e.to.key)
    }
    ScalaGraph.from(nodes, edges)

  /** Get topological ordering for execution planning */
  def topologicalOrder[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
    wioGraph: WIOGraph[Ctx, In, Err, Out]
  ): Either[String, List[NodeKey]] =
    val sg = toScalaGraph(wioGraph)
    sg.topologicalSort match
      case Right(order) => Right(order.toList.map(_.outer))
      case Left(cycleNode) => Left(s"Cycle detected at ${cycleNode.outer}")

  /** Export to DOT format for visualization */
  def toDot[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
    wioGraph: WIOGraph[Ctx, In, Err, Out],
    graphName: String = "workflow"
  ): String =
    // Use scala-graph's DOT module or implement simple DOT export
    val sg = toScalaGraph(wioGraph)
    // ... DOT generation
```

## Alternatives Considered

### Alternative A: Replace Graph/WIOGraph with scala-graph

**Replace the base `Graph` and `WIOGraph` classes entirely with scala-graph.**

This would require:
1. Redefining `GraphNode`/`WIONode` as custom node types compatible with scala-graph
2. Abandoning phantom-typed `NodeRef[I, O]` - scala-graph doesn't support this pattern
3. Reimplementing type-safe edge validation externally
4. Maintaining WIO compilation logic separately

**Rejected because:**

1. **Loss of compile-time type safety** - scala-graph's path-dependent types ensure graph cohesion (no mixing nodes from different graphs), but they cannot enforce input/output type matching at edges:

   ```scala
   // adk4s: Compile-time enforcement
   val ref1: NodeRef[Int, String] = ...
   val ref2: NodeRef[String, Boolean] = ...
   val ref3: NodeRef[Double, Boolean] = ...

   graph.addEdge(ref1, ref2)  // Compiles: String matches String
   graph.addEdge(ref1, ref3)  // ERROR: String != Double

   // scala-graph: No equivalent mechanism
   val g = Graph(1 ~> 2, 2 ~> 3)  // Types are uniform
   ```

2. **No workflow semantics** - scala-graph doesn't understand:
   - `Ctx <: WorkflowContext` bindings
   - `WCState[Ctx]` output constraints
   - `Err` error type propagation
   - `ErrorMeta[Err]` requirements

3. **Cannot compile to WIO** - The entire purpose of WIOGraph is to produce `WIO[In, Err, Out, Ctx]`. scala-graph has no concept of this compilation.

4. **Significant refactoring cost** - Would require rewriting ~1,100 lines of domain-specific code with no clear benefit for the primary use case.

### Alternative B: Use scala-graph internally for validation

**Use scala-graph as an internal implementation detail within `GraphValidation`.**

```scala
// In GraphValidation.scala
import scalax.collection.immutable.Graph as ScalaGraph
import scalax.collection.edges.DiEdge

private def toInternalGraph[In, Out](graph: Graph[In, Out]):
    ScalaGraph[NodeKey, DiEdge[NodeKey]] =
  val nodes = graph.nodes.keys
  val edges = graph.edges.flatMap { case (from, tos) =>
    tos.map(to => DiEdge(from, to))
  }
  ScalaGraph.from(nodes, edges)

def validateGraph[In, Out](graph: Graph[In, Out]): ValidatedNec[AdkError, Unit] =
  val sg = toInternalGraph(graph)

  // Use scala-graph algorithms
  val cycleCheck = if sg.isCyclic then
    sg.findCycle.map(c => EdgeValidationError(c.nodes.map(_.outer).toList))
      .toInvalidNec(())
  else Valid(())

  // ... other validations using scala-graph
```

**Partially accepted** - This is a viable approach if validation becomes a bottleneck or requires more sophisticated algorithms. However, the current custom implementations are:
- Simple and well-tested (~167 lines for GraphValidation)
- Domain-specific (e.g., fan-in validation for MergeNode)
- Sufficient for current requirements

**Recommendation:** Defer this until validation complexity warrants the additional dependency.

### Alternative C: Dual representation with automatic sync

**Maintain both representations with automatic synchronization.**

```scala
class HybridGraph[In, Out](
  wioGraph: WIOGraph[...],
  analysisGraph: scalax.collection.Graph[NodeKey, DiEdge[NodeKey]]
):
  def addNode(...): HybridGraph[In, Out] =
    HybridGraph(
      wioGraph.addNode(...),
      analysisGraph.incl(...)
    )
```

**Rejected because:**

1. **Synchronization complexity** - Must keep two data structures in sync
2. **Memory overhead** - Double storage for the same graph
3. **Maintenance burden** - Every operation must be implemented twice
4. **Marginal benefit** - Advanced algorithms are rarely needed in workflow execution

## Consequences

### Positive

1. **Preserves type safety** - Phantom-typed `NodeRef[I, O]` continues to enforce I/O matching at compile-time
2. **No new dependencies** - Avoids adding ~6,500 lines of library code to the core path
3. **Domain focus** - WIOGraph remains optimized for workflows4s integration
4. **Simple codebase** - ~1,100 lines vs ~6,500+ for scala-graph core
5. **Flexibility** - Optional scala-graph integration available when needed for analysis
6. **No learning curve** - Team continues with familiar abstractions

### Negative

1. **Limited algorithms** - Must implement any needed graph algorithms manually
2. **No built-in visualization** - DOT export would need custom implementation
3. **No hyperedge support** - If needed in future, would require significant work
4. **No constraint system** - Runtime invariants (like Acyclic) not enforceable at graph level

### Neutral

1. **scala-graph remains available** - Can be added as optional dependency for analysis tooling
2. **Migration path exists** - If requirements change significantly, decision can be revisited
3. **Validation is sufficient** - Current cycle/connectivity checks meet requirements

## Feature Comparison Matrix

| Feature | scala-graph | adk4s Graph | adk4s WIOGraph |
|---------|-------------|-------------|----------------|
| **Type Safety** |
| Path-dependent types | ✓ | ✗ | ✗ |
| Phantom-typed I/O refs | ✗ | ✓ | ✓ |
| Context binding | ✗ | ✗ | ✓ |
| Error type propagation | ✗ | ✗ | ✓ |
| **Data Structures** |
| Directed edges | ✓ | ✓ | ✓ |
| Undirected edges | ✓ | ✗ | ✗ |
| Labeled edges | ✓ | ✗ | ✗ |
| Weighted edges | ✓ | ✗ | ✗ |
| Hyperedges | ✓ | ✗ | ✗ |
| **Algorithms** |
| BFS/DFS traversal | ✓ | ✗ | ✗ |
| Cycle detection | ✓ | ✓ | ✓ |
| Connectivity check | ✓ | ✓ | ✓ |
| Topological sort | ✓ | ✗ | ✗ |
| Shortest path | ✓ | ✗ | ✗ |
| **Integration** |
| WIO compilation | ✗ | ✗ | ✓ |
| workflows4s native | ✗ | Partial | ✓ |
| DOT export | ✓ | ✗ | ✗ |
| JSON serialization | ✓ | ✗ | ✗ |
| **Other** |
| Mutable variant | ✓ | ✗ | ✗ |
| Lazy evaluation | ✓ | ✗ | ✗ |
| Cross-platform | ✓ | JVM | JVM |
| Lines of code | ~6,500 | ~650 | ~450 |

## When to Reconsider This Decision

This decision should be revisited if:

1. **Advanced algorithms become critical** - If workflow optimization requires shortest path, minimum spanning tree, or similar algorithms
2. **Visualization becomes a primary feature** - If debugging tools need rich graph visualization
3. **Hypergraph semantics needed** - If workflows need to model many-to-many node relationships
4. **Performance issues arise** - If validation or traversal becomes a bottleneck with large graphs
5. **Team preferences shift** - If the team prefers working with a standard library over custom implementations

## References

- [scala-graph GitHub](https://github.com/scala-graph/scala-graph)
- [scala-graph Documentation](http://www.scala-graph.org/)
- [workflows4s](https://github.com/business4s/workflows4s)
- [Graph Implementation](/adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/)
- [WIOGraph Implementation](/adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/)
- [GraphValidation](/adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala)
