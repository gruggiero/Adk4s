## ADDED Requirements

### Requirement: Chain Builder for Linear Pipelines
The system SHALL provide a Chain[I, O] builder that composes operations into linear pipelines (A -> B -> C) with methods for appending Lambda, ChatModel, Branch, and passthrough steps, and compiles to Runnable[I, O].

#### Scenario: Create empty chain
- **WHEN** `Chain[I, O]` is called
- **THEN** a Chain instance is returned with an empty steps Vector

#### Scenario: Append Lambda to chain
- **GIVEN** a Chain[I, O] and a Lambda[O, O2]
- **WHEN** `chain.appendLambda(lambda)` is called
- **THEN** a Chain[I, O2] is returned with LambdaStep added to steps

#### Scenario: Append ChatModel to chain
- **GIVEN** a Chain[I, O] and a ChatModel[IO]
- **WHEN** `chain.appendChatModel(model)` is called
- **THEN** a Chain[I, Completion] is returned with ChatModelStep added to steps

#### Scenario: Append Branch to chain
- **GIVEN** a Chain[I, O] and a ChainBranch[O, O2]
- **WHEN** `chain.appendBranch(branch)` is called
- **THEN** a Chain[I, O2] is returned with BranchStep added to steps

#### Scenario: Append passthrough (no-op)
- **GIVEN** a Chain[I, O]
- **WHEN** `chain.appendPassthrough` is called
- **THEN** the same Chain[I, O] instance is returned (no change)

#### Scenario: Compile chain to Runnable
- **GIVEN** a Chain with multiple steps
- **WHEN** `chain.compile` is called
- **THEN** an IO[Runnable[I, O]] is returned
- **AND** the Runnable executes steps sequentially

### Requirement: Graph Builder for DAG-based Workflows
The system SHALL provide a Graph[I, O, S] builder that creates DAG-based pipelines with branching, supporting lambda nodes, chat model nodes, tools nodes, sub-graphs, edges, and branches, with validation before compiling to Runnable[I, O].

#### Scenario: Create empty graph
- **WHEN** `Graph[I, O]` is called
- **THEN** a Graph[I, O, Unit] instance is returned with empty nodes, edges, and branches

#### Scenario: Create graph with state
- **GIVEN** an `IO[StateRef[IO, S]]`
- **WHEN** `Graph.withState[I, O, S](stateGen)` is called
- **THEN** a Graph[I, O, S] instance is returned with the stateGen configured

#### Scenario: Add lambda node to graph
- **GIVEN** a Graph[I, O, S] and a Lambda[A, B]
- **WHEN** `graph.addLambdaNode(key, lambda)` is called with a unique key
- **THEN** a ValidatedNec with the updated Graph containing a LambdaNode is returned

#### Scenario: Add edge between nodes
- **GIVEN** a Graph with two nodes with keys "node1" and "node2"
- **WHEN** `graph.addEdge("node1", "node2")` is called
- **THEN** a ValidatedNec with the updated Graph containing the edge is returned
- **AND** the edge maps from "node1" to "node2"

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

#### Scenario: Compile valid graph to Runnable
- **GIVEN** a valid Graph with nodes and edges (no cycles, all targets exist)
- **WHEN** `graph.compile(config)` is called
- **THEN** a ValidatedNec with an IO[Runnable[I, O]] is returned
- **AND** the Runnable executes the DAG correctly with the provided GraphConfig

#### Scenario: Compile graph with custom config
- **GIVEN** a valid Graph and a GraphConfig(maxRunSteps = 50, graphName = Some("my-graph"))
- **WHEN** `graph.compile(config)` is called
- **THEN** a ValidatedNec with an IO[Runnable[I, O]] is returned
- **AND** the Runnable respects maxRunSteps = 50

### Requirement: Workflow Builder with Field Mapping
The system SHALL provide a Workflow[I, O] builder with field-level data mapping, supporting lambda and chat model nodes, input field mappings, end node specification, and compilation to Runnable[I, O].

#### Scenario: Create empty workflow
- **WHEN** `Workflow[I, O]` is called
- **THEN** a Workflow[I, O] instance is returned with empty nodes and inputs

#### Scenario: Add lambda node with default input mapping
- **GIVEN** a Workflow[I, O] and a Lambda[A, B]
- **WHEN** `workflow.addLambdaNode(key, lambda).addInput(sourceKey)` is called
- **THEN** a WorkflowNodeBuilder is returned
- **AND** the builder adds an input mapping from sourceKey with FieldPath.Root to FieldPath.Root

