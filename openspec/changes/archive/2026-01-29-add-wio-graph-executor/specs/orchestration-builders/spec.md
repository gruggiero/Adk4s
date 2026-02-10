## ADDED Requirements
### Requirement: Graph Node Executables
The system SHALL provide a NodeExecutable[I, O] abstraction that exposes IO execution and WIO compilation for graph nodes, and every GraphNode variant MUST provide access to a NodeExecutable[I, O] so the WIO compiler can compose nodes without runtime casts.

#### Scenario: GraphNode exposes WIO execution
- **GIVEN** a GraphNode[A, B]
- **WHEN** its execution capability is requested
- **THEN** a NodeExecutable[A, B] is returned that supports invocation and WIO compilation

## MODIFIED Requirements
### Requirement: Graph Builder for DAG-based Workflows
The system SHALL provide a Graph[I, O, S] builder that creates DAG-based pipelines with branching, supporting lambda nodes, chat model nodes, tools nodes, structured model nodes, structured tool nodes, merge nodes, and sub-graphs, with explicit entry and end nodes validated before compiling to Runnable[I, O] and translating to WIO via WIOExecutor. The builder MUST enforce WIO-compatible type constraints: edge connections MUST ensure the source node output type conforms to the target node input type, the entry node MUST accept the graph input type, and end nodes MUST output a subtype of the graph output type. Branch targets MUST match declared outgoing edges from the branching node. The builder MUST reject fan-in by adding more than one incoming edge to a node unless the target node is a MergeNode.

#### Scenario: Create empty graph
- **WHEN** `Graph[I, O]` is called
- **THEN** a Graph[I, O, Unit] instance is returned with empty nodes, edges, and branches
- **AND** the entry node is unset and end nodes are empty

#### Scenario: Set entry node
- **GIVEN** a Graph with a node reference `node1Ref` for a node accepting input type `I`
- **WHEN** `graph.setEntry(node1Ref)` is called
- **THEN** a ValidatedNec with the updated Graph containing entry = NodeKey("node1") is returned

#### Scenario: Add end node
- **GIVEN** a Graph with a node reference `node2Ref` whose output type conforms to `O`
- **WHEN** `graph.addEndNode(node2Ref)` is called
- **THEN** a ValidatedNec with the updated Graph containing "node2" in end nodes is returned

#### Scenario: Add edge between nodes
- **GIVEN** a Graph with node references `node1Ref` and `node2Ref` where the output type of `node1Ref` conforms to the input type of `node2Ref`
- **WHEN** `graph.addEdge(node1Ref, node2Ref)` is called
- **THEN** a ValidatedNec with the updated Graph containing the edge is returned
- **AND** the edge maps from "node1" to "node2"

#### Scenario: Reject edge with incompatible node types
- **GIVEN** a Graph with node references `node1Ref` (output type `Int`) and `node2Ref` (input type `String`)
- **WHEN** `graph.addEdge(node1Ref, node2Ref)` is attempted
- **THEN** the call is rejected by the type checker

#### Scenario: Reject fan-in on target nodes
- **GIVEN** a Graph with an existing edge from "node1" to "node2"
- **WHEN** `graph.addEdge("node3", "node2")` is called
- **THEN** a ValidatedNec with an AdkError is returned
- **AND** the error indicates fan-in is not supported for "node2"

#### Scenario: Allow fan-in into merge nodes
- **GIVEN** a Graph with a MergeNode target "merge1" and an existing edge from "node1" to "merge1"
- **WHEN** `graph.addEdge("node2", "merge1")` is called
- **THEN** a ValidatedNec with the updated Graph containing both incoming edges is returned

#### Scenario: Reject branch targets not declared as outgoing edges
- **GIVEN** a Graph with a branch on "node1" whose target includes "node3" but no outgoing edge to "node3"
- **WHEN** `graph.compile` is called
- **THEN** a ValidatedNec with an AdkError is returned
- **AND** the error indicates the branch target is not a declared outgoing edge

