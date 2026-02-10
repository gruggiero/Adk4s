# ADK4S Examples

This module contains example implementations demonstrating the capabilities of ADK4S (Agent Development Kit for Scala).

## Overview

ADK4S provides a functional programming approach to building agent workflows using:
- **Graph-based composition** for defining agent flows
- **Workflow compilation** to convert graphs to executable workflows
- **Branching and parallel execution** for complex agent behaviors
- **LLM integration** through a standardized interface

## Examples

### Chain Example

The chain example (`org.adk4s.examples.chain.ChainExample`) demonstrates a complete agent workflow that mirrors the Eino compose/chain example. It showcases:

1. **Conditional Branching**: Randomly selects between two paths (cat or dog role)
2. **Parallel Execution**: Processes role and input in parallel
3. **LLM Integration**: Uses OpenAI API or a mock model for chat completion
4. **Graph to Workflow**: Compiles a graph definition into an executable workflow

#### Key Components

```scala
// Graph definition with nodes and edges
val graph: Graph[ChainInput, ChainOutput, Unit] = Graph[ChainInput, ChainOutput]
  .addLambdaNode("initial", ...)
  .addLambdaNode("branch_condition", ...)
  .addLambdaNode("b1", ...)  // Cat branch
  .addLambdaNode("b2", ...)  // Dog branch
  .addLambdaNode("parallel_role", ...)
  .addLambdaNode("parallel_input", ...)
  .addMergeNode("merge_parallel", ...)
  .addLambdaNode("prompt_template", ...)
  .addChatModelNode("chat_model", chatModel)
  .addLambdaNode("output", ...)
  .addEdge(...)
  .addBranch(...)
  .setEntry(...)
  .addEndNode(...)
```

#### Running the Example

1. **With OpenAI API**:
   ```bash
   export OPENAI_BASE_URL="https://api.openai.com/v1"
   export OPENAI_API_KEY="your-api-key"
   export OPENAI_MODEL_NAME="gpt-3.5-turbo"
   sbt "adk4s-examples/runMain org.adk4s.examples.chain.ChainExample"
   ```

2. **With Mock Model** (no API required):
   ```bash
   sbt "adk4s-examples/runMain org.adk4s.examples.chain.ChainExample"
   ```

#### Expected Output

```
in view lambda: Map()
hello in branch lambda 01  # or lambda 02 depending on random choice
in view of messages: Meow! I purr when I'm happy...  # or Woof! I bark...
Chain execution completed. Result: ChainOutput(cat, Meow! I purr...)
```

## Building and Running

### Prerequisites

- Scala 3.7.3+
- sbt 1.9.0+
- Java 17+

### Compilation

```bash
sbt adk4s-examples/compile
```

### Running Examples

```bash
# Run the chain example
sbt "adk4s-examples/runMain org.adk4s.examples.chain.ChainExample"

# Run with specific JVM options
sbt "adk4s-examples/run -J-Xmx2g org.adk4s.examples.chain.ChainExample"
```

### Testing

```bash
sbt adk4s-examples/test
```

## Architecture

### Graph-Based Design

ADK4S uses a graph-based approach where:
- **Nodes** represent processing steps (LLM calls, lambdas, tools)
- **Edges** define the flow between nodes
- **Branches** enable conditional routing
- **Parallel execution** is achieved through merge nodes

### Workflow Compilation

Graphs are compiled to Workflows4s WIO:
```scala
val workflow = WIOExecutor.toWIO(graph)
val runtime = InMemorySyncRuntime.create(workflow, initialState, engine)
```

### Functional Programming Principles

- **Immutable data structures** for graph definitions
- **Pure functions** for node implementations
- **Type-safe composition** through typed node references
- **Effect management** with Cats Effect IO

## Configuration

Examples can be configured through environment variables:

- `OPENAI_BASE_URL`: OpenAI API base URL
- `OPENAI_API_KEY`: OpenAI API key
- `OPENAI_MODEL_NAME`: Model to use (default: gpt-3.5-turbo)

## Extending Examples

To create your own example:

1. Define input/output types:
   ```scala
   case class MyInput(data: String)
   case class MyOutput(result: String)
   ```

