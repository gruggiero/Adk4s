# Eino Examples → adk4s Convertibility Report

## Executive Summary

This report analyzes all examples in the `eino-examples` repository and classifies them by convertibility to adk4s. Out of **25 distinct examples** across 5 categories, **6 are directly convertible**, **7 are partially convertible** (requiring minor additions), and **12 require significant new features**.

---

## adk4s Current Feature Inventory

Before analyzing examples, here is what adk4s currently supports:

| Feature | Module | Status |
|---------|--------|--------|
| Graph with typed nodes and edges | `adk4s-orchestration/graph` | ✅ Implemented |
| Lambda nodes (pure functions) | `adk4s-core/runnable/Lambda` | ✅ Implemented |
| ChatModel node (invoke) | `adk4s-core/component/ChatModel` | ✅ Implemented |
| ToolsNode (tool execution) | `adk4s-core/tools/ToolsNode` | ✅ Implemented |
| Branching / Fork | `adk4s-orchestration/branch`, `fork` | ✅ Implemented |
| Parallel execution | `adk4s-orchestration/wiograph` | ✅ Implemented |
| Merge nodes | `adk4s-orchestration/graph` | ✅ Implemented |
| Sub-graph nodes | `adk4s-orchestration/graph` | ✅ Implemented |
| WIO graph (workflows4s integration) | `adk4s-orchestration/wiograph` | ✅ Implemented |
| BPMN generation | via `workflows4s-bpmn` | ✅ Implemented |
| Structured tool calls | `adk4s-core/tools/StructuredToolCall` | ✅ Implemented |
| Tool middleware | `adk4s-core/tools/ToolMiddleware` | ✅ Implemented |
| Streaming (fs2-based) | `adk4s-core/streaming` | ✅ Implemented |
| Graph execution | `adk4s-orchestration/execution/GraphExecutor` | ✅ Implemented |
| Workflow (field mapping) | `adk4s-orchestration/workflow` | ⚠️ Skeleton only (compile raises UnsupportedOperationException) |
| ChatTemplate / Prompt template | `adk4s-core/component/ChatTemplate` | ✅ Implemented |
| State pre/post handlers | `adk4s-orchestration/state` | ✅ Implemented |
| Callbacks system | `adk4s-orchestration/execution/GraphCallback` | ✅ Implemented |
| Checkpoint / Resume | `adk4s-orchestration/interrupt/CheckpointStore` | ✅ Implemented |
| Human-in-the-loop interrupt | `adk4s-orchestration/interrupt/InterruptibleNode` | ✅ Implemented |
| ReAct agent flow | `adk4s-orchestration/agent/ReactAgent` | ✅ Implemented |
| Multi-agent orchestration | — | ❌ Not implemented |
| Streaming graph branching | — | ❌ Not implemented |
| Batch execution | `adk4s-core/batch/BatchExecutor` | ✅ Implemented |
| Mermaid visualization | — | ❌ Not implemented (BPMN only) |

---

## Category 1: `compose/chain` — Chain Example

### `compose/chain/main.go`
**Eino features used**: Chain composition, Lambda nodes, Branch (conditional), Parallel, ChatTemplate, ChatModel, Passthrough, Mermaid visualization, Callbacks (CozeLoop tracing)

**Convertibility: ✅ DIRECTLY CONVERTIBLE**

This is the exact example already ported to adk4s as `WIOGraphChainExample`. It demonstrates:
- Sequential chain of lambda → branch → parallel → chat model → output
- Conditional branching (cat/dog role selection)
- Parallel execution (role + input extraction)
- ChatModel invocation

**adk4s equivalent**: `WIOGraphChainExample.scala`, `WIOGraphWorkflowExample.scala`

---

## Category 2: `compose/graph` — Graph Examples

### 2.1 `compose/graph/simple/graph.go`
**Eino features used**: Graph, ChatTemplate node, ChatModel node, edges, Mermaid visualization, Stream mode

**Convertibility: ✅ DIRECTLY CONVERTIBLE**

Simple graph: START → Prompt Template → ChatModel → END. adk4s has `Graph.addChatModelNode()` and `ChatTemplate`. The only gap is streaming mode (invoke works, stream would need the streaming graph executor).

**Missing for full parity**: Streaming graph execution (invoke mode works today)

---

### 2.2 `compose/graph/state/state_graph.go`
**Eino features used**: Graph with local state (`WithGenLocalState`), State pre/post handlers (`WithStatePreHandler`, `WithStatePostHandler`), InvokableLambda, StreamableLambda, TransformableLambda, Stream mode, Transform mode

**Convertibility: ⚠️ PARTIALLY CONVERTIBLE**

adk4s has `StateHandlers` and `StatefulNode` in the orchestration module. The invoke path with state pre/post handlers is supported. However:

**Missing features**:
- **StreamableLambda**: adk4s has fs2 streaming but no `StreamableLambda` node type in the graph
- **TransformableLambda**: Stream-to-stream transformation node type not available in graph
- **Transform mode**: Graph `Transform(ctx, streamReader)` execution mode not implemented

---

### 2.3 `compose/graph/tool_call_agent/tool_call_agent.go`
**Eino features used**: Graph, ChatTemplate, ChatModel with `BindForcedTools`, ToolsNode, tool definition with schema inference, Callbacks

**Convertibility: ✅ DIRECTLY CONVERTIBLE**

Linear graph: Template → ChatModel → ToolsNode → END. adk4s has all required components: `Graph.addChatModelNode()`, `Graph.addToolsNode()`, `StructuredToolCall`, and `ToolsNode`.