#### Scenario: Compile valid graph to Runnable
- **GIVEN** a valid Graph with nodes, edges, entry node, and end nodes (no cycles, all targets exist)
- **WHEN** `graph.compile(config)` is called
- **THEN** a ValidatedNec with an IO[Runnable[I, O]] is returned
- **AND** the Runnable executes the DAG correctly with the provided GraphConfig

#### Scenario: Translate graph to WIO
- **GIVEN** a valid Graph with nodes, edges, entry node, and end nodes (no cycles, all targets exist)
- **WHEN** `WIOExecutor.toWIO(graph)` is called
- **THEN** a WIO is returned that starts at the entry node and terminates at the configured end nodes

### Requirement: Workflows4s WIO Execution Integration
The system SHALL provide a WIOExecutor that converts Graph to Workflows4s WIO using a static DAG compilation (Option A) that composes node-level WIO steps from the entry node to the end nodes. Branches MUST be translated to Workflows4s fork/branch constructs, MergeNode fan-in MUST be compiled using Workflows4s parallel composition, and sub-graphs MUST be compiled via WIOExecutor and embedded as nested WIO steps. Graph execution via WIOExecutor.execute MUST run the compiled WIO using a Workflows4s runtime.

#### Scenario: Convert graph to WIO via static DAG compilation
- **GIVEN** a valid Graph[I, O, S]
- **WHEN** `WIOExecutor.toWIO[I, O, Ctx](graph)` is called
- **THEN** a WIO[I, Nothing, O, Ctx] is returned
- **AND** the WIO represents the graph structure with branching logic

#### Scenario: Execute graph via WIO runtime
- **GIVEN** a Graph[I, O, ?] and an input value of type I
- **WHEN** `WIOExecutor.execute(graph, input)` is called
- **THEN** an IO[O] is returned with the execution result
- **AND** the execution runs through a Workflows4s runtime

#### Scenario: Compile merge node fan-in
- **GIVEN** a Graph containing a MergeNode with two incoming edges
- **WHEN** `WIOExecutor.toWIO` is called
- **THEN** the resulting WIO composes the incoming branches in parallel and applies the merge function

### Requirement: Graph Node Types
The system SHALL provide a GraphNode ADT with variants for LambdaNode, ChatModelNode, ToolsNode, StructuredModelNode, StructuredToolNode, MergeNode, and SubGraphNode.

#### Scenario: Create lambda graph node
- **GIVEN** a Lambda[A, B]
- **WHEN** `GraphNode.LambdaNode(lambda)` is created
- **THEN** a GraphNode[A, B] of type LambdaNode is returned

#### Scenario: Create chat model graph node
- **GIVEN** a ChatModel[IO]
- **WHEN** `GraphNode.ChatModelNode(model)` is created
- **THEN** a GraphNode[Conversation, Completion] of type ChatModelNode is returned

#### Scenario: Create tools graph node
- **GIVEN** a ToolsNode
- **WHEN** `GraphNode.ToolsNode(toolsNode)` is created
- **THEN** a GraphNode[List[ToolCall], List[ToolMessage]] of type ToolsNode is returned
- **AND** toolsNode is typed as `org.adk4s.core.tools.ToolsNode`

#### Scenario: Create structured model graph node
- **GIVEN** a StructuredLLM[IO] and a PromptTemplate[I]
- **WHEN** `GraphNode.StructuredModelNode(model, promptTemplate)` is created
- **THEN** a GraphNode[I, O] of type StructuredModelNode is returned

#### Scenario: Create structured tool graph node
- **GIVEN** a StructuredToolCall[IO] and a tool name
- **WHEN** `GraphNode.StructuredToolNode(structuredToolCall, toolName)` is created
- **THEN** a GraphNode[ToolCall, O] of type StructuredToolNode is returned

#### Scenario: Create sub-graph node
- **GIVEN** a Graph[A, B, S]
- **WHEN** `GraphNode.SubGraphNode(graph)` is created
- **THEN** a GraphNode[A, B] of type SubGraphNode is returned

#### Scenario: Create merge graph node
- **GIVEN** a merge function `(A, B) => C`
- **WHEN** `GraphNode.MergeNode(combine)` is created
- **THEN** a GraphNode[(A, B), C] of type MergeNode is returned