2. Create a graph:
   ```scala
   val graph = Graph[MyInput, MyOutput]
     .addLambdaNode("process", Lambda[MyInput, String](...))
     .addEdge(...)
     .setEntry(...)
     .addEndNode(...)
   ```

3. Compile and run:
   ```scala
   val workflow = WIOExecutor.toWIO(graph)
   val runtime = InMemorySyncRuntime.create(workflow, (), engine)
   ```

## Eino-Equivalent Examples

These examples mirror the [Eino](https://github.com/cloudwego/eino-examples) Go framework examples, reimplemented in Scala using adk4s and llm4s APIs. All examples work with a `MockChatModel` by default; set `OPENAI_API_KEY` for real LLM calls.

### Components (Phase 1)

| Example | Eino Equivalent | Description |
|---------|----------------|-------------|
| `LambdaExample` | `components/lambda` | Runnable modes: invoke, stream, transform, collect, composition |
| `ChatModelExample` | `components/model` | ChatModel generate/stream, multi-turn conversations |
| `ChatTemplateExample` | `components/prompt` | Variable substitution, template rendering, multiple templates |
| `DocumentLoaderExample` | `components/document` | Document loading, chunking (simple, sentence, markdown) |
| `RetrieverExample` | `components/retriever` | Keyword-based retrieval, chunking pipeline |
| `ToolSchemaExample` | `components/tool` | Tool schema inference from case classes, JSON fix middleware |

### Graphs (Phase 2)

| Example | Eino Equivalent | Description |
|---------|----------------|-------------|
| `SimpleGraphExample` | `graph/simple` | WIOGraph with ChatTemplate → ChatModel pipeline |
| `ToolCallAgentExample` | `graph/tool_call_agent` | Tool execution within WIOGraph using ToolsNode |
| `TwoModelChatExample` | `graph/two_model_chat` | Writer/critic loop using WIOLoopNode |
| `StateGraphExample` | `graph/state` | Invoke/stream/transform modes via WIORunnableNode |
| `ToolCallOnceExample` | `graph/tool_call_once` | Conditional tool call branching via WIOForkNode |
| `AsyncNodeExample` | `graph/async_node` | Async invoke + streaming nodes with delays |
| `ReactWithInterruptExample` | `graph/react_with_interrupt` | Human-in-the-loop with InterruptibleNode + CheckpointStore |

### Workflows (Phase 3)

| Example | Eino Equivalent | Description |
|---------|----------------|-------------|
| `SimpleWorkflowExample` | `workflow/1_simple` | Lambda chain via WIOGraph with pure/effectful nodes |
| `BranchWorkflowExample` | `workflow/4_control_only_branch` | Branching via WIOForkNode with conditional routing |
| `StaticValuesExample` | `workflow/5_static_values` | Static value injection via WIOPureNode transforms |
| `FieldMappingWorkflowExample` | `workflow/2_field_mapping` | Field mapping via typed lambdas (word counting) |
| `DataOnlyWorkflowExample` | `workflow/3_data_only` | Data-only dependencies with explicit field extraction |
| `StreamFieldMapExample` | `workflow/6_stream_field_map` | Stream-level field splitting, per-chunk processing, merging |

### Agent Patterns (Phase 4)

| Example | Eino Equivalent | Description |
|---------|----------------|-------------|
| `ReactMemoryExample` | `agent/react/memory` | Multi-turn conversation with memory context |
| `MultiAgentHostExample` | `agent/multiagent/host` | Host/router with classifier and specialist agents |
| `PlanExecuteExample` | `agent/plan_execute` | Plan-and-execute with planner and executor agents |
| `ReactAgentExample` | `flow/agent/react` | ReAct agent with tool calling loop + streaming |
| `DynamicOptionExample` | `flow/agent/react/dynamic_option` | Dynamic tool registry with runtime add/remove |

### Batch (Phase 6)

| Example | Eino Equivalent | Description |
|---------|----------------|-------------|
| `BatchExample` | `compose/batch` | BatchExecutor: sequential, parallel, streaming, error isolation |

### Quickstart (Phase 5)

| Example | Eino Equivalent | Description |
|---------|----------------|-------------|
| `ChatExample` | `quickstart/chat` | Basic multi-turn chat with generate and stream modes |

### Running Eino Examples

```bash
# Components
sbt "adk4s-examples/runMain org.adk4s.examples.eino.components.LambdaExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.components.ChatModelExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.components.ChatTemplateExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.components.DocumentLoaderExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.components.RetrieverExample"

# Graphs
sbt "adk4s-examples/runMain org.adk4s.examples.eino.graph.SimpleGraphExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.graph.ToolCallAgentExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.graph.TwoModelChatExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.graph.StateGraphExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.graph.ToolCallOnceExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.graph.AsyncNodeExample"

# Workflows
sbt "adk4s-examples/runMain org.adk4s.examples.eino.workflow.SimpleWorkflowExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.workflow.BranchWorkflowExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.workflow.StaticValuesExample"

# Agent Patterns
sbt "adk4s-examples/runMain org.adk4s.examples.eino.agent.ReactMemoryExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.agent.MultiAgentHostExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.agent.PlanExecuteExample"

# Quickstart
sbt "adk4s-examples/runMain org.adk4s.examples.eino.quickstart.ChatExample"

# Batch
sbt "adk4s-examples/runMain org.adk4s.examples.eino.batch.BatchExample"

# New Agent Patterns
sbt "adk4s-examples/runMain org.adk4s.examples.eino.agent.ReactAgentExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.agent.DynamicOptionExample"

# New Graphs
sbt "adk4s-examples/runMain org.adk4s.examples.eino.graph.ReactWithInterruptExample"

# New Workflows
sbt "adk4s-examples/runMain org.adk4s.examples.eino.workflow.FieldMappingWorkflowExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.workflow.DataOnlyWorkflowExample"
sbt "adk4s-examples/runMain org.adk4s.examples.eino.workflow.StreamFieldMapExample"

# New Components
sbt "adk4s-examples/runMain org.adk4s.examples.eino.components.ToolSchemaExample"
```

### Using a Real LLM

```bash
export OPENAI_API_KEY="your-key"
export LLM_MODEL="gpt-4o-mini"           # optional, default: gpt-4o-mini
export OPENAI_BASE_URL="https://api.openai.com/v1"  # optional
sbt "adk4s-examples/runMain org.adk4s.examples.eino.components.ChatModelExample"
```

## Structured Examples

These examples demonstrate type-safe LLM response parsing and tool execution using the Structured LLM/ToolCall APIs. All examples support both mock and real LLM backends.

### Structured LLM (Type-Safe Response Parsing)

The StructuredLLM API provides type-safe parsing of LLM responses into Scala case classes using Smithy schemas. It automatically injects schema definitions into prompts and uses the Schema-Aligned Parser (SAP) to recover from common LLM output errors.

#### Classification Examples

| Example | Description | Run Command |
|---------|-------------|-------------|
| `CategoryClassificationStructuredExample` | Classify queries into categories (math/science/history) with confidence scores | `./run-example.sh categoryclassification` |
| `RoleDetectionStructuredExample` | Detect user roles (customer/support/manager) in service contexts | `./run-example.sh roledetection` |
| `QueryClassificationStructuredExample` | Classify query types (question/command/statement) and extract intent | `./run-example.sh queryclassification` |
| `ChainRouteStructuredExample` | Route tasks to processing chains with routing rationale | `./run-example.sh chainroute` |

#### Extraction Examples

| Example | Description | Run Command |
|---------|-------------|-------------|
| `PlanExecuteStructuredExample` | Extract structured plans with numbered steps and durations | `./run-example.sh planextraction` |
| `StepsExtractionStructuredExample` | Extract step lists with optional metadata from instructions | `./run-example.sh stepsextraction` |
| `ListParsingStructuredExample` | Parse various list formats (numbered/bulleted/embedded) | `./run-example.sh listparsing` |
| `SchemaExtractionStructuredExample` | Extract complex nested structures with optional fields | `./run-example.sh schemaextraction` |

#### Multi-Agent Examples

| Example | Description | Run Command |
|---------|-------------|-------------|
| `SpecialistDelegationStructuredExample` | Delegate tasks to specialist agents with rationale | `./run-example.sh specialistdelegation` |
| `MultiAgentHostStructuredExample` | Host agent coordinates multiple specialists | `./run-example.sh multiagenthostex` |

#### Chain Examples

| Example | Description | Run Command |
|---------|-------------|-------------|
| `TypedIntermediatesStructuredExample` | Multi-stage pipeline with typed intermediate results | `./run-example.sh typedintermediates` |
| `ChainCompositionStructuredExample` | Compose multiple parsers in sequence | `./run-example.sh chaincomposition` |
| `TransformChainStructuredExample` | Four-stage ETL transformation pipeline | `./run-example.sh transformchain` |

#### Workflow Examples

| Example | Description | Run Command |
|---------|-------------|-------------|
| `GraphIntegrationStructuredExample` | Use StructuredLLM within WIOGraph nodes for orchestrated workflows | `./run-example.sh graphintegration` |
| `AsyncNodeStructuredExample` | Async streaming with StructuredLLM in graph nodes | `./run-example.sh asyncnodestructured` |

#### Error Recovery

| Example | Description | Run Command |
|---------|-------------|-------------|
| `SAPErrorRecoveryStructuredExample` | Demonstrate SAP recovery from malformed JSON (markdown fences, trailing commas, single quotes) | `./run-example.sh saperrorrecovery` |

### Structured ToolCall (Type-Safe Tool Execution)

The StructuredToolCall API provides compile-time type safety for tool definition and execution. Tools are defined with typed input/output using `StructuredToolCall.createTool`, enabling full type checking across the tool execution pipeline.

#### Key Features

- **Compile-time type safety**: Input/output types checked at compile time
- **Automatic schema derivation**: `ToolSchema.derive` generates schemas from case classes
- **Type-safe execution**: `TypedTool[F, I, O]` ensures correct argument/result types
- **Registry compatibility**: Convert to `InvokableTool` for use with existing systems

#### Examples

| Example | Description | Run Command |
|---------|-------------|-------------|
| `ReactAgentStructuredExample` | ReAct agent with typed tools (calculator, search, weather) | `./run-example.sh reactagentstructured` |
| `DynamicToolRegistryStructuredExample` | Dynamic tool creation and management with type safety | `./run-example.sh dynamictoolregistry` |
| `WIOGraphToolStructuredExample` | Execute typed tools within WIOGraph workflow nodes | `./run-example.sh wiographtool` |

### Schema-Aligned Parser (SAP)

The Schema-Aligned Parser implements "liberal in what you accept" parsing with automatic error recovery:

- **Markdown fence extraction**: Extracts JSON from ` ```json ... ``` ` blocks
- **Trailing comma removal**: Handles `{"key": "value",}`
- **Quote normalization**: Converts `'key'` to `"key"`
- **Unquoted key fixing**: Adds quotes to `{key: "value"}`
- **Comment stripping**: Removes `// comments` from JSON

All recovery attempts are tracked as warnings, and the final result is validated against the Smithy schema.

### Running Structured Examples

**With Mock LLM (default):**
```bash
./run-example.sh categoryclassification --mock
./run-example.sh reactagentstructured --mock
```

**With Real LLM:**
```bash
export OPENAI_API_KEY="your-key"
export LLM_MODEL="gpt-4o-mini"  # optional
./run-example.sh categoryclassification
./run-example.sh reactagentstructured
```

### Schema Design

Schemas are defined in Smithy IDL and compiled via smithy4s. The Smithy definition is injected directly into prompts (80% more compact than JSON Schema).

**Example Schema:**
```smithy
structure CategoryClassification {
    @required
    category: String
    @required
    confidence: Double
}
```

**Usage in Code:**
```scala
given Schema[CategoryClassification] = Schema.instance(
  """structure CategoryClassification {
    |  @required
    |  category: String
    |  @required
    |  confidence: Double
    |}""".stripMargin
)(using summon[smithy4s.schema.Schema[CategoryClassification]])

val structured = StructuredLLM.fromClient[IO](llmClient)
val result: IO[CategoryClassification] = structured.complete(prompt)
```

## Contributing

To add new examples:
1. Create a new package under `org.adk4s.examples`
2. Follow the established patterns
3. Include comprehensive documentation
4. Add tests where appropriate
5. Update this README

## Resources

- [ADK4S Core Documentation](../../adk4s-core/README.md)
- [Orchestration Guide](../../adk4s-orchestration/README.md)
- [Workflows4s Documentation](../../../business4s/workflows4s/README.md)
- [LLM4S Documentation](../../../llm4s/llm4s/README.md)
