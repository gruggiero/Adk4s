# Concept: WIOGraph

## Concept specification

```
concept WIOGraph[Ctx, In, Err, Out]
purpose
    A type-safe DAG built from WIONodes; validates edges, entry, and end
    nodes; compiles to a workflows4s WIO or to a Runnable.
state
    nodes: WIOGraph -> List[NodeEntry[Ctx, Err, ?, ? <: Out]]
    edges: WIOGraph -> List[Edge[Ctx, ?, ? <: Out, ? <: Out]]
    entryNode: WIOGraph -> Option[WIONodeRef[Ctx, In, ? <: Out]]
    endNodes: WIOGraph -> Set[NodeKey]
actions
    addNode [ key: String ; node: WIONode[Ctx, I, Err, O] ]
        => [ graph: WIOGraph ]
    addNode [ key ; node ]
        => [ error: NodeAlreadyExists(key) ]
    addEdge [ from: WIONodeRef ; to: WIONodeRef ]
        => [ graph: WIOGraph ]
    addEdge [ from ; to ]
        => [ error: SourceNodeNotFound(from.key) ]
    addEdge [ from ; to ]
        => [ error: TargetNodeNotFound(to.key) ]
    setEntry [ entry: WIONodeRef ]
        => [ graph: WIOGraph ]   # or EntryNodeNotFound
    addEndNode [ endNode: WIONodeRef ]
        => [ graph: WIOGraph ]   # or EndNodeNotFound
    withCheckpoint [ nodeRef ; genEvent ; handleEvent ]
        => [ graph: WIOGraph ]
    withRetry [ nodeRef ; onError ]
        => [ graph: WIOGraph ]
    withInterruption [ nodeRef ; interruption ]
        => [ graph: WIOGraph ]
    toWIO (using ErrorMeta[Err])
        => [ wio: WIO[In, Err, Out, Ctx] ]
    toWIO
        => [ errors: NonEmptyChain[WIOGraphError] ]   # cycle, missing entry, unreachable ends
    toRunnable
        => [ runnable: Runnable[In, Out] ]
operational principle
    A builder adds nodes (pure, RunIO, Runnable, Loop, Await, Parallel,
    Fork, SubGraph, ForEach, HandleSignal), wires edges, sets an entry,
    marks end nodes, optionally attaches modifiers (Checkpoint, Retry,
    Interruption), then calls toWIO or toRunnable. Validation rejects
    cycles, missing entry, unreachable end nodes, multiple outgoing edges
    from a non-fork node, and fork edge/branch count mismatch.
```

## Implementation map

| Element | Code |
|---|---|
| class `WIOGraph` | `final case class WIOGraph[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`) |
| state `NodeEntry` | `WIOGraph.NodeEntry[Ctx, Err, I, O]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`) |
| state `Edge` | `WIOGraph.Edge[Ctx, A, B, C]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`) |
| action `addNode` | `WIOGraph.addNode[I, O <: Out](key, node): Either[WIOGraphError, WIOGraph]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`) |
| action `addEdge` | `WIOGraph.addEdge[A, B, C](from, to): Either[WIOGraphError, WIOGraph]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`) |
| action `setEntry` | `WIOGraph.setEntry[A <: Out](entry): Either[WIOGraphError, WIOGraph]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`) |
| action `addEndNode` | `WIOGraph.addEndNode[O2 <: Out](endNode): Either[WIOGraphError, WIOGraph]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`) |
| action `withCheckpoint` | `WIOGraph.withCheckpoint[I, O, Evt](nodeRef, genEvent, handleEvent)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`) |
| action `withRetry` | `WIOGraph.withRetry[I, O](nodeRef, onError)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`) |
| action `withInterruption` | `WIOGraph.withInterruption[I, O](nodeRef, interruption)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`) |
| action `toWIO` | `WIOGraph.toWIO(using ErrorMeta[Err]): Either[NonEmptyChain[WIOGraphError], WIO[In, Err, Out, Ctx]]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`) |
| action `toRunnable` | `WIOGraph.toRunnable: Either[NonEmptyChain[WIOGraphError], Runnable[In, Out]]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`) |
| error `WIOGraphError` | `sealed trait WIOGraphError` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraphError.scala`) |
| error `CycleDetected` | `WIOGraphError.CycleDetected(cycle: List[NodeKey])` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraphError.scala`) |
| error `MissingEntry` | `WIOGraphError.MissingEntry` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraphError.scala`) |
| error `UnreachableEnd` | `WIOGraphError.UnreachableEnd(endNodes: Set[NodeKey])` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraphError.scala`) |
| error `MultipleOutgoingEdges` | `WIOGraphError.MultipleOutgoingEdges(key: String)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraphError.scala`) |
| error `ForkEdgeBranchMismatch` | `WIOGraphError.ForkEdgeBranchMismatch(edgeCount, branchCount)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraphError.scala`) |
| ref `WIONodeRef` | `final case class WIONodeRef[Ctx, I, O]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIONodeRef.scala`) |
| runtime host | `org.adk4s.orchestration.wiograph` |

## Deviations from the pattern

- The compilation path uses `asInstanceOf` extensively (multiple sites in `WIOGraph.toWIO` and per-node `toWIO` methods) to bridge the graph's existentially-typed nodes to WIO's variance — type safety is lost at compile time (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`).
- `WIOGraph`'s type parameter order is `[Ctx, In, Err, Out]`, differing from `WIO[-In, +Err, +Out, Ctx]` — a documented source of confusion (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`).
- `WIOSubGraphNode.toWIO` compiles the sub-graph via `subGraph.toWIO` and, on failure, synthesizes a failing `WIO.RunIO` with the error string — the sub-graph's structured errors are flattened to a string (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIONode.scala`).
- Fork execution uses a hardcoded workflow ID (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`).
- `RetryModifier` uses `Instant.EPOCH` as a dummy timestamp and casts `Applicative[IO]` to `Applicative[WCEffect[Ctx]]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIONodeModifier.scala`).
- `StreamBranch` exists on `WIOForkNode` but is not integrated into WIO compilation (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIONode.scala`).
