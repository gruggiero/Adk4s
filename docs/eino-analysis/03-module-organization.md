# Eino Framework Analysis - Module Organization

## Module Structure

```
eino/
├── schema/              # Core data types - NO dependencies on other eino packages
├── components/          # Component interfaces - depends on schema
├── compose/             # Orchestration - depends on schema, components, callbacks
├── callbacks/           # Aspect system - depends on schema, internal
├── flow/                # Pre-built flows - depends on all above
├── internal/            # Internal utilities - minimal dependencies
└── utils/               # Public utilities
```

## Module Boundaries

### 1. schema/ - Core Data Types

**Purpose**: Define fundamental data structures used across the framework.

**Key Files**:
- `message.go` - Message, ToolCall, RoleType, MessageTemplate
- `stream.go` - StreamReader, StreamWriter, Pipe
- `document.go` - Document for RAG
- `tool.go` - ToolInfo, ParameterInfo, DataType
- `serialization.go` - JSON serialization helpers

**Dependencies**: Only external libraries (gonja, pyfmt for templating)

**Boundary Rules**:
- No imports from other eino packages
- Pure data structures and utilities
- Serialization/deserialization logic

### 2. components/ - Component Abstractions

**Purpose**: Define interfaces for all pluggable components.

**Submodules**:
```
components/
├── model/       # BaseChatModel, ChatModel, ToolCallingChatModel
├── tool/        # BaseTool, InvokableTool, StreamableTool
├── prompt/      # ChatTemplate
├── retriever/   # Retriever
├── embedding/   # Embedder
├── indexer/     # Indexer
├── document/    # Loader, Transformer
└── types.go     # Component type constants
```

**Dependencies**: schema only

**Boundary Rules**:
- Only interface definitions
- Option types for each component
- Callback input/output types
- No implementations (those live in eino-ext)

### 3. compose/ - Orchestration Engine

**Purpose**: Graph-based composition and execution.

**Key Files**:
- `graph.go` - Graph[I, O] definition and node management
- `chain.go` - Chain[I, O] builder
- `workflow.go` - Workflow[I, O] with field mapping
- `runnable.go` - Runnable interface and implementations
- `state.go` - State management
- `tool_node.go` - ToolsNode for tool execution
- `branch.go` - Conditional branching
- `types_lambda.go` - Lambda node types
- `error.go` - Error types
- `graph_run.go` - Execution engine
- `checkpoint.go` - Checkpointing support
- `interrupt.go` - Interrupt/resume support

**Dependencies**: schema, components, callbacks, internal

**Boundary Rules**:
- Orchestration logic only
- No specific component implementations
- Type validation at compile time
- Stream processing utilities

### 4. callbacks/ - Aspect System

**Purpose**: Cross-cutting concerns via callback handlers.

**Key Files**:
- `interface.go` - Handler, RunInfo, CallbackInput/Output
- `handler_builder.go` - HandlerBuilder for creating handlers
- `aspect_inject.go` - Automatic aspect injection

**Dependencies**: internal/callbacks

**Boundary Rules**:
- Handler interfaces and builders
- No component-specific logic
- Timing definitions (OnStart, OnEnd, etc.)

### 5. flow/ - Pre-built Flows

**Purpose**: Ready-to-use agent patterns.

**Submodules**:
```
flow/
├── agent/
│   ├── react/      # ReAct agent implementation
│   └── multiagent/ # Multi-agent patterns
├── retriever/      # Retrieval flows
└── indexer/        # Indexing flows
```

**Dependencies**: All other modules

**Boundary Rules**:
- High-level compositions
- Opinionated defaults
- Can be used as examples

### 6. internal/ - Internal Utilities

**Purpose**: Shared utilities not part of public API.

**Submodules**:
```
internal/
├── callbacks/   # Core callback implementation
├── generic/     # Generic type utilities
├── gmap/        # Map utilities
├── gslice/      # Slice utilities
├── safe/        # Panic recovery
├── mock/        # Test mocks
└── serialization/
```

**Boundary Rules**:
- Not exported
- Can change without notice
- Shared implementation details

---

## Scala 3 Module Mapping

### Proposed ADK4S Structure (Using LLM4S, Workflows4s, structured-llm)

```
adk4s/
├── structured-llm/          # Current module - Type-safe structured LLM outputs
│   ├── core/                # StructuredLLM, Prompt, Schema, PromptTemplate
│   ├── sap/                 # SchemaAlignedParser for lenient JSON parsing
│   └── template/            # PromptSyntax extensions
│
├── agent/                   # Agent orchestration (builds on LLM4S Agent)
│   ├── react/               # ReAct agent patterns
│   └── multi/               # Multi-agent coordination
│
├── workflow/                # Workflow orchestration (builds on Workflows4s)
│   ├── llm/                 # LLM-specific workflow steps
│   └── agent/               # Agent workflows with checkpointing
│
├── tools/                   # Tool definitions (extends LLM4S ToolRegistry)
│   ├── builtin/             # Built-in tools (web search, file ops, etc.)
│   └── smithy/              # Smithy-generated tool schemas
│
└── ext/                     # External integrations
    ├── openai/              # OpenAI-specific extensions
    ├── anthropic/           # Anthropic-specific extensions
    └── rag/                 # RAG components (retriever, indexer)
```

### Dependency Mapping to ADK4S Modules