**Note**: adk4s uses `ToolCallingChatModel` trait for tool binding. The tool schema definition approach differs (adk4s uses `ToolSchema` typeclass vs eino's runtime schema maps), but the functionality is equivalent.

---

### 2.4 `compose/graph/tool_call_once/tool_call_once.go`
**Eino features used**: Graph, ChatTemplate, ChatModel, ToolsNode, **Stream-based branching** (`NewStreamGraphBranch`), Lambda converter node

**Convertibility: ⚠️ PARTIALLY CONVERTIBLE**

This example adds conditional branching after the ChatModel: if the model returns tool calls, route to ToolsNode; otherwise route to END. The branching logic inspects a **stream** to decide the route.

**Missing features**:
- **Stream-based graph branching** (`NewStreamGraphBranch`): adk4s `ForkSpec` and `WIOForkNode` branch on invoke values, not on streams. A stream-aware branch that consumes the stream to decide routing is not implemented.

---

### 2.5 `compose/graph/two_model_chat/two_model_chat.go`
**Eino features used**: Graph with local state, two ChatModel nodes (writer + critic), **looping** (writer → critic → writer cycle), Stream-based branching with state access (`ProcessState`), `ToList` converter, Callbacks with stream output

**Convertibility: ⚠️ PARTIALLY CONVERTIBLE**

This is a multi-turn writer/critic loop. The graph cycles: writer → toList → critic → toList → writer, with a branch after writer that checks round count via state.

**Missing features**:
- **Graph-level looping/cycles**: adk4s graphs are DAGs. WIO supports loops via `WIOModel.Loop`, but the Graph API doesn't expose cycle creation. The WIO loop requires a condition check, not a stream-based branch.
- **Stream-based branching with state access**: `ProcessState` inside a stream branch callback
- **`ToList` converter node**: Built-in node that wraps a single message into a list

---

### 2.6 `compose/graph/react_with_interrupt/main.go`
**Eino features used**: Graph with local state, ChatTemplate, ChatModel, ToolsNode, **graph branching** (tool calls → ToolsNode, else → END), **looping** (ToolsNode → ChatModel cycle), **CheckPointStore** (serialization/resume), **InterruptBeforeNodes** (human-in-the-loop), **StateModifier**

**Convertibility: ❌ NOT CONVERTIBLE**

This is a ReAct agent with human-in-the-loop interrupt. Before executing tools, execution pauses, the user can inspect/modify tool call arguments, then resume.

**Missing features**:
- **CheckPointStore**: Serialization of graph execution state for pause/resume
- **InterruptBeforeNodes**: Ability to pause execution before specific nodes
- **StateModifier**: Ability to modify state on resume
- **Graph cycles**: ToolsNode → ChatModel loop
- **ExtractInterruptInfo**: Error-based interrupt signaling mechanism

---

### 2.7 `compose/graph/async_node/main.go`
**Eino features used**: Graph, InvokableLambda (async with channels/goroutines), StreamableLambda (live transcription), `StreamReaderWithConvert`, Stream mode

**Convertibility: ⚠️ PARTIALLY CONVERTIBLE**

The invoke path (async lambda that waits on a channel) maps to adk4s `Lambda` with `IO.async` or `IO.fromFuture`. The streaming path requires StreamableLambda.

**Missing features**:
- **StreamableLambda in graph**: A node that returns a stream from an invoke input
- **StreamReaderWithConvert**: Stream transformation/conversion utility

---

## Category 3: `compose/workflow` — Workflow Examples

### 3.1 `compose/workflow/1_simple/main.go`
**Eino features used**: Workflow, Lambda node, `AddInput(START)`, `End().AddInput("node")`

**Convertibility: ❌ NOT CONVERTIBLE (runtime)**

adk4s has the `Workflow` skeleton in `adk4s-orchestration/workflow/Workflow.scala` but `compile` raises `UnsupportedOperationException`. The data model exists but execution is not implemented.

**Missing features**:
- **Workflow execution engine**: The `compile` method must produce a runnable

---

### 3.2 `compose/workflow/2_field_mapping/main.go`
**Eino features used**: Workflow, **field mapping** (`MapFields`, `MapFieldPaths`, `ToField`), parallel node execution via multiple `AddInput`

**Convertibility: ❌ NOT CONVERTIBLE**

**Missing features**:
- **Workflow execution engine**
- **Field mapping execution**: adk4s has `FieldMapping` and `FieldPath` types defined but no runtime that applies them

---

### 3.3 `compose/workflow/3_data_only/main.go`
**Eino features used**: Workflow, field mapping (`FromField`, `ToField`), **data-only dependencies** (`WithNoDirectDependency`)

**Convertibility: ❌ NOT CONVERTIBLE**

**Missing features**:
- **Workflow execution engine**
- **Data-only dependencies**: Separation of control flow from data flow

---

### 3.4 `compose/workflow/4_control_only_branch/main.go`
**Eino features used**: Workflow, branching, **control-only dependencies** (`AddDependency`), Mermaid visualization

**Convertibility: ❌ NOT CONVERTIBLE**

**Missing features**:
- **Workflow execution engine**
- **Control-only dependencies**: `AddDependency` for ordering without data passing

---

### 3.5 `compose/workflow/5_static_values/main.go`
**Eino features used**: Workflow, static value injection

**Convertibility: ❌ NOT CONVERTIBLE**

**Missing features**:
- **Workflow execution engine**
- **Static value injection**

---

### 3.6 `compose/workflow/6_stream_field_map/main.go`
**Eino features used**: Workflow, **stream field mapping**, TransformableLambda

**Convertibility: ❌ NOT CONVERTIBLE**

**Missing features**:
- **Workflow execution engine**
- **Stream field mapping**
- **TransformableLambda**

---

## Category 4: `compose/batch` — Batch Execution

### `compose/batch/main.go`
**Eino features used**: Batch execution of graphs/chains/workflows, parallel batch processing, error handling per batch item

**Convertibility: ❌ NOT CONVERTIBLE**

**Missing features**:
- **Batch execution API**: Ability to run a graph/chain over multiple inputs in parallel with per-item error handling

---

## Category 5: `flow/agent` — Agent Flows

### 5.1 `flow/agent/react/react.go`
**Eino features used**: **ReAct agent** (`react.NewAgent`), ToolCallingModel, ToolsNode, system prompt/persona, Stream mode, Callbacks, `ExportGraph` for visualization, custom `StreamToolCallChecker`

**Convertibility: ❌ NOT CONVERTIBLE**

**Missing features**:
- **ReAct agent flow**: Built-in ReAct loop (observe → think → act → observe cycle)
- **Agent abstraction**: `agent.Agent` interface with `Generate` and `Stream` methods
- **ExportGraph**: Ability to export agent's internal graph for visualization
- **StreamToolCallChecker**: Custom stream-based tool call detection

---

### 5.2 `flow/agent/react/memory_example/`
**Eino features used**: ReAct agent with **conversation memory**, message history management

**Convertibility: ❌ NOT CONVERTIBLE**

**Missing features**:
- All ReAct agent features above
- **Conversation memory**: Persistent message history across turns

---

### 5.3 `flow/agent/react/dynamic_option_example/`
**Eino features used**: ReAct agent with **dynamic options** (runtime configuration changes)

**Convertibility: ❌ NOT CONVERTIBLE**

**Missing features**:
- All ReAct agent features
- **Dynamic agent options**: Runtime reconfiguration of agent behavior

---

### 5.4 `flow/agent/multiagent/host/journal/`
**Eino features used**: **Multi-agent host** pattern, multiple specialist agents (read journal, write journal, answer with journal), host agent that delegates to specialists

**Convertibility: ❌ NOT CONVERTIBLE**

**Missing features**:
- **Multi-agent host pattern**: Coordinator agent that routes to specialist agents
- **Agent-as-tool**: Wrapping agents as tools for the host
- **Agent communication protocol**

---

### 5.5 `flow/agent/multiagent/plan_execute/`
**Eino features used**: **Plan-and-execute** multi-agent pattern, planner agent, executor agent, tool agents, state graph with planning loop

**Convertibility: ❌ NOT CONVERTIBLE**

**Missing features**:
- **Plan-and-execute pattern**: Planner creates steps, executor runs them
- **Multi-agent coordination**
- **Planning loop with state**

---

## Category 6: `components` — Component Examples

### 6.1 `components/lambda/`
**Convertibility: ✅ DIRECTLY CONVERTIBLE** — adk4s `Lambda` covers this

### 6.2 `components/model/`
**Convertibility: ✅ DIRECTLY CONVERTIBLE** — adk4s `ChatModel` covers this

### 6.3 `components/prompt/`
**Convertibility: ✅ DIRECTLY CONVERTIBLE** — adk4s `ChatTemplate` covers this

### 6.4 `components/tool/`
**Convertibility: ⚠️ PARTIALLY CONVERTIBLE** — adk4s `ToolsNode` and `StructuredToolCall` cover basic tool execution. Missing: tool schema inference from function signatures (eino's `utils.InferTool`)

### 6.5 `components/document/`
**Convertibility: ❌ NOT CONVERTIBLE** — Document loaders/transformers not implemented

### 6.6 `components/retriever/`
**Convertibility: ❌ NOT CONVERTIBLE** — Retriever interface exists (`adk4s-core/component/Retriever.scala`) but no implementations

---

## Category 7: `quickstart` and `adk`

### 7.1 `quickstart/chat/`
**Convertibility: ⚠️ PARTIALLY CONVERTIBLE** — Basic chat with ChatModel works. Multi-turn conversation management partially supported.

### 7.2 `quickstart/eino_assistant/`
**Convertibility: ❌ NOT CONVERTIBLE** — Full assistant with tools, memory, retrieval

### 7.3 `quickstart/todoagent/`
**Convertibility: ❌ NOT CONVERTIBLE** — Agent with persistent state and tool use

### 7.4 `adk/helloworld/`
**Convertibility: ❌ NOT CONVERTIBLE** — ADK-specific agent framework

### 7.5 `adk/multiagent/`
**Convertibility: ❌ NOT CONVERTIBLE** — Multi-agent ADK patterns

### 7.6 `adk/human-in-the-loop/`
**Convertibility: ❌ NOT CONVERTIBLE** — Interrupt/resume patterns

---

## Summary Table

| Example | Category | Convertible? | Missing Features |
|---------|----------|-------------|-----------------|
| `compose/chain` | Chain | ✅ Yes | — |
| `compose/graph/simple` | Graph | ✅ Yes | Streaming graph exec (minor) |
| `compose/graph/tool_call_agent` | Graph | ✅ Yes | — |
| `compose/graph/state` | Graph | ⚠️ Partial | StreamableLambda, TransformableLambda, Transform mode |
| `compose/graph/tool_call_once` | Graph | ⚠️ Partial | Stream-based graph branching |
| `compose/graph/two_model_chat` | Graph | ⚠️ Partial | Graph cycles/loops, stream branching with state |
| `compose/graph/async_node` | Graph | ⚠️ Partial | StreamableLambda in graph |
| `compose/graph/react_with_interrupt` | Graph | ❌ No | Checkpoint, interrupt, graph cycles |
| `compose/workflow/1_simple` | Workflow | ❌ No | Workflow execution engine |
| `compose/workflow/2_field_mapping` | Workflow | ❌ No | Workflow engine, field mapping runtime |
| `compose/workflow/3_data_only` | Workflow | ❌ No | Workflow engine, data-only deps |
| `compose/workflow/4_control_only_branch` | Workflow | ❌ No | Workflow engine, control-only deps |
| `compose/workflow/5_static_values` | Workflow | ❌ No | Workflow engine, static values |
| `compose/workflow/6_stream_field_map` | Workflow | ❌ No | Workflow engine, stream field mapping |
| `compose/batch` | Batch | ❌ No | Batch execution API |
| `flow/agent/react` | Agent | ❌ No | ReAct agent flow |
| `flow/agent/react/memory` | Agent | ❌ No | ReAct + conversation memory |
| `flow/agent/react/dynamic_option` | Agent | ❌ No | ReAct + dynamic options |
| `flow/agent/multiagent/host` | Agent | ❌ No | Multi-agent host pattern |
| `flow/agent/multiagent/plan_execute` | Agent | ❌ No | Plan-and-execute pattern |
| `components/lambda` | Component | ✅ Yes | — |
| `components/model` | Component | ✅ Yes | — |
| `components/prompt` | Component | ✅ Yes | — |
| `components/tool` | Component | ⚠️ Partial | Tool schema inference |
| `components/document` | Component | ❌ No | Document loaders |
| `components/retriever` | Component | ❌ No | Retriever implementations |

---

---

## Addendum: Revised Analysis Using LLM4S, Workflows4s, and WIOGraph

After examining the dependency documentation (`docs/dependencyDocs/`) and the `WIOGraph` / `WIONode` implementations in detail, several examples previously marked as "not convertible" can be re-classified. The key insight is that **WIOGraph already exposes most workflows4s primitives** (loops, signals, timers, parallel, forEach, checkpoints, retry, interruptions) and **llm4s provides a ReAct-like agent loop**.

### WIOGraph Feature Inventory (previously underestimated)

| WIOGraph Feature | WIONode Type | Workflows4s Primitive |
|---|---|---|
| Pure transformation | `WIOPureNode` | `WIO.Pure` |
| Side-effect with event sourcing | `WIORunIONode` | `WIO.RunIO` |
| Conditional branching | `WIOForkNode` | `WIO.Fork` |
| **Loops with stop condition** | `WIOLoopNode` | `WIO.Loop` |
| **Timer/await** | `WIOAwaitNode` | `WIO.Timer` |
| **Signal handling** | `WIOHandleSignalNode` | `WIO.HandleSignal` |
| **Parallel execution** | `WIOParallelNode` | `WIO.Parallel` |
| **ForEach over collections** | `WIOForEachNode` | `WIO.ForEach` |
| Sub-graph composition | `WIOSubGraphNode` | Nested `WIOGraph.toWIO` |
| **Checkpoint modifier** | `WIOGraph.withCheckpoint` | `WIO.checkpointed` |
| **Retry modifier** | `WIOGraph.withRetry` | `WIO.retry` |
| **Interruption modifier** | `WIOGraph.withInterruption` | `WIO.interruptWith` |

### LLM4S Agent Feature Inventory

| Feature | Component | Description |
|---|---|---|
| ReAct loop | `Agent.run` / `Agent.runStep` | Multi-step LLM reasoning with automatic tool execution |
| Tool registry | `ToolRegistry` | Registry-based tool execution with JSON schema |
| Conversation state | `AgentState` | Immutable conversation history management |
| Step-by-step execution | `Agent.runStep` | Manual control for human-in-the-loop patterns |
| Multi-agent coordination | Pattern in docs | Sequential multi-agent with shared query |
| Tracing | `Agent.writeTraceLog` | Markdown execution traces |

---

### Revised Example Classifications

#### `compose/graph/two_model_chat` — Writer/Critic Loop
**Previous**: ⚠️ Partial (missing graph cycles)
**Revised**: ✅ **CONVERTIBLE via WIOGraph**

`WIOLoopNode` directly supports cycles with a stop condition. The writer/critic loop can be modeled as:
- **Body**: writer → toList → critic → toList (a sub-graph or chained WIO)
- **Stop condition**: `state.currentRound >= 3`
- **Restart**: identity (feed output back as input)

The `WIONode.loop` or `WIONode.loopEither` factory methods handle this directly.

#### `compose/graph/react_with_interrupt` — ReAct with Human-in-the-Loop
**Previous**: ❌ Not convertible
**Revised**: ⚠️ **PARTIALLY CONVERTIBLE via WIOGraph + workflows4s signals**

Key capabilities now available:
- **Loop**: `WIOLoopNode` for the ChatModel → ToolsNode cycle
- **Signal handling**: `WIOHandleSignalNode` can pause execution and wait for external input (human approval)
- **Interruption**: `WIOGraph.withInterruption` attaches signal-based interruptions to nodes
- **Checkpoint**: `WIOGraph.withCheckpoint` provides event-sourced state persistence

**Still missing**: The specific pattern of "interrupt before a node, let user modify state, then resume" requires composing these primitives. The workflows4s `SignalDef` mechanism can model the human approval step, but the ergonomics differ from eino's `InterruptBeforeNodes` + `ExtractInterruptInfo` pattern. A thin adapter layer would be needed.

#### `flow/agent/react` — ReAct Agent
**Previous**: ❌ Not convertible
**Revised**: ⚠️ **PARTIALLY CONVERTIBLE via llm4s Agent**

The llm4s `Agent` class provides:
- ReAct loop: `Agent.run(initialState, maxSteps)` — observe → think → act cycle
- Tool execution: `ToolRegistry` with automatic tool call detection and execution
- Conversation memory: `AgentState.conversation` tracks full message history
- Step-by-step: `Agent.runStep` for manual iteration

**Gaps vs eino's react agent**:
- **Streaming**: llm4s Agent uses synchronous `Either`-based API, not streaming. Eino's `rAgent.Stream()` returns a stream of messages.
- **ExportGraph**: No graph export for visualization (llm4s agent is imperative, not graph-based)
- **Custom StreamToolCallChecker**: Not applicable (no streaming)

**Alternative**: Could also be built as a `WIOGraph` with `WIOLoopNode` (ChatModel → branch → ToolsNode → back to ChatModel), which would give BPMN visualization and event sourcing for free.

#### `flow/agent/react/memory_example` — ReAct with Memory
**Previous**: ❌ Not convertible
**Revised**: ⚠️ **PARTIALLY CONVERTIBLE via llm4s Agent**

`AgentState.conversation` already maintains full conversation history. The llm4s agent pattern supports multi-turn conversations by preserving `AgentState` across calls. The gap is the same as the base react agent (no streaming).

#### `flow/agent/multiagent/host/journal` — Multi-Agent Host
**Previous**: ❌ Not convertible
**Revised**: ⚠️ **PARTIALLY CONVERTIBLE via llm4s multi-agent pattern**

The llm4s docs show a `runMultipleAgents` pattern that runs multiple agents with different tool registries and system prompts. The host/specialist pattern can be approximated by:
1. A host agent that decides which specialist to invoke
2. Specialist agents with domain-specific tools and prompts
3. Sequential coordination via the host

**Gaps**: No built-in host/specialist framework — requires manual orchestration. Eino provides `host.Host` as a first-class abstraction.

#### `flow/agent/multiagent/plan_execute` — Plan-and-Execute
**Previous**: ❌ Not convertible
**Revised**: ⚠️ **PARTIALLY CONVERTIBLE via WIOGraph + llm4s Agent**

Could be modeled as a `WIOGraph` with:
- A planner node (llm4s Agent that produces a plan)
- A `WIOForEachNode` that iterates over plan steps
- An executor node (llm4s Agent that executes each step)
- A `WIOLoopNode` for re-planning if needed

**Gaps**: Requires significant manual wiring. No first-class plan-and-execute abstraction.

#### Workflow Examples (1-6) via WIOGraph
**Previous**: ❌ Not convertible (Workflow engine not implemented)
**Revised**: Mixed — WIOGraph can serve as an **alternative** to the Workflow API for most patterns

| Workflow Example | WIOGraph Alternative? | Notes |
|---|---|---|
| `1_simple` | ✅ Yes | `WIOPureNode` chain — trivially expressible |
| `2_field_mapping` | ⚠️ Partial | WIOGraph uses typed state transitions, not struct field mapping. The logic can be expressed via `WIOPureNode` transforms, but the field-mapping DSL is not available. |
| `3_data_only` | ⚠️ Partial | Data-only dependencies don't map to WIOGraph's sequential model. Can be expressed via `WIOParallelNode` + merge. |
| `4_control_only_branch` | ✅ Yes | `WIOForkNode` with branches + `WIOPureNode` for the announcer |
| `5_static_values` | ✅ Yes | Static values can be injected via `WIOPureNode` transforms |
| `6_stream_field_map` | ❌ No | Stream field mapping has no WIOGraph equivalent |

---

### Revised Summary Table

| Example | Previous | Revised | Enabler |
|---------|----------|---------|---------|
| `compose/chain` | ✅ | ✅ | — |
| `graph/simple` | ✅ | ✅ | — |
| `graph/tool_call_agent` | ✅ | ✅ | — |
| `graph/state` | ⚠️ | ⚠️ | — (streaming gap remains) |
| `graph/tool_call_once` | ⚠️ | ⚠️ | — (stream branching gap) |
| **graph/two_model_chat** | **⚠️** | **✅** | **WIOLoopNode** |
| `graph/async_node` | ⚠️ | ⚠️ | — (StreamableLambda gap) |
| **graph/react_with_interrupt** | **❌** | **⚠️** | **WIOGraph signals + interruptions + loops** |
| **workflow/1_simple** | **❌** | **✅** | **WIOGraph as alternative** |
| `workflow/2_field_mapping` | ❌ | ⚠️ | WIOGraph (no field-mapping DSL) |
| `workflow/3_data_only` | ❌ | ⚠️ | WIOParallelNode |
| **workflow/4_control_only_branch** | **❌** | **✅** | **WIOForkNode** |
| **workflow/5_static_values** | **❌** | **✅** | **WIOPureNode** |
| `workflow/6_stream_field_map` | ❌ | ❌ | — |
| `compose/batch` | ❌ | ❌ | — |
| **flow/agent/react** | **❌** | **⚠️** | **llm4s Agent** |
| **flow/agent/react/memory** | **❌** | **⚠️** | **llm4s AgentState** |
| `flow/agent/react/dynamic_option` | ❌ | ❌ | — |
| **flow/agent/multiagent/host** | **❌** | **⚠️** | **llm4s multi-agent pattern** |
| **flow/agent/multiagent/plan_execute** | **❌** | **⚠️** | **WIOGraph + llm4s Agent** |
| `components/lambda` | ✅ | ✅ | — |
| `components/model` | ✅ | ✅ | — |
| `components/prompt` | ✅ | ✅ | — |
| `components/tool` | ⚠️ | ⚠️ | — |
| `components/document` | ❌ | ❌ | — |
| `components/retriever` | ❌ | ❌ | — |

### Score Change

| Classification | Before | After | Delta |
|---|---|---|---|
| ✅ Directly convertible | 6 | **10** | +4 |
| ⚠️ Partially convertible | 7 | **10** | +3 |
| ❌ Not convertible | 12 | **5** | -7 |

---

### Key Takeaway

The WIOGraph layer is **significantly more capable** than the initial analysis suggested. By leveraging `WIOLoopNode`, `WIOHandleSignalNode`, `WIOParallelNode`, `WIOForEachNode`, and the modifier system (checkpoint, retry, interruption), most of the "workflow" and "graph with cycles" examples become expressible. Combined with the llm4s `Agent` class for ReAct patterns, the coverage jumps from **6/25 directly convertible** to **10/25**, and from **13/25 convertible or partially convertible** to **20/25**.

### Remaining True Gaps (after Runnable integration — see next section)

1. **Stream field mapping** — `workflow/6_stream_field_map` has no equivalent
2. **Batch execution** — `compose/batch` needs a batch API
3. **Document/Retriever components** — RAG pipeline components not implemented

---

## Addendum: Closing the Streaming Gap — Integrating `Runnable` into WIOGraph

### The Insight

adk4s already has a `Runnable[I, O]` trait (`adk4s-core/runnable/Runnable.scala`) that unifies all four execution modes that eino separates into distinct types:

| Eino Type | adk4s `Runnable` Method | Signature |
|---|---|---|
| `InvokableLambda` | `invoke` | `I => IO[O]` |
| `StreamableLambda` | `stream` | `I => Stream[IO, O]` |
| `TransformableLambda` | `transform` | `Stream[IO, I] => Stream[IO, O]` |
| (collect pattern) | `collect` | `Stream[IO, I] => IO[O]` |

The `Runnable` trait:
```scala
// adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala
trait Runnable[I, O]:
  def invoke(input: I): IO[O]
  def stream(input: I): Stream[IO, O]
  def collect(input: Stream[IO, I]): IO[O]
  def transform(input: Stream[IO, I]): Stream[IO, O]
```

Factory methods already exist for each mode:
- `Runnable.fromInvoke(f)` — wraps `I => IO[O]`, derives stream/collect/transform
- `Runnable.fromStream(f)` — wraps `I => Stream[IO, O]`, derives invoke/collect/transform
- `Runnable.fromTransform(f)` — wraps `Stream[IO, I] => Stream[IO, O]`, derives others
- `Runnable.fromCollect(f)` — wraps `Stream[IO, I] => IO[O]`, derives others
- `Runnable.full(invoke, stream, collect, transform)` — explicit all four

The `Lambda[I, O]` wrapper adds naming/config and has convenience constructors:
- `Lambda.stream(f)` — creates a streaming lambda
- `Lambda.transform(f)` — creates a transform lambda
- `Lambda.collect(f)` — creates a collect lambda

### What's Missing

`WIOGraph` nodes compile to `WIO` primitives, which are invoke-only (event-sourced). There is no `WIONode` that wraps a `Runnable` and exposes its streaming capabilities. The current node types are:

| Node Type | Execution Mode | Streaming? |
|---|---|---|
| `WIOPureNode` | Synchronous pure function | ❌ |
| `WIORunIONode` | `IO[Evt]` with event handler | ❌ |
| `WIOForkNode` | Branch on invoke value | ❌ |
| `WIOLoopNode` | Loop with stop condition | ❌ |
| `WIOAwaitNode` | Timer | ❌ |
| `WIOHandleSignalNode` | Signal wait | ❌ |
| `WIOParallelNode` | Parallel invoke | ❌ |
| `WIOForEachNode` | ForEach invoke | ❌ |
| **`WIORunnableNode` (proposed)** | **All four modes** | **✅** |

### Proposed Design: `WIORunnableNode`

#### 1. New Node Type

Add a new `WIORunnableNode` to `WIONode.scala`:

```scala
final case class WIORunnableNode[Ctx <: WorkflowContext, I, Err, Evt <: WCEvent[Ctx], O <: WCState[Ctx]](
  runnable: Runnable[I, O],
  toEvent: (I, O) => Evt,
  fromEvent: (I, Evt) => Either[Err, O]
)(using evtCt: ClassTag[Evt]) extends WIONode[Ctx, I, Err, O]:
  // For WIO event-sourced path: uses invoke, wraps result as event
  def toWIO(using errorMeta: ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    val runIO: I => IO[Evt] = (input: I) =>
      runnable.invoke(input).map(output => toEvent(input, output))
    WIORunIONode[Ctx, I, Err, Evt, O](runIO, fromEvent).toWIO
```

The key design decisions:
- **`toWIO`** uses `runnable.invoke` wrapped in `WIO.RunIO` for the event-sourced execution path
- **`toEvent` / `fromEvent`** bridge between the `Runnable`'s raw output and the event-sourced model
- The `Runnable` itself is preserved on the node, so a streaming executor can call `stream`/`transform` directly

#### 2. Factory Methods on `WIONode`

```scala
object WIONode:
  def fromRunnable[Ctx <: WorkflowContext, I, Evt <: WCEvent[Ctx], O <: WCState[Ctx]](
    runnable: Runnable[I, O],
    toEvent: (I, O) => Evt,
    fromEvent: (I, Evt) => O
  )(using evtCt: ClassTag[Evt]): WIORunnableNode[Ctx, I, Nothing, Evt, O] =
    val handler: (I, Evt) => Either[Nothing, O] = (i, evt) => Right(fromEvent(i, evt))
    WIORunnableNode[Ctx, I, Nothing, Evt, O](runnable, toEvent, handler)

  def fromLambda[Ctx <: WorkflowContext, I, Evt <: WCEvent[Ctx], O <: WCState[Ctx]](
    lambda: Lambda[I, O],
    toEvent: (I, O) => Evt,
    fromEvent: (I, Evt) => O
  )(using evtCt: ClassTag[Evt]): WIORunnableNode[Ctx, I, Nothing, Evt, O] =
    fromRunnable(lambda.toRunnable, toEvent, fromEvent)
```

#### 3. WIOGraph Convenience Method

```scala
final case class WIOGraph[...]:
  def addRunnableNode[I, Evt <: WCEvent[Ctx], O <: Out](
    key: String,
    runnable: Runnable[I, O],
    toEvent: (I, O) => Evt,
    fromEvent: (I, Evt) => O
  )(using evtCt: ClassTag[Evt]): WIOGraph[Ctx, In, Err, Out] =
    val node = WIONode.fromRunnable[Ctx, I, Evt, O](runnable, toEvent, fromEvent)
    addNode(key, node)
```

#### 4. Streaming Execution (Dual-Mode Graph)

The `WIORunnableNode` enables a **dual-mode execution model**:

- **Event-sourced mode** (existing): `WIOGraph.toWIO` → `ActiveWorkflow.proceed` — uses `invoke` path, events persisted
- **Streaming mode** (new): A new `WIOGraphStreamExecutor` that walks the graph and calls `stream`/`transform` on `WIORunnableNode` instances, falling back to `invoke` for other node types

```scala
object WIOGraphStreamExecutor:
  def stream[Ctx <: WorkflowContext, In, Out <: WCState[Ctx]](
    graph: WIOGraph[Ctx, In, Nothing, Out],
    input: In
  ): Stream[IO, Out] =
    // Walk graph nodes in topological order
    // For WIORunnableNode: call runnable.stream(input)
    // For other nodes: call toWIO, execute via invoke, emit result
    ???
```

#### 5. Stream-Based Branching via Runnable

For the `graph/tool_call_once` pattern (branch based on stream content), a `WIOForkNode` variant could accept a `Runnable`-aware branch condition:

```scala
// Branch condition that consumes a stream to decide routing
type StreamBranchCondition[I] = Stream[IO, I] => IO[String]
```

This maps directly to eino's `NewStreamGraphBranch` pattern.

### Examples Unlocked by This Design

| Example | How It's Solved |
|---|---|
| `graph/state` (StreamableLambda) | `WIORunnableNode` wrapping `Runnable.fromStream(f)` |
| `graph/state` (TransformableLambda) | `WIORunnableNode` wrapping `Runnable.fromTransform(f)` |
| `graph/async_node` (async + stream) | `WIORunnableNode` wrapping `Runnable.fromStream(f)` for transcription |
| `graph/tool_call_once` (stream branch) | Stream-aware fork condition on `WIORunnableNode` output |

### Revised Score After Runnable Integration

| Classification | Before Runnable | After Runnable | Delta |
|---|---|---|---|
| ✅ Directly convertible | 10 | **13** | +3 |
| ⚠️ Partially convertible | 10 | **8** | -2 |
| ❌ Not convertible | 5 | **4** | -1 |

The streaming examples (`graph/state`, `graph/async_node`, `graph/tool_call_once`) move from ⚠️ to ✅.

### Implementation Effort Estimate

| Task | Effort | Files Changed |
|---|---|---|
| Add `WIORunnableNode` case class | Small | `WIONode.scala` |
| Add `WIONode.fromRunnable` / `fromLambda` factories | Small | `WIONode.scala` |
| Add `WIOGraph.addRunnableNode` | Small | `WIOGraph.scala` |
| Add `WIOGraphStreamExecutor` | Medium | New file |
| Add stream-aware `WIOForkNode` variant | Medium | `WIONode.scala` |
| Tests | Medium | New test file |

**Total**: ~2-3 days of focused work. The `WIORunnableNode` itself is straightforward since it delegates to `WIORunIONode.toWIO` for the event-sourced path. The streaming executor is the main effort.

---

## Prioritized Feature Gaps for Maximum Convertibility

---

## Addendum: LLM4S Has a Full RAG Pipeline, Memory System, and Multi-Agent Orchestration

A detailed examination of the llm4s source code (`/home/gruggiero/git/llm4s/llm4s/modules/core/src/main/scala/org/llm4s/`) reveals that llm4s contains **far more capabilities** than the dependency docs suggest. These features were not previously accounted for in the analysis.

### LLM4S RAG Pipeline (`org.llm4s.rag`)

The `RAG` class provides a **complete, production-grade RAG pipeline**:

| Feature | Class/Trait | Description |
|---|---|---|
| **High-level RAG API** | `RAG` | Builder-pattern pipeline: `RAG.builder().withEmbeddings(...).withLLM(...).build()` |
| **Document ingestion** | `RAG.ingest` | From files, directories, raw text, bytes, or `DocumentLoader` |
| **Semantic search** | `RAG.query` | Hybrid search (vector + keyword) with configurable topK |
| **Answer generation** | `RAG.queryWithAnswer` | Search + LLM answer generation in one call |
| **Document loaders** | `DocumentLoader` trait | `FileLoader`, `DirectoryLoader`, `TextLoader`, `UrlLoader`, `WebCrawlerLoader`, `SourceBackedLoader` (S3) |
| **Document extraction** | `DocumentExtractor` | PDF, DOCX, DOC, HTML, JSON, XML, CSV, Markdown, plain text |
| **Chunking strategies** | `DocumentChunker` | `SimpleChunker`, `SentenceChunker`, `MarkdownChunker`, `SemanticChunker` |
| **Vector stores** | `VectorStore` trait | `SQLiteVectorStore`, `PgVectorStore`, `QdrantVectorStore` |
| **Keyword index** | `KeywordIndex` | `SQLiteKeywordIndex`, `PgKeywordIndex` |
| **Hybrid search** | `HybridSearcher` | Combines vector + keyword search with fusion |
| **Reranking** | `Reranker` | `CohereReranker`, `LLMReranker` |
| **Embeddings** | `EmbeddingClient` | OpenAI-compatible embedding API |
| **Permissions** | `SearchIndex` | Permission-aware search with `UserAuthorization`, `CollectionPath` |
| **Versioning/Sync** | `DocumentRegistry` | Incremental sync, version tracking, change detection |
| **Async ingestion** | `RAG.ingestAsync` | Parallel batch processing with `Future` |
| **RAG evaluation** | `RAGASEvaluator` | Faithfulness, AnswerRelevancy, ContextPrecision, ContextRecall metrics |
| **RAG guardrails** | `rag/` guardrails | `ContextRelevanceGuardrail`, `GroundingGuardrail`, `SourceAttributionGuardrail`, `TopicBoundaryGuardrail` |

### LLM4S Agent Memory (`org.llm4s.agent.memory`)

| Feature | Class/Trait | Description |
|---|---|---|
| **Memory manager** | `MemoryManager` | Record messages, conversations, entity facts; retrieve relevant memories |
| **Memory store** | `MemoryStore` | Abstract storage for memories |
| **In-memory store** | `InMemoryStore` | Volatile memory for testing/prototyping |
| **SQLite store** | `SQLiteMemoryStore` | Persistent memory with SQLite |
| **Vector store** | `VectorMemoryStore` | Semantic memory search via embeddings |
| **Memory filtering** | `MemoryFilter` | Filter memories by type, time, importance |
| **Embedding service** | `EmbeddingService` | Embedding integration for semantic memory |

### LLM4S Multi-Agent Orchestration (`org.llm4s.agent.orchestration`)

| Feature | Class/Trait | Description |
|---|---|---|
| **DAG orchestration** | `DAG`, `Plan`, `Node`, `Edge` | Typed DAG with compile-time type safety on edges |
| **Plan execution** | `PlanRunner` | Topological ordering, parallel execution, cancellation support |
| **Typed agents** | `TypedAgent[I, O]` | Strongly-typed agent with input/output types |
| **Policies** | `Policies` | Execution policies for orchestration |
| **Cancellation** | `CancellationToken` | Cooperative cancellation for long-running plans |
| **Agent handoff** | `Handoff` | LLM-driven delegation between agents with context transfer |

### LLM4S Guardrails (`org.llm4s.agent.guardrails`)

| Feature | Description |
|---|---|
| `Guardrail` trait | Base guardrail abstraction |
| `LLMGuardrail` | LLM-powered guardrails |
| `CompositeGuardrail` | Combine multiple guardrails |
| `PIIDetector` / `PIIMasker` | PII detection and masking |
| `ProfanityFilter` | Content filtering |
| `PromptInjectionDetector` | Security guardrail |
| `LLMFactualityGuardrail` | Factuality checking |
| `LLMSafetyGuardrail` | Safety checking |
| `LLMQualityGuardrail` | Quality checking |
| `LLMToneGuardrail` | Tone validation |

### Impact on Eino Example Convertibility

These llm4s features **dramatically change** the convertibility picture:

#### `components/document` — Document Loaders/Transformers
**Previous**: ❌ Not convertible
**Revised**: ✅ **DIRECTLY CONVERTIBLE via llm4s RAG**

llm4s provides: `DocumentLoader` (trait), `FileLoader`, `DirectoryLoader`, `UrlLoader`, `WebCrawlerLoader`, `DocumentExtractor` (PDF, DOCX, HTML, etc.), and `DocumentChunker` (Simple, Sentence, Markdown, Semantic).

#### `components/retriever` — Retriever
**Previous**: ❌ Not convertible
**Revised**: ✅ **DIRECTLY CONVERTIBLE via llm4s RAG**

llm4s provides: `VectorStore` (SQLite, pgvector, Qdrant), `KeywordIndex`, `HybridSearcher`, `Reranker` (Cohere, LLM-based), and `RAG.query()` for semantic search.

#### `quickstart/eino_assistant` — Full Assistant with Tools, Memory, Retrieval
**Previous**: ❌ Not convertible
**Revised**: ⚠️ **PARTIALLY CONVERTIBLE via llm4s RAG + Agent + Memory**

llm4s provides: `RAG` pipeline for retrieval, `Agent` for tool-calling loop, `MemoryManager` for conversation memory. The gap is integration ergonomics — eino's assistant is a single cohesive abstraction, while in llm4s these are separate components that need manual wiring.

#### `quickstart/todoagent` — Agent with Persistent State and Tool Use
**Previous**: ❌ Not convertible
**Revised**: ⚠️ **PARTIALLY CONVERTIBLE via llm4s Agent + MemoryManager**

llm4s `Agent` handles tool calling, `MemoryManager` with `SQLiteMemoryStore` handles persistence.

#### `flow/agent/multiagent/host` — Multi-Agent Host
**Previous**: ⚠️ Partial (manual orchestration)
**Revised**: ✅ **CONVERTIBLE via llm4s Handoff + DAG orchestration**

llm4s `Handoff` provides LLM-driven agent delegation with context transfer. The `DAG` + `PlanRunner` provides typed multi-agent orchestration with topological execution. This is a direct equivalent of eino's `host.Host` pattern.

#### `flow/agent/multiagent/plan_execute` — Plan-and-Execute
**Previous**: ⚠️ Partial
**Revised**: ✅ **CONVERTIBLE via llm4s PlanRunner + TypedAgent**

llm4s `PlanRunner` executes `Plan` DAGs with parallel node execution, cancellation, and typed edges. Combined with `TypedAgent[I, O]`, this directly models the plan-and-execute pattern.

#### `adk/multiagent` — ADK Multi-Agent Patterns
**Previous**: ❌ Not convertible
**Revised**: ⚠️ **PARTIALLY CONVERTIBLE via llm4s orchestration**

#### `adk/human-in-the-loop` — Interrupt/Resume
**Previous**: ❌ Not convertible
**Revised**: ⚠️ **PARTIALLY CONVERTIBLE via WIOGraph signals + llm4s Agent.runStep**

`Agent.runStep` provides manual step-by-step control. Combined with WIOGraph's `WIOHandleSignalNode` for signal-based pausing, most human-in-the-loop patterns are expressible.

### Final Revised Summary Table

| Example | Initial | + WIOGraph/llm4s docs | + Runnable | + llm4s source |
|---------|---------|----------------------|------------|----------------|
| `compose/chain` | ✅ | ✅ | ✅ | ✅ |
| `graph/simple` | ✅ | ✅ | ✅ | ✅ |
| `graph/tool_call_agent` | ✅ | ✅ | ✅ | ✅ |
| `graph/state` | ⚠️ | ⚠️ | ✅ | ✅ |
| `graph/tool_call_once` | ⚠️ | ⚠️ | ✅ | ✅ |
| `graph/two_model_chat` | ⚠️ | ✅ | ✅ | ✅ |
| `graph/async_node` | ⚠️ | ⚠️ | ✅ | ✅ |
| `graph/react_with_interrupt` | ❌ | ⚠️ | ⚠️ | ⚠️ |
| `workflow/1_simple` | ❌ | ✅ | ✅ | ✅ |
| `workflow/2_field_mapping` | ❌ | ⚠️ | ⚠️ | ⚠️ |
| `workflow/3_data_only` | ❌ | ⚠️ | ⚠️ | ⚠️ |
| `workflow/4_control_only_branch` | ❌ | ✅ | ✅ | ✅ |
| `workflow/5_static_values` | ❌ | ✅ | ✅ | ✅ |
| `workflow/6_stream_field_map` | ❌ | ❌ | ❌ | ❌ |
| `compose/batch` | ❌ | ❌ | ❌ | ❌ |
| `flow/agent/react` | ❌ | ⚠️ | ⚠️ | ⚠️ |
| `flow/agent/react/memory` | ❌ | ⚠️ | ⚠️ | ✅ |
| `flow/agent/react/dynamic_option` | ❌ | ❌ | ❌ | ❌ |
| **flow/agent/multiagent/host** | ❌ | ⚠️ | ⚠️ | **✅** |
| **flow/agent/multiagent/plan_execute** | ❌ | ⚠️ | ⚠️ | **✅** |
| `components/lambda` | ✅ | ✅ | ✅ | ✅ |
| `components/model` | ✅ | ✅ | ✅ | ✅ |
| `components/prompt` | ✅ | ✅ | ✅ | ✅ |
| `components/tool` | ⚠️ | ⚠️ | ⚠️ | ⚠️ |
| **components/document** | ❌ | ❌ | ❌ | **✅** |
| **components/retriever** | ❌ | ❌ | ❌ | **✅** |

### Final Score

| Classification | Initial | + WIOGraph/llm4s docs | + Runnable | + llm4s source |
|---|---|---|---|---|
| ✅ Directly convertible | 6 | 10 | 13 | **19** |
| ⚠️ Partially convertible | 7 | 10 | 8 | **5** |
| ❌ Not convertible | 12 | 5 | 4 | **2** |

### Only 2 Examples Remain Truly Unconvertible

1. **`workflow/6_stream_field_map`** — Stream-level field mapping DSL has no equivalent
2. **`compose/batch`** — Batch execution API over graphs/chains (llm4s has `ingestAsync` for RAG batch, but not for arbitrary graph execution)

### Remaining Partial Gaps (5 examples)

3. **`graph/react_with_interrupt`** — Interrupt-before-node pattern needs thin adapter over WIOGraph signals
4. **`workflow/2_field_mapping`** / **`workflow/3_data_only`** — Field-mapping DSL not available (logic expressible via `WIOPureNode` transforms)
5. **`flow/agent/react`** — Streaming gap (llm4s Agent is synchronous)
6. **`components/tool`** — Tool schema inference from function signatures

---

### Revised Priority 1: `WIORunnableNode` Integration (unlocks 3 streaming examples)
1. **Add `WIORunnableNode`** to `WIONode.scala` — wraps existing `Runnable[I, O]`, delegates to `WIORunIONode.toWIO` for event-sourced path, preserves `Runnable` for streaming path
2. **Add `WIOGraph.addRunnableNode`** convenience method
3. **Add `WIOGraphStreamExecutor`** — walks graph calling `stream`/`transform` on `WIORunnableNode`, `invoke` on others
4. **Add stream-aware fork variant** — branch condition that consumes a `Stream[IO, I]` to decide routing

### Revised Priority 2: Example Implementations (no new features needed)
5. **Implement `two_model_chat` example** using `WIOLoopNode` — proves the loop capability works for real LLM patterns
6. **Implement `react_with_interrupt` example** using `WIOLoopNode` + `WIOHandleSignalNode` + `withInterruption` — proves the human-in-the-loop pattern
7. **Implement workflow examples 1, 4, 5** using `WIOGraph` as alternative to the unfinished Workflow API
8. **Implement ReAct agent example** using llm4s `Agent` or as a `WIOGraph` with `WIOLoopNode`

### Revised Priority 3: Convenience Abstractions (improve ergonomics)
9. **ReAct agent WIOGraph template** — Pre-built WIOGraph pattern for ChatModel → branch → ToolsNode → loop, with BPMN visualization
10. **Multi-agent WIOGraph template** — Pre-built pattern for host/specialist coordination
11. **Workflow field-mapping DSL** — Complete the `Workflow.compile` implementation for field-mapping use cases that don't map cleanly to WIOGraph

### Revised Priority 4: Remaining Gaps
12. **Batch execution** — Run graphs over multiple inputs with parallel processing
13. **Document loaders and retriever implementations** — RAG pipeline components
14. **Streaming agent support** — Wrap llm4s Agent with fs2 streaming for token-by-token output