#### Scenario: Add lambda node with custom field mapping
- **GIVEN** a Workflow[I, O] and a Lambda[A, B]
- **WHEN** `workflow.addLambdaNode(key, lambda).addInput(sourceKey, FieldMapping("output.data", "input"))` is called
- **THEN** a WorkflowNodeBuilder is returned
- **AND** the builder adds an input mapping from sourceKey with custom field paths

#### Scenario: Set end node for workflow
- **GIVEN** a Workflow with multiple nodes
- **WHEN** `workflow.end` is called
- **THEN** a WorkflowEndBuilder is returned
- **AND** the end node can be specified

#### Scenario: Compile workflow to Runnable
- **GIVEN** a Workflow with nodes, inputs, and end node specified
- **WHEN** `workflow.compile` is called
- **THEN** an IO[Runnable[I, O]] is returned
- **AND** the Runnable executes the workflow with field-level data mapping applied

### Requirement: Field Mapping for Data Flow
The system SHALL provide a FieldMapping type that specifies source and destination field paths with optional source node specification.

#### Scenario: Create field mapping with string paths
- **WHEN** `FieldMapping("source.path", "dest.path")` is called
- **THEN** a FieldMapping is created with from = FieldPath("source.path") and to = FieldPath("dest.path")

#### Scenario: Create field mapping with explicit fromNode
- **WHEN** `FieldMapping(FieldPath("source"), FieldPath("dest"), Some(NodeKey.unsafeApply("node1")))` is called
- **THEN** a FieldMapping is created with fromNode = Some(NodeKey.unsafeApply("node1"))

### Requirement: Workflows4s WIO Execution Integration
The system SHALL provide a WIOExecutor that converts Graph to Workflows4s WIO for execution.

#### Scenario: Convert graph to WIO
- **GIVEN** a valid Graph[I, O, S]
- **WHEN** `WIOExecutor.toWIO[I, O, Ctx](graph)` is called
- **THEN** a WIO[I, Nothing, O, Ctx] is returned
- **AND** the WIO represents the graph structure with branching logic

#### Scenario: Execute graph via WIO
- **GIVEN** a Graph[I, O, ?] and an input value of type I
- **WHEN** `WIOExecutor.execute(graph, input)` is called
- **THEN** an IO[O] is returned with the execution result
- **AND** the execution follows the graph structure with branching

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

#### Scenario: Create sub-graph node
- **GIVEN** a Graph[A, B, S]
- **WHEN** `GraphNode.SubGraphNode(graph)` is created
- **THEN** a GraphNode[A, B] of type SubGraphNode is returned

### Requirement: Workflow Node Types
The system SHALL provide a WorkflowNode ADT with variants for Lambda and ChatModel.

#### Scenario: Create lambda workflow node
- **GIVEN** a Runnable Lambda[I, O]
- **WHEN** `WorkflowNode.Lambda(lambda)` is created
- **THEN** a WorkflowNode[I, O] of type Lambda is returned

#### Scenario: Create chat model workflow node
- **GIVEN** a ChatModel[IO]
- **WHEN** `WorkflowNode.ChatModel(model)` is created
- **THEN** a WorkflowNode[Conversation, Completion] of type ChatModel is returned

### Requirement: Chain Branch for Conditional Routing
The system SHALL provide a ChainBranch[I, O] type for conditional routing within chains (similar to Branch but chain-specific).

#### Scenario: Create binary chain branch
- **GIVEN** a predicate `I => IO[Boolean]` and two Runnable targets
- **WHEN** `ChainBranch.binary(predicate, ifTrue, ifFalse)` is called
- **THEN** a ChainBranch is created that routes to ifTrue when true, otherwise to ifFalse

#### Scenario: Create endIf chain branch
- **GIVEN** a predicate `I => IO[Boolean]` and a fallback Runnable
- **WHEN** `ChainBranch.endIf(predicate, otherwise)` is called
- **THEN** a ChainBranch is created that routes to END when true, otherwise to otherwise

### Requirement: Graph Configuration Options
The system SHALL provide a GraphConfig for configuring graph execution behavior.

#### Scenario: Create default graph config
- **WHEN** `GraphConfig()` is called
- **THEN** a GraphConfig with maxRunSteps = 100 and graphName = None is returned

#### Scenario: Create graph config with custom values
- **WHEN** `GraphConfig(maxRunSteps = 50, graphName = Some("my-graph"))` is called
- **THEN** a GraphConfig with maxRunSteps = 50 and graphName = Some("my-graph") is returned