| Eino Module | ADK4S Module | Primary Dependency |
|-------------|--------------|-------------------|
| `schema/` | Use LLM4S types directly | **LLM4S** (`Message`, `Conversation`, `ToolCall`) |
| `components/model/` | Use LLM4S `LLMClient` | **LLM4S** |
| `components/tool/` | Use LLM4S `ToolFunction`, `ToolRegistry` | **LLM4S** |
| `components/prompt/` | `structured-llm/core/` | **structured-llm** (`Prompt`, `PromptTemplate`) |
| `compose/` | `workflow/` | **Workflows4s** (`WIO`, `WorkflowContext`) |
| `flow/agent/react/` | `agent/react/` | **LLM4S** (`Agent`, `AgentState`) |
| `callbacks/` | Workflows4s hooks + LLM4S tracing | **Workflows4s** + **LLM4S** |

### Dependency Graph (ADK4S with LLM4S, Workflows4s, structured-llm)

```
                    ┌─────────────────────────────────────────┐
                    │           External Dependencies         │
                    │  ┌─────────┐ ┌───────────┐ ┌─────────┐  │
                    │  │  LLM4S  │ │Workflows4s│ │Smithy4s │  │
                    │  └────┬────┘ └─────┬─────┘ └────┬────┘  │
                    └───────┼────────────┼────────────┼───────┘
                            │            │            │
              ┌─────────────┼────────────┼────────────┼─────────────┐
              │             │            │            │             │
              ▼             ▼            ▼            ▼             │
        ┌───────────────────────────────────────────────────┐      │
        │                  structured-llm                    │      │
        │  (StructuredLLM, Prompt, Schema, SAP)             │      │
        └──────────────────────┬────────────────────────────┘      │
                               │                                    │
              ┌────────────────┼────────────────┐                  │
              │                │                │                  │
              ▼                ▼                ▼                  │
        ┌──────────┐    ┌──────────┐    ┌──────────┐              │
        │  agent/  │    │ workflow/│    │  tools/  │              │
        │  (ReAct) │    │  (WIO)   │    │(Registry)│              │
        └────┬─────┘    └────┬─────┘    └────┬─────┘              │
             │               │               │                     │
             └───────────────┼───────────────┘                     │
                             │                                     │
                             ▼                                     │
                       ┌──────────┐                                │
                       │   ext/   │◄───────────────────────────────┘
                       │(OpenAI,  │
                       │Anthropic)│
                       └──────────┘
```

### Key Dependency Relationships

| ADK4S Module | Depends On | Provides |
|--------------|------------|----------|
| `structured-llm` | LLM4S, Smithy4s | `StructuredLLM[F]`, `Schema[A]`, `Prompt`, `SchemaAlignedParser` |
| `agent/` | LLM4S (Agent, ToolRegistry) | ReAct patterns, multi-agent coordination |
| `workflow/` | Workflows4s (WIO, WorkflowContext) | LLM workflow steps, checkpointing |
| `tools/` | LLM4S (ToolFunction, ToolBuilder) | Built-in tools, Smithy-generated schemas |
| `ext/` | All above | Provider-specific implementations |

### SBT Module Definition (Using LLM4S, Workflows4s, structured-llm)

```scala
// External dependencies (local projects per project.md)
lazy val llm4s = ProjectRef(file("../llm4s"), "core")
lazy val workflows4s = ProjectRef(file("../workflows4s"), "workflows4s-core")

lazy val structuredLlm = project
  .in(file("structured-llm"))
  .dependsOn(llm4s)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % smithy4sVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-json" % smithy4sVersion,
    )
  )

lazy val agent = project
  .in(file("agent"))
  .dependsOn(structuredLlm, llm4s)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
    )
  )

lazy val workflow = project
  .in(file("workflow"))
  .dependsOn(structuredLlm, workflows4s)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
    )
  )

lazy val tools = project
  .in(file("tools"))
  .dependsOn(llm4s)
  .settings(
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % smithy4sVersion,
    )
  )

lazy val ext = project
  .in(file("ext"))
  .dependsOn(structuredLlm, agent, workflow, tools)
  .settings(...)
```

---

## Key Design Decisions

### 1. Schema Independence

Eino's `schema` package has no internal dependencies, making it:
- Easy to share across modules
- Stable API surface
- Testable in isolation

**ADK4S with LLM4S**: LLM4S already provides independent schema types (`Message`, `Conversation`, `ToolCall`, `Completion`). These types have no internal dependencies and can be used directly across all ADK4S modules. No need to redefine schema types - use LLM4S types as the foundation.

### 2. Interface Segregation

Components define minimal interfaces:
- `BaseChatModel` - just Generate and Stream
- `BaseTool` - just Info
- Extensions add capabilities (ToolCallingChatModel adds WithTools)

**ADK4S with LLM4S**: LLM4S follows the same principle:
- `LLMClient` - just `complete` and `completeStreamed`
- `ToolFunction` - minimal tool interface with `info` and `handler`
- Extensions via composition: wrap `LLMClient` with `StructuredLLM` for type-safe outputs

### 3. Composition Over Inheritance

- No deep inheritance hierarchies
- Components composed via Graph/Chain
- Lambdas for custom logic

**ADK4S with Workflows4s**: Workflows4s `WIO` is designed for composition:
- `WIO.flatMap` / `>>>` for sequential composition
- `WIO.parallel` for parallel execution
- `WIO.fork` for branching
- No inheritance needed - pure functional composition

### 4. Implementation Separation

- Core framework in `eino`
- Implementations in `eino-ext`
- Examples in `eino-examples`

**ADK4S with Dependencies**: The separation is already achieved through dependencies:
- **LLM4S** provides LLM client abstractions (OpenAI, Anthropic, Azure already supported)
- **Workflows4s** provides workflow orchestration (runtime implementations included)
- **structured-llm** provides ADK4S-specific structured output layer
- **ext/** module for ADK4S-specific extensions and integrations
