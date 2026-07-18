# Concept: Graph

## Concept specification

```
concept Graph[In, Out]
purpose
    A generic typed workflow graph with typed node refs, edge validation,
    and compilation to a Runnable via GraphExecutor.
state
    nodes: Graph -> Map[NodeKey, GraphNode[?, ?]]
    edges: Graph -> Map[NodeKey, Set[NodeKey]]
    entryNode: Graph -> Option[NodeKey]
    endNodes: Graph -> Set[NodeKey]
    compiled: Graph -> Boolean
actions
    addLambdaNode [ key: String ; lambda: Lambda[A, B] ]
        => [ NodeAddition[In, Out, A, B] ]
    addChatModelNode [ key ; model: ChatModel[IO] ]
        => [ NodeAddition[..., Conversation, Completion] ]
    addToolsNode [ key ; toolsNode: ToolsNode ]
        => [ NodeAddition[..., List[ToolCall], List[ToolMessage]] ]
    addSubGraphNode [ key ; subGraph: Graph[A, B] ]
        => [ NodeAddition[..., A, B] ]
    addStructuredModelNode [ key ; model ; template ]
        => [ NodeAddition[..., A, B] ]
    addMergeNode [ key ; combine: (A, B) => C ]
        => [ NodeAddition[..., (A, B), C] ]
    addForkNode [ key ; forkSpec: ForkSpec[A, B] ]
        => [ NodeAddition[..., A, B] ]
    addEdge [ from: NodeRef[A, B] ; to: NodeRef[B, C] ]
        => [ Graph[In, Out] ]
    setEntry [ entry: NodeRef[In, A] ]
        => [ Graph[In, Out] ]
    addEndNode [ endNode: NodeRef[A, Out] ]
        => [ Graph[In, Out] ]
    compile [ config: GraphConfig ]
        => [ IO[Runnable[In, Out]] ]
    compile [ config ]
        => [ error: GraphCompiledError() ]   # already compiled
    validateGraph
        => [ ValidatedNec[AdkError, Unit] ]
operational principle
    A builder adds typed nodes (Lambda, ChatModel, ToolsNode, SubGraph,
    StructuredModel, Merge, Fork, Pure, RunIO, TypedAgent), wires edges
    via NodeRefs, sets entry and end nodes, then calls compile. Validation
    rejects missing entry/end, missing edge targets, cycles, unreachable
    nodes, and fan-in except on merge nodes.
```

## Implementation map

| Element | Code |
|---|---|
| class `Graph` | `case class Graph[In, Out](nodes, edges, entryNode, endNodes, compiled)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| action `addLambdaNode` | `Graph.addLambdaNode[A, B](key, lambda): ValidatedNec[AdkError, NodeAddition]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| action `addChatModelNode` | `Graph.addChatModelNode(key, model)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| action `addToolsNode` | `Graph.addToolsNode(key, toolsNode)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| action `addSubGraphNode` | `Graph.addSubGraphNode[A, B](key, subGraph)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| action `addStructuredModelNode` | `Graph.addStructuredModelNode[A, B](key, model, template)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| action `addMergeNode` | `Graph.addMergeNode[A, B, C](key, combine)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| action `addForkNode` | `Graph.addForkNode[A, B](key, forkSpec)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| action `addEdge` | `Graph.addEdge[A, B, C](from, to): ValidatedNec[AdkError, Graph[In, Out]]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| action `setEntry` | `Graph.setEntry[A](entry)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| action `addEndNode` | `Graph.addEndNode[A](endNode)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| action `compile` | `Graph.compile(config): ValidatedNec[AdkError, IO[Runnable[In, Out]]]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| action `validateGraph` | `Graph.validateGraph: ValidatedNec[AdkError, Unit]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| validation `GraphValidation` | `object GraphValidation` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala`) |
| node `GraphNode` | `sealed trait GraphNode[I, O]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala`) |
| ref `NodeRef` | `case class NodeRef[I, O](key: NodeKey)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| ref `NodeAddition` | `case class NodeAddition[In, Out, A, B]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`) |
| error `NodeNotFoundError` | `NodeNotFoundError(key: String)` (`adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`) |
| error `EdgeValidationError` | `EdgeValidationError(from, to, message)` (`adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`) |
| error `GraphEntryMissingError` | `GraphEntryMissingError()` (`adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`) |
| error `FanInError` | `FanInError(nodeKey)` (`adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`) |
| runtime host | `org.adk4s.orchestration.graph` |

## Deviations from the pattern

- `Graph.executeGraph` raises "not yet fully implemented" — direct execution is stubbed (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`).
- `GraphNode.SubGraphNode.toRunnable` raises "not yet implemented" (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala`).
- `GraphNode.ForkNode.toRunnable` raises "should be executed via GraphExecutor" (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala`).
- `addForkInternal` is a no-op (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`).
- Nodes are stored as `GraphNode[?, ?]`, losing type information; type safety relies on the `NodeRef` phantom types and runtime casts in execution (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`).
- `compiled` is a mutable boolean flag that prevents recompilation — once compiled, the graph cannot be re-compiled even if no execution has occurred (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`).
