## MODIFIED Requirements

### Requirement: Graph Builder for DAG-based Workflows
The system SHALL provide a Graph[I, O, S] builder that creates DAG-based pipelines with branching, supporting lambda nodes, chat model nodes, tools nodes, sub-graphs, edges, and branches, with explicit entry and end nodes validated before compiling to Runnable[I, O] and translating to WIO via WIOExecutor. The builder MUST enforce WIO-compatible type constraints: edge connections MUST ensure the source node output type conforms to the target node input type, the entry node MUST accept the graph input type, and end nodes MUST output a subtype of the graph output type.

#### Scenario: Create empty graph
- **WHEN** `Graph[I, O]` is called
- **THEN** a Graph[I, O, Unit] instance is returned with empty nodes, edges, and branches
- **AND** the entry node is unset and end nodes are empty

#### Scenario: Create graph with state
- **GIVEN** an `IO[StateRef[IO, S]]`
- **WHEN** `Graph.withState[I, O, S](stateGen)` is called
- **THEN** a Graph[I, O, S] instance is returned with the stateGen configured

#### Scenario: Set entry node
- **GIVEN** a Graph with a node reference `node1Ref` for a node accepting input type `I`
- **WHEN** `graph.setEntry(node1Ref)` is called
- **THEN** a ValidatedNec with the updated Graph containing entry = NodeKey("node1") is returned

#### Scenario: Add end node
- **GIVEN** a Graph with a node reference `node2Ref` whose output type conforms to `O`
- **WHEN** `graph.addEndNode(node2Ref)` is called
- **THEN** a ValidatedNec with the updated Graph containing "node2" in end nodes is returned

#### Scenario: Add lambda node to graph
- **GIVEN** a Graph[I, O, S] and a Lambda[A, B]
- **WHEN** `graph.addLambdaNode(key, lambda)` is called with a unique key
- **THEN** a ValidatedNec with the updated Graph containing a LambdaNode is returned

#### Scenario: Add edge between nodes
- **GIVEN** a Graph with node references `node1Ref` and `node2Ref` where the output type of `node1Ref` conforms to the input type of `node2Ref`
- **WHEN** `graph.addEdge(node1Ref, node2Ref)` is called
- **THEN** a ValidatedNec with the updated Graph containing the edge is returned
- **AND** the edge maps from "node1" to "node2"

#### Scenario: Reject edge with incompatible node types
- **GIVEN** a Graph with node references `node1Ref` (output type `Int`) and `node2Ref` (input type `String`)
- **WHEN** `graph.addEdge(node1Ref, node2Ref)` is attempted
- **THEN** the call is rejected by the type checker

#### Scenario: Add branch after node
- **GIVEN** a Graph with a node "node1" and a Branch[A]
- **WHEN** `graph.addBranch("node1", branch)` is called
- **THEN** a ValidatedNec with the updated Graph containing the branch is returned
- **AND** the branch is associated with "node1"

#### Scenario: Validate graph for cycles
- **GIVEN** a Graph with edges forming a cycle
- **WHEN** `graph.compile` is called
- **THEN** a ValidatedNec with an AdkError (GraphCompiledError) is returned
- **AND** the error indicates a cycle was detected

#### Scenario: Validate graph for missing edges
- **GIVEN** a Graph with an edge to a non-existent node
- **WHEN** `graph.compile` is called
- **THEN** a ValidatedNec with an AdkError is returned
- **AND** the error indicates a missing node target

#### Scenario: Validate graph for missing entry or end nodes
- **GIVEN** a Graph with nodes and edges but no entry node or end nodes
- **WHEN** `graph.compile` is called
- **THEN** a ValidatedNec with an AdkError is returned
- **AND** the error indicates the entry node or end nodes are missing

#### Scenario: Compile valid graph to Runnable
- **GIVEN** a valid Graph with nodes, edges, entry node, and end nodes (no cycles, all targets exist)
- **WHEN** `graph.compile(config)` is called
- **THEN** a ValidatedNec with an IO[Runnable[I, O]] is returned
- **AND** the Runnable executes the DAG correctly with the provided GraphConfig

#### Scenario: Translate graph to WIO
- **GIVEN** a valid Graph with nodes, edges, entry node, and end nodes (no cycles, all targets exist)
- **WHEN** `WIOExecutor.toWIO(graph)` is called
- **THEN** a WIO is returned that starts at the entry node and terminates at the configured end nodes

#### Scenario: Compile graph with custom config
- **GIVEN** a valid Graph and a GraphConfig(maxRunSteps = 50, graphName = Some("my-graph"))
- **WHEN** `graph.compile(config)` is called
- **THEN** a ValidatedNec with an IO[Runnable[I, O]] is returned
- **AND** the Runnable respects maxRunSteps = 50

### Requirement: Graph Node Types
The system SHALL provide a GraphNode ADT with variants for LambdaNode, ChatModelNode, ToolsNode, and SubGraphNode.

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

#### Scenario: Create sub-graph node
- **GIVEN** a Graph[A, B, S]
- **WHEN** `GraphNode.SubGraphNode(graph)` is created
- **THEN** a GraphNode[A, B] of type SubGraphNode is returned
