# Change: Implement Convertible Eino Examples in adk4s

## Why
The eino-examples convertibility report identified 19 out of 26 Eino examples as directly convertible to adk4s using existing features (adk4s core, WIOGraph, llm4s). Currently only 1 example (`compose/chain`) is implemented. Implementing the remaining 18 examples will:
- Validate that adk4s features work end-to-end for real agent patterns
- Provide reference implementations for users adopting adk4s
- Surface any API ergonomic issues before they become entrenched
- Demonstrate the full breadth of adk4s capabilities (graphs, loops, tools, RAG, memory, multi-agent orchestration)

## What Changes

### Phase 0: Infrastructure — WIORunnableNode (prerequisite for 3 streaming examples)
- Add `WIORunnableNode` to `WIONode.scala` — wraps `Runnable[I, O]`, delegates to `WIORunIONode.toWIO` for event-sourced path
- Add `WIOGraph.addRunnableNode` convenience method
- Add `WIOGraphStreamExecutor` — walks graph calling `stream`/`transform` on `WIORunnableNode`, `invoke` on others
- Add stream-aware fork variant for stream-based branching

### Phase 1: Component Examples (5 examples)
- `components/lambda` — Lambda showcase (invoke, stream, transform, collect modes)
- `components/model` — ChatModel showcase (generate, stream)
- `components/prompt` — ChatTemplate showcase (template rendering, variable substitution)
- `components/document` — Document loading via llm4s RAG (`DocumentLoader`, `DocumentExtractor`, `DocumentChunker`)
- `components/retriever` — Retrieval via llm4s RAG (`VectorStore`, `HybridSearcher`, `Reranker`)

### Phase 2: Graph Examples (6 examples, 3 need Phase 0)
- `graph/simple` — START → ChatTemplate → ChatModel → END
- `graph/tool_call_agent` — Template → ChatModel → ToolsNode → END
- `graph/two_model_chat` — Writer/critic loop via `WIOLoopNode`
- `graph/state` — Graph with state pre/post handlers + `WIORunnableNode` for streaming *(needs Phase 0)*
- `graph/tool_call_once` — ChatModel → branch (tool calls vs END) + `WIORunnableNode` *(needs Phase 0)*
- `graph/async_node` — Async lambda + streaming transcription via `WIORunnableNode` *(needs Phase 0)*

### Phase 3: Workflow Alternative Examples (3 examples)
- `workflow/1_simple` — Simple lambda chain via WIOGraph
- `workflow/4_control_only_branch` — Branching via `WIOForkNode`
- `workflow/5_static_values` — Static value injection via `WIOPureNode`

### Phase 4: Agent Pattern Examples (3 examples)
- `flow/agent/react/memory` — ReAct agent with conversation memory via llm4s `Agent` + `MemoryManager`
- `flow/agent/multiagent/host` — Host/specialist delegation via llm4s `Handoff` + `DAG` orchestration
- `flow/agent/multiagent/plan_execute` — Plan-and-execute via llm4s `PlanRunner` + `TypedAgent`

### Phase 5: Quickstart (1 example)
- `quickstart/chat` — Basic multi-turn chat with ChatModel

## Impact
- Affected specs: New spec `eino-examples`
- Affected code: `adk4s-examples/src/main/scala/org/adk4s/examples/` (new example files)
- Affected code: `adk4s-orchestration/` (WIORunnableNode infrastructure in Phase 0)
- No breaking changes to existing APIs
