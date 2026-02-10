## Context
The eino-examples convertibility report analyzed 26 Eino examples and found 19 directly convertible to adk4s. Only 1 (`compose/chain`) is currently implemented. This change implements the remaining 18 convertible examples, organized into 5 phases with a prerequisite infrastructure phase.

The examples exercise the full stack: adk4s-core (Lambda, ChatModel, ChatTemplate, Runnable, streaming), adk4s-orchestration (Graph, WIOGraph, WIOLoopNode, WIOForkNode, WIOPureNode, WIORunIONode, WIOHandleSignalNode), and llm4s (Agent, MemoryManager, RAG, Handoff, PlanRunner, TypedAgent).

## Goals / Non-Goals
- Goals:
  - Implement all 18 remaining convertible Eino examples as runnable Scala programs
  - Each example must work with a MockChatModel (no API key required) and optionally with a real LLM
  - Each example must be self-contained in a single file (or small package) under `adk4s-examples`
  - Phase 0 infrastructure (WIORunnableNode) must have unit tests
  - Examples should follow existing patterns in `adk4s-examples` (IOApp.Simple, MockChatModel fallback)
  - Document each example with a header comment explaining the Eino equivalent and what it demonstrates

- Non-Goals:
  - 100% behavioral parity with Eino (Go idioms don't translate 1:1 to Scala)
  - Mermaid visualization (adk4s uses BPMN via workflows4s)
  - Eino callback/tracing system (adk4s has its own GraphCallback)
  - Implementing the 2 unconvertible examples (stream field mapping, batch execution)
  - Implementing the 5 partially convertible examples (react_with_interrupt, field_mapping workflow, data_only workflow, streaming react agent, tool schema inference)

## Decisions

### Decision 1: All examples in `adk4s-examples` module under `org.adk4s.examples.eino`
**What**: Place all new examples under `adk4s-examples/src/main/scala/org/adk4s/examples/eino/` organized by category.
**Why**: Keeps examples separate from the existing chain examples, makes the Eino mapping explicit, easy to find.
**Alternatives considered**: Separate module per category (rejected — too many modules), mix with existing examples (rejected — confusing).

### Decision 2: MockChatModel with optional real LLM
**What**: Every example uses a `MockChatModel` by default. If `OPENAI_API_KEY` is set, use a real LLM client.
**Why**: Examples must be runnable without API keys for CI and quick testing. Real LLM support validates end-to-end behavior.
**Alternatives considered**: Always require API key (rejected — bad DX), only mock (rejected — can't validate real behavior).

### Decision 3: Phase 0 WIORunnableNode as separate infrastructure task
**What**: Implement WIORunnableNode, WIOGraph.addRunnableNode, and WIOGraphStreamExecutor before the streaming examples.
**Why**: Three examples (graph/state, graph/tool_call_once, graph/async_node) require streaming graph execution. This infrastructure is also valuable beyond examples.
**Alternatives considered**: Skip streaming examples (rejected — loses 3 examples), implement inline (rejected — infrastructure deserves proper tests).

### Decision 4: llm4s integration via direct dependency, not wrappers
**What**: Examples that use llm4s features (RAG, Memory, Orchestration) call llm4s APIs directly.
**Why**: adk4s already depends on llm4s. Creating wrapper abstractions would be premature — the examples help discover what wrappers are actually needed.
**Alternatives considered**: Create adk4s wrapper traits first (rejected — YAGNI, examples inform wrapper design).

### Decision 5: WIOGraph for workflow examples instead of completing Workflow.compile
**What**: Workflow examples 1, 4, 5 use WIOGraph as the execution engine, not the unfinished Workflow API.
**Why**: WIOGraph already works and provides event sourcing, BPMN visualization, loops, signals. Completing Workflow.compile is a separate effort.
**Alternatives considered**: Complete Workflow.compile first (rejected — large scope, orthogonal to examples).

## Risks / Trade-offs

- **Risk**: llm4s API changes break examples
  - Mitigation: Pin llm4s version, add compile-only CI check for examples module

- **Risk**: WIORunnableNode design doesn't fit all streaming patterns
  - Mitigation: Start with graph/state (simplest streaming case), iterate design before tackling tool_call_once and async_node

- **Trade-off**: MockChatModel responses are simplistic
  - Rationale: Mocks prove the wiring works; real LLM testing is manual/optional

- **Risk**: Phase 4 agent examples need llm4s Agent class which uses synchronous Either API
  - Mitigation: Wrap in IO.fromEither or IO.blocking as needed; document the sync/async boundary

- **Trade-off**: Examples use `var` / mutable state in MockChatModel for conversation tracking
  - Rationale: Mock is test infrastructure, not production code. Keep it simple.

## File Structure

```
adk4s-examples/src/main/scala/org/adk4s/examples/eino/
├── common/
│   ├── MockChatModel.scala          # Shared mock (reuse existing or extend)
│   └── ExampleUtils.scala           # Shared helpers (LLM client creation, printing)
├── components/
│   ├── LambdaExample.scala          # components/lambda
│   ├── ChatModelExample.scala       # components/model
│   ├── ChatTemplateExample.scala    # components/prompt
│   ├── DocumentLoaderExample.scala  # components/document (llm4s RAG)
│   └── RetrieverExample.scala       # components/retriever (llm4s RAG)
├── graph/
│   ├── SimpleGraphExample.scala     # graph/simple
│   ├── ToolCallAgentExample.scala   # graph/tool_call_agent
│   ├── TwoModelChatExample.scala    # graph/two_model_chat (WIOLoopNode)
│   ├── StateGraphExample.scala      # graph/state (WIORunnableNode)
│   ├── ToolCallOnceExample.scala    # graph/tool_call_once (stream fork)
│   └── AsyncNodeExample.scala       # graph/async_node (WIORunnableNode)
├── workflow/
│   ├── SimpleWorkflowExample.scala  # workflow/1_simple (WIOGraph)
│   ├── BranchWorkflowExample.scala  # workflow/4_control_only_branch (WIOForkNode)
│   └── StaticValuesExample.scala    # workflow/5_static_values (WIOPureNode)
├── agent/
│   ├── ReactMemoryExample.scala     # flow/agent/react/memory (llm4s Agent)
│   ├── MultiAgentHostExample.scala  # flow/agent/multiagent/host (llm4s Handoff)
│   └── PlanExecuteExample.scala     # flow/agent/multiagent/plan_execute (llm4s PlanRunner)
└── quickstart/
    └── ChatExample.scala            # quickstart/chat

adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/
├── WIORunnableNode.scala            # New node type (Phase 0)
└── WIOGraphStreamExecutor.scala     # New executor (Phase 0)

adk4s-orchestration/src/test/scala/org/adk4s/orchestration/wiograph/
├── WIORunnableNodeTest.scala        # Unit tests (Phase 0)
└── WIOGraphStreamExecutorTest.scala # Unit tests (Phase 0)
```

## Eino → adk4s Mapping Reference

| Eino Concept | adk4s Equivalent | Package |
|---|---|---|
| `InvokableLambda` | `Lambda` / `Runnable.fromInvoke` | `adk4s-core` |
| `StreamableLambda` | `Runnable.fromStream` | `adk4s-core` |
| `TransformableLambda` | `Runnable.fromTransform` | `adk4s-core` |
| `ChatTemplate` | `ChatTemplate` | `adk4s-core` |
| `ChatModel` | `ChatModel[IO]` | `adk4s-core` |
| `ToolsNode` | `ToolsNode` | `adk4s-core` |
| `Graph` | `Graph` / `WIOGraph` | `adk4s-orchestration` |
| `Graph.Branch` | `WIOForkNode` / `ForkSpec` | `adk4s-orchestration` |
| `Graph loop` | `WIOLoopNode` | `adk4s-orchestration` |
| `Graph state` | `StateHandlers` / `StatefulNode` | `adk4s-orchestration` |
| `Workflow` | `WIOGraph` (alternative) | `adk4s-orchestration` |
| `react.NewAgent` | llm4s `Agent` | `llm4s` |
| `Memory` | llm4s `MemoryManager` + `MemoryStore` | `llm4s` |
| `host.Host` | llm4s `Handoff` + `DAG` + `PlanRunner` | `llm4s` |
| `DocumentLoader` | llm4s `DocumentLoader` | `llm4s` |
| `Retriever` | llm4s `VectorStore` + `HybridSearcher` | `llm4s` |
| `Mermaid` | BPMN via `workflows4s-bpmn` | `workflows4s` |
| `Callbacks` | `GraphCallback` | `adk4s-orchestration` |

## Open Questions
- Should Phase 0 WIORunnableNode support error types (Either[Err, O]) or only infallible (Nothing)?
- Should agent examples (Phase 4) use llm4s directly or create thin adk4s wrappers?
- Should we add a shared `EinoExampleRunner` that lists and runs all examples interactively?
