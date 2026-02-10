# Gap Analysis: Eino (Go) vs adk4s + llm4s (Scala)

**Date**: 2026-02-08
**Scope**: Feature-by-feature comparison of CloudWeGo Eino (Go) against adk4s + llm4s (Scala)

---

## Executive Summary

Eino is a comprehensive Go-based LLM application framework with a layered architecture: Schema -> Components -> Compose -> Flow -> ADK. The Scala ecosystem (adk4s built on llm4s) covers significant ground but has notable gaps in several areas. This report details each feature area, the current state of parity, and what would be needed to close gaps.

**Legend**:
- **Full Parity** - Feature exists with comparable or superior capability
- **Partial Parity** - Feature exists but with reduced scope
- **No Parity** - Feature is absent; gap must be filled
- **Scala Advantage** - Feature in adk4s/llm4s has no Eino equivalent

---

## 1. Schema & Message Layer

### 1.1 Message Types

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Basic text messages (System/User/Assistant/Tool) | `schema.Message` with `RoleType` | llm4s `Message` + adk4s `Prompt.Message` | **Full Parity** |
| Tool calls in messages | `ToolCall` with `ID`, `Function` (Name, Arguments) | llm4s `ToolCall` in `Completion` | **Full Parity** |
| Multi-content messages (text + image) | `MultiContent` with `ChatMessagePart` (text, image_url) | llm4s supports image URLs in messages | **Partial Parity** |
| Audio content | `AudioContent` in `UserInputMultiContent` | Not supported | **No Parity** |
| Video content | `VideoContent` in `UserInputMultiContent` | Not supported | **No Parity** |
| File content | `FileContent` in `UserInputMultiContent` | Not supported | **No Parity** |
| Reasoning content (chain-of-thought) | `ReasoningContent` with `Content` + `Signature` | Not supported | **No Parity** |
| Token usage tracking | `TokenUsage` (prompt/completion/reasoning/cache breakdown) | llm4s `TokenUsage` (basic prompt/completion) | **Partial Parity** |
| LogProbs | `LogProbs` with `TokenLogProb` array | Not directly exposed | **No Parity** |
| Response metadata | `ResponseMeta` (ID, Model, FinishReason) | llm4s `Completion` has model, finish reason | **Partial Parity** |
| Message concatenation (stream merge) | `ConcatMessages`, `ConcatMessageStream` | Not applicable (different streaming model) | **N/A** |

**Gap Assessment**: The biggest gaps are **multimodal content** beyond images (audio, video, file), **reasoning content** for chain-of-thought models, and **detailed token usage** with cache/reasoning breakdowns.

**To fill the gap**:
- Extend llm4s `Message` to support `AudioContent`, `VideoContent`, `FileContent` variants
- Add `ReasoningContent` support for models like o1/o3/DeepSeek-R1
- Enrich `TokenUsage` with reasoning tokens, cached tokens breakdowns
- Add `LogProbs` to completion results

### 1.2 Template System

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| String interpolation templates | FString (`{variable}`) | adk4s `PromptTemplate` with `{variable}` | **Full Parity** |
| Go template syntax | `GoTemplate` | N/A (Scala has string interpolation) | **N/A** |
| Jinja2 templates | `Jinja2Template` | Not supported | **No Parity** |
| Messages placeholder (history injection) | `MessagesPlaceholder` injects conversation history | Manual concatenation | **No Parity** |

**Gap Assessment**: The `MessagesPlaceholder` pattern is particularly useful for injecting conversation history into prompt templates. Jinja2 is less critical in Scala (string interpolation + `PromptTemplate` covers most cases).

**To fill the gap**:
- Add `MessagesPlaceholder` concept to `PromptTemplate` for automatic conversation history injection
- Consider Jinja2 support if cross-language prompt sharing is needed

### 1.3 Document Types

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Document with metadata | `Document` (ID, Content, MetaData map) | llm4s `Document` (id, content, metadata) | **Full Parity** |
| Dense vectors | `DenseVector []float64` | llm4s VectorRecord has embeddings | **Full Parity** |
| Sparse vectors | `SparseVector` (indices + values) | Not supported | **No Parity** |
| Score field | `Score *float64` | llm4s `ScoredRecord.score` | **Full Parity** |
| Sub-indexes | `SubIndexes map[string][]SubIndex` | Not supported | **No Parity** |
| DSL info for retrieval | `DSLInfo` | Not supported | **No Parity** |

**Gap Assessment**: Sparse vectors and sub-indexes are advanced retrieval features. Most use cases are covered.

---

## 2. LLM Client / Chat Model

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Generate (non-streaming) | `BaseChatModel.Generate()` | llm4s `LLMClient.complete()` | **Full Parity** |
| Stream | `BaseChatModel.Stream()` | llm4s streaming support | **Full Parity** |
| Tool binding | `ToolCallingChatModel.WithTools()` | adk4s `ChatModel.withTools()` | **Full Parity** |
| Completion options | Via `Option` functional options | adk4s `CompletionOptions` | **Full Parity** |
| Multi-provider support | OpenAI, Anthropic, etc. via separate packages | llm4s: OpenAI, Anthropic, Google, Azure, Mistral, Groq, Ollama, AWS Bedrock | **Scala Advantage** (8 providers) |
| Response caching | Not in core Eino | llm4s has response caching | **Scala Advantage** |
| Token counting/encoding | Not in core Eino | llm4s `ConversationTokenCounter` | **Scala Advantage** |

**Gap Assessment**: **Full parity or better**. llm4s actually supports more providers than Eino core.

---

## 3. Tool System

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Tool info/schema | `ToolInfo` (Name, Desc, ParamsOneOf) | adk4s `AdkToolInfo` + `ToolSchema.derive` | **Full Parity** |
| Invokable tool (string JSON in, string out) | `InvokableTool.InvokableRun()` | adk4s `InvokableTool[F].invoke()` | **Full Parity** |
| Streamable tool | `StreamableTool.StreamableRun()` | Not supported as a separate tool type | **No Parity** |
| Enhanced invokable (multimodal result) | `EnhancedInvokableTool` returns `*ToolResult` | Not supported | **No Parity** |
| Enhanced streamable (multimodal stream) | `EnhancedStreamableTool` | Not supported | **No Parity** |
| Tool from function | Via struct + InvokableRun impl | adk4s `Tool.invokable(name, desc, params)(fn)` | **Full Parity** |
| Tool middleware | Not in Eino tool layer (in compose) | adk4s `ToolMiddleware` (logging, timing, retry, validation) | **Scala Advantage** |
| Dynamic tool registry | Not built-in | adk4s `DynamicToolRegistry` with `Ref[IO, ...]` | **Scala Advantage** |
| Built-in tools | Not in Eino core | llm4s: DateTime, Calculator, UUID, JSON, WebSearch, HTTP, File | **Scala Advantage** |
| ToolsNode (batch execution) | `compose.ToolsNode` in compose layer | adk4s `ToolsNode` with config builder | **Full Parity** |
| Schema derivation | Manual JSON schema definition | adk4s `ToolSchema.derive` from smithy4s schemas | **Scala Advantage** |
| ToolChoice (forced/allowed/forbidden) | `schema.ToolChoice` enum | llm4s supports tool_choice | **Full Parity** |

**Gap Assessment**: The Scala side actually has **advantages** in tool middleware, dynamic registries, built-in tools, and schema derivation. The gaps are **streaming tools** and **multimodal tool results** (Enhanced tools).

**To fill the gap**:
- Add `StreamableTool[F]` trait with `streamInvoke(): Stream[F, String]`
- Add `EnhancedToolResult` type supporting text + images + audio + video + files
- Add `EnhancedInvokableTool[F]` returning `F[EnhancedToolResult]`

---

## 4. Embeddings

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Embed strings | `Embedder.EmbedStrings()` returns `[][]float64` | adk4s `Embedder[F].embedBatch()` | **Full Parity** |
| Single embedding | Batch with single input | adk4s `Embedder[F].embed()` | **Full Parity** |
| Embedding dimension | Not explicit | adk4s `Embedder[F].dimension` | **Scala Advantage** |
| Embedding service | Via provider packages | llm4s `EmbeddingService` with caching | **Scala Advantage** |
| Usage tracking | Not in core interface | llm4s `EmbeddingUsage` (promptTokens, totalTokens) | **Scala Advantage** |

**Gap Assessment**: **Full parity or better**. llm4s has richer embedding support.

---

## 5. Retrieval (RAG)

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Retriever interface | `Retriever.Retrieve()` returns `[]*Document` | adk4s `Retriever[F].retrieve()` returns `List[Document]` | **Full Parity** |
| Indexer interface | `Indexer.Store()` returns `([]string, error)` | llm4s VectorStore.upsert/upsertBatch | **Full Parity** |
| Vector store | Via external packages | llm4s: SQLite, PgVector, Qdrant | **Full Parity** |
| Multi-query retriever | `flow/retriever/MultiQueryRetriever` | Not implemented | **No Parity** |
| Parent document retriever | `flow/retriever/ParentRetriever` + `flow/indexer/parent` | Not implemented | **No Parity** |
| Router retriever | `flow/retriever/RouterRetriever` | Not implemented | **No Parity** |
| Reranking | Not in Eino core | llm4s: Cohere reranker, LLM reranker | **Scala Advantage** |
| Hybrid search | Not in Eino core | llm4s `HybridSearcher` (vector + keyword) | **Scala Advantage** |
| Document chunking | Not in Eino core | llm4s: Simple, Sentence, Markdown, Semantic chunkers | **Scala Advantage** |
| RAG orchestrator | Not in Eino core (compose-based) | llm4s `RAG` class (index, retrieve, query) | **Scala Advantage** |
| RAG evaluation | Not in Eino core | llm4s: Precision, Recall, F1, MRR, NDCG, BLEU, ROUGE | **Scala Advantage** |
| Metadata filtering | Not in core Eino | llm4s `MetadataFilter` DSL (And, Or, Not, Equals, Contains) | **Scala Advantage** |

**Gap Assessment**: Eino has specific **retriever flow patterns** (MultiQuery, Parent, Router) that adk4s lacks, but llm4s has significantly richer RAG infrastructure overall.

**To fill the gap**:
- Implement `MultiQueryRetriever` that generates multiple query variants via LLM
- Implement `ParentDocumentRetriever` that indexes chunks but returns parent documents
- Implement `RouterRetriever` that routes queries to appropriate retriever based on content

---

## 6. Document Processing

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Document loader interface | `Loader.Load()` returns `[]*Document` | llm4s: FileLoader, S3Loader, WebLoader | **Full Parity** |
| Document transformer | `Transformer.Transform()` | Not as explicit interface | **Partial Parity** |
| Document source | `Source` with URIs | llm4s loader sources | **Full Parity** |
| PDF extraction | Via external packages | llm4s: PDFBox extraction | **Full Parity** |

**Gap Assessment**: Roughly at parity. llm4s has concrete implementations while Eino defines interfaces.

---

## 7. Prompt Templates

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Chat template interface | `ChatTemplate.Format()` returns `[]*Message` | adk4s `ChatTemplate[F, V].format()` returns `F[Prompt]` | **Full Parity** |
| Variable substitution | FString `{variable}` | adk4s `ChatTemplate.simple` with `{key}` | **Full Parity** |
| Type-safe variables | Via map[string]any | adk4s `ChatTemplate[F, V]` with type parameter V | **Scala Advantage** |
| From messages builder | Part of ChatTemplate | adk4s `ChatTemplate.fromMessages` | **Full Parity** |

**Gap Assessment**: **Full parity**. Scala version is more type-safe.

---

## 8. Compose / Orchestration Layer

This is the most complex comparison area.

### 8.1 Runnable Abstraction

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Invoke (I => O) | `Runnable.Invoke()` | adk4s `Runnable[I, O].invoke()` | **Full Parity** |
| Stream (I => Stream[O]) | `Runnable.Stream()` | adk4s `Runnable[I, O].stream()` | **Full Parity** |
| Collect (Stream[I] => O) | `Runnable.Collect()` | adk4s `Runnable[I, O].collect()` | **Full Parity** |
| Transform (Stream[I] => Stream[O]) | `Runnable.Transform()` | adk4s `Runnable[I, O].transform()` | **Full Parity** |
| Lambda builder | `compose.InvokableLambda`, `compose.StreamableLambda` | adk4s `Lambda.invoke`, `Lambda.stream`, etc. | **Full Parity** |
| Runnable from components | Auto-convert ChatModel/Tool/etc. | adk4s `ChatModel.toRunnable`, `ToolsNode.toRunnable` | **Full Parity** |
| Passthrough runnable | `compose.Passthrough[T]` | adk4s `Runnable.passthrough[I]` | **Full Parity** |
| Batch execution | Not built-in to Runnable | adk4s `BatchExecutor.fromRunnable` | **Scala Advantage** |

**Gap Assessment**: **Full parity**. Both frameworks implement the same 4-mode Runnable pattern.

### 8.2 Graph System

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| DAG mode | `compose.NewGraph` with `GraphTypeDAG` | adk4s `WIOGraph` (acyclic enforced) | **Full Parity** |
| Cyclic graph (Pregel) | `compose.NewGraph` with `GraphTypePregel` | Not supported | **No Parity** |
| START/END sentinel nodes | `compose.START`, `compose.END` | adk4s `NodeKey.START`, `NodeKey.END` | **Full Parity** |
| Add nodes | `graph.AddLambdaNode`, `graph.AddChatModelNode`, etc. | `graph.addNode`, `graph.addRunnableNode` | **Full Parity** |
| Add edges | `graph.AddEdge(from, to)` | `graph.addEdge(from, to)` | **Full Parity** |
| Conditional branching | `graph.AddBranch(node, branch)` | adk4s `Branch` + `ForkSpec` | **Full Parity** |
| Type-safe edges | Runtime type checking | Compile-time type alignment via `WIONodeRef[Ctx, I, O]` | **Scala Advantage** |
| Graph compilation | `graph.Compile()` returns `Runnable` | `graph.toWIO()` or `graph.toRunnable()` | **Full Parity** |
| Cycle detection | Runtime validation | `validateNoCycles()` at compile time | **Full Parity** |
| Node modifiers (checkpoint, retry, interrupt) | Via compose layer options | adk4s `WIONodeModifier` (CheckpointModifier, RetryModifier, InterruptionModifier) | **Full Parity** |
| Sub-graph embedding | Via nested graph compilation | adk4s `WIONode.subGraph(graph)` | **Full Parity** |
| Parallel nodes | Via Pregel model | adk4s `WIOParallelNode` | **Partial Parity** |
| ForEach iteration | Not built-in graph primitive | adk4s `WIOForEachNode` | **Scala Advantage** |

**Gap Assessment**: The main gap is **Pregel/cyclic graph** support. Eino allows cyclic graphs (e.g., for ReAct loops at the graph level), while adk4s enforces DAG-only. The Scala side compensates with stronger compile-time type safety.

**To fill the gap**:
- Consider adding a `PregelGraph` variant that allows cycles with a configurable max-steps termination condition
- Alternatively, loops can be modeled via `WIONode.loop` (which exists), but embedding them at the graph topology level would be more flexible

### 8.3 Chain Builder

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Chain builder pattern | `compose.NewChain` with `Append*` methods | Not a separate builder (use graph or WIO composition) | **No Parity** |
| Append ChatModel | `chain.AppendChatModel(node)` | Via graph nodes or direct WIO composition | **Partial Parity** |
| Append ToolsNode | `chain.AppendToolsNode(node)` | Via graph nodes | **Partial Parity** |
| Append Lambda | `chain.AppendLambda(lambda)` | Via graph nodes | **Partial Parity** |
| Append Retriever | `chain.AppendRetriever(node)` | Via graph nodes | **Partial Parity** |
| Parallel branches in chain | `chain.AppendParallel(...)` | Via WIOParallelNode | **Partial Parity** |

**Gap Assessment**: Eino's `Chain` is a convenience builder that provides a linear composition API. adk4s achieves the same via `WIOGraph` edge composition, but a dedicated `Chain` builder could improve ergonomics.

**To fill the gap**:
- Create a `Chain[I, O]` builder that provides `append`, `appendChatModel`, `appendToolsNode` methods
- Under the hood, it builds a linear `WIOGraph` or composes Runnables directly

### 8.4 Workflow Builder

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Workflow with dependencies | `compose.NewWorkflow` with `AddEnd(deps)` | adk4s `WIOGraph` with edges | **Full Parity** |
| Field mapping between nodes | `compose.WithFieldMapping(from, to, mapping)` | Not supported | **No Parity** |
| Auto-parallel from deps | Inferred from dependency graph | Via WIOParallelNode | **Partial Parity** |

**Gap Assessment**: Eino's **field mapping** system allows remapping output fields from one node to input fields of another, which is useful when node I/O types don't exactly match. adk4s requires type-aligned edges.

**To fill the gap**:
- Add a `FieldMapping[A, B]` concept that allows mapping specific fields between nodes
- Could be implemented as a lightweight transform node inserted automatically on edges

### 8.5 State Management

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Graph-level state | `compose.WithState` with pre/post handlers | adk4s `StatefulNode` with `StateRef` | **Full Parity** |
| State pre-handler | `StatePreHandler` | `StatefulNodeConfig.preHandler` | **Full Parity** |
| State post-handler | `StatePostHandler` | `StatefulNodeConfig.postHandler` | **Full Parity** |
| Stream state handlers | `StreamStatePreHandler`, `StreamStatePostHandler` | `StatefulNodeConfig.streamPreHandler/streamPostHandler` | **Full Parity** |
| Event sourcing | Not in Eino core | adk4s `AdkWorkflowContext` (initialState, applyEvent) + workflows4s | **Scala Advantage** |

**Gap Assessment**: **Full parity or better**. adk4s has event sourcing capabilities via workflows4s that Eino lacks.

### 8.6 Checkpointing

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Checkpoint store interface | `compose.CheckPointStore` (Get/Set/Clear) | adk4s `CheckpointStore[F]` (save/load/delete) | **Full Parity** |
| Checkpoint serialization | `compose.Serializer` | Via gob encoding (Eino ADK) / event sourcing (adk4s) | **Full Parity** |
| Checkpoint ID management | `compose.CheckPointID` | adk4s WIOGraph checkpoint modifier | **Full Parity** |
| In-memory checkpoint | Built-in | `CheckpointStore.inMemory` | **Full Parity** |

---

## 9. Agent Framework

### 9.1 ReAct Agent

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| ReAct loop (reason + act) | `flow/agent/react.NewAgent` | adk4s `ReactAgent.create(model, tools, ...)` | **Full Parity** |
| Max steps limit | Via config | `ReactAgent.Config.maxSteps` | **Full Parity** |
| System prompt | Via config | `ReactAgent.Config.systemPrompt` | **Full Parity** |
| Tool result middleware | `ToolResultMiddleware` (Eino ADK) | adk4s `ToolMiddleware` | **Full Parity** |
| Dynamic tool provider | Not built-in | adk4s `ReactAgent.createWithToolProvider` + `DynamicToolRegistry` | **Scala Advantage** |
| Streaming agent output | Via `StreamReader[*Message]` | adk4s `ReactAgent.stream()` returns `Stream[IO, StreamedChunk]` | **Full Parity** |

**Gap Assessment**: **Full parity or better**. adk4s has dynamic tool providers.

### 9.2 ADK Agent Interface

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Agent interface (Name, Description, Run) | `adk.Agent` | llm4s `Agent` class | **Partial Parity** |
| AsyncIterator for events | `AsyncIterator[*AgentEvent]` | llm4s `runWithEvents(onEvent: AgentEvent => Unit)` | **Partial Parity** |
| AgentEvent with Output + Action | `AgentEvent` (Output, Action, Err, RunPath) | llm4s `AgentEvent` (TextDelta, ToolCall*, ErrorOccurred) | **Partial Parity** |
| Exit action | `AgentAction.Exit` | Implicit (loop ends) | **No Parity** |
| Transfer to agent action | `AgentAction.TransferToAgent` | llm4s `Handoff` mechanism | **Partial Parity** |
| Break loop action | `AgentAction.BreakLoop` | Not supported | **No Parity** |
| Custom action | `AgentAction.CustomizedAction` | Not supported | **No Parity** |
| Run path tracking | `RunPath []RunStep` (hierarchical execution path) | Not supported | **No Parity** |
| OnSubAgents interface | `OnSetSubAgents`, `OnSetAsSubAgent`, `OnDisallowTransferToParent` | Not supported | **No Parity** |

**Gap Assessment**: Eino's ADK has a significantly richer **agent action model** with explicit Exit, TransferToAgent, BreakLoop, and Custom actions. The `RunPath` tracking enables debugging of nested agent hierarchies. llm4s has a simpler agent model.

**To fill the gap**:
- Define an `AgentAction` sealed trait: `Exit`, `TransferToAgent(dest)`, `BreakLoop`, `CustomAction(payload)`
- Add `RunPath` (list of `RunStep`) to agent events for hierarchical tracking
- Add `OnSubAgents` lifecycle hooks for multi-agent coordination
- Adopt `AsyncIterator` pattern (or use `Stream[IO, AgentEvent]`) for event emission

### 9.3 Workflow Agent (Orchestration Patterns)

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Sequential agent workflow | `adk.WorkflowAgent` (sequential mode) | Not a dedicated agent type | **No Parity** |
| Parallel agent workflow | `adk.WorkflowAgent` (parallel mode) | Not a dedicated agent type | **No Parity** |
| Loop agent workflow | `adk.WorkflowAgent` (loop mode) | Not a dedicated agent type | **No Parity** |
| Agent-as-tool | `adk.NewAgentTool` (wrap agent as tool) | Not supported | **No Parity** |
| Deterministic transfer | `adk.DeterministicTransfer` | Not supported | **No Parity** |

**Gap Assessment**: Eino's ADK has **WorkflowAgent** which provides three execution modes (sequential, parallel, loop) for orchestrating sub-agents. The **agent-as-tool** pattern allows wrapping an entire agent as a tool callable by another agent. These are significant gaps.

**To fill the gap**:
- Implement `WorkflowAgent` with sequential/parallel/loop modes
- Implement `AgentTool` that wraps an `Agent` as an `InvokableTool[IO]`
- Implement `DeterministicTransfer` for static agent routing

### 9.4 Multi-Agent Host

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Multi-agent host coordination | `flow/agent/multiagent/host/` | llm4s basic `Handoff` mechanism | **Partial Parity** |
| Agent delegation via handoffs | Via TransferToAgent action | llm4s `Handoff.to(agent, description)` | **Partial Parity** |
| Parent/child agent relationship | `OnSubAgents` interface | Not supported | **No Parity** |

**Gap Assessment**: llm4s has basic handoff support but lacks the structured parent/child agent relationships and the host coordination pattern.

### 9.5 Prebuilt Agents

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Deep research agent | `adk/prebuilt/deep` | Not implemented | **No Parity** |
| Plan-execute agent | `adk/prebuilt/planexecute` | adk4s has PlanExecute example (structured LLM) | **Partial Parity** |
| Supervisor agent | `adk/prebuilt/supervisor` | Not implemented | **No Parity** |

**Gap Assessment**: Eino has prebuilt agent patterns for common use cases. adk4s has examples but not reusable components.

**To fill the gap**:
- Create `DeepResearchAgent` that performs iterative research with search + synthesis
- Formalize `PlanExecuteAgent` as a reusable component
- Create `SupervisorAgent` that coordinates multiple worker agents

---

## 10. Interrupt / Resume

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Basic interrupt | `adk.Interrupt(ctx, info)` | Not supported | **No Parity** |
| Stateful interrupt | `adk.StatefulInterrupt(ctx, info, state)` | Not supported | **No Parity** |
| Composite interrupt (nested) | `adk.CompositeInterrupt(ctx, info, state, subSignals...)` | Not supported | **No Parity** |
| Resume from interrupt | `ResumableAgent.Resume(ctx, resumeInfo)` | Not supported | **No Parity** |
| Interrupt contexts | `InterruptCtx` with address chain | Not supported | **No Parity** |
| Address segments | `AddressSegment` (agent/tool types) | Not supported | **No Parity** |
| Checkpoint persistence for resume | `saveCheckPoint`, `loadCheckPoint` with gob encoding | Not supported | **No Parity** |
| Human-in-the-loop pattern | Via Interrupt/Resume | Not supported | **No Parity** |

**Gap Assessment**: This is one of the **largest gaps**. Eino's interrupt/resume system enables human-in-the-loop workflows where an agent can pause execution, request human input, and resume from exactly where it stopped. This is critical for production agent systems.

**To fill the gap**:
- Implement `Interrupt[F]` trait with `interrupt(info)` and `statefulInterrupt(info, state)` methods
- Implement `ResumableAgent[F]` extending `Agent` with `resume(resumeInfo)` method
- Add `InterruptSignal` serialization for checkpoint persistence
- Add `CompositeInterrupt` for nested agent interrupt propagation
- Add `InterruptCtx` with hierarchical address tracking
- This is a **high-priority gap** for production readiness

---

## 11. Streaming Infrastructure

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Stream reader/writer | `schema.StreamReader`, `schema.StreamWriter`, `schema.Pipe` | fs2 `Stream[IO, A]` | **Full Parity** (different paradigm) |
| Stream copy (fan-out) | `StreamReader.Copy(n)` | fs2 `Stream.broadcastThrough` | **Full Parity** |
| Stream merge | `MergeStreamReaders` | fs2 `Stream.merge` | **Full Parity** |
| Named stream merge | `MergeNamedStreamReaders` | Not directly equivalent | **No Parity** |
| Stream conversion | `StreamReaderWithConvert` | fs2 `.map`, `.evalMap` | **Full Parity** |
| Auto-close on GC | `SetAutomaticClose()` | fs2 Resource/Bracket patterns | **Full Parity** (different approach) |
| Streaming LLM client | Via BaseChatModel.Stream | adk4s `StreamingLLMClient` wrapping llm4s | **Full Parity** |

**Gap Assessment**: Both frameworks have comprehensive streaming. Eino uses custom `StreamReader/StreamWriter` while adk4s uses fs2 `Stream`. fs2 is arguably more powerful (backpressure, resource safety, composability).

---

## 12. Callbacks / Observability

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Callback handler interface | `callbacks.Handler` (OnStart, OnEnd, OnError, OnStartWithStreamInput, OnEndWithStreamOutput) | Not in adk4s | **No Parity** |
| Global callbacks | Global handler registration | Not supported | **No Parity** |
| Per-node callbacks | Node-specific handlers | Not supported | **No Parity** |
| Lifecycle timing | Pre/post execution timing | Not supported | **No Parity** |
| Tracing integration | Via callback handlers | llm4s: Langfuse, Console, OpenTelemetry tracing | **Partial Parity** |
| Tracing interface | Not a separate concern | llm4s `Tracing` trait (traceEvent, traceToolCall, etc.) | **Scala Advantage** |
| Cost tracking | Not in core Eino | llm4s `traceCost()` | **Scala Advantage** |

**Gap Assessment**: Eino has a **callback system** at the compose layer that provides lifecycle hooks (OnStart, OnEnd, OnError) for every node. This is different from tracing — it's a way to observe and potentially modify execution at each step. llm4s has dedicated tracing (Langfuse, OpenTelemetry) which is more focused on observability. Both approaches have merit.

**To fill the gap**:
- Implement a `CallbackHandler[F]` trait with `onStart`, `onEnd`, `onError` methods
- Allow global and per-node callback registration
- Add stream-aware callbacks (OnStartWithStreamInput, OnEndWithStreamOutput)
- This complements the existing llm4s tracing system

---

## 13. Memory

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Memory manager interface | Not in Eino core | llm4s `MemoryManager` (store, record, retrieve, consolidate) | **Scala Advantage** |
| Memory types | Not in Eino core | llm4s: Conversation, EntityFact, UserPreference, Knowledge, TaskOutcome | **Scala Advantage** |
| Memory stores | Not in Eino core | llm4s: InMemory, SQLite, Postgres, Vector stores | **Scala Advantage** |
| Memory consolidation | Not in Eino core | llm4s `LLMMemoryManager` with LLM-based consolidation | **Scala Advantage** |
| Entity extraction | Not in Eino core | llm4s entity fact recording | **Scala Advantage** |
| Importance scoring | Not in Eino core | llm4s `Memory.importance` field | **Scala Advantage** |

**Gap Assessment**: **Scala advantage**. llm4s has a comprehensive memory system that Eino core lacks.

---

## 14. Guardrails

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Input guardrails | Not in Eino core | llm4s: Length, Profanity, Regex, PromptInjection | **Scala Advantage** |
| Output guardrails | Not in Eino core | llm4s: JSON, Tone, Quality validators | **Scala Advantage** |
| LLM-as-judge guardrails | Not in Eino core | llm4s: Safety, Factuality, Quality, Tone | **Scala Advantage** |
| RAG guardrails | Not in Eino core | llm4s: Grounding, SourceAttribution, ContextRelevance | **Scala Advantage** |
| PII detection/masking | Not in Eino core | llm4s `PIIDetector`, `PIIMasker` | **Scala Advantage** |
| Composite guardrails | Not in Eino core | llm4s `CompositeGuardrail` (all, any, sequential) | **Scala Advantage** |
| ADK middlewares | `adk/middlewares/` (Reduction, Skill, Filesystem) | Not directly equivalent | **No Parity** |

**Gap Assessment**: llm4s has a much richer guardrail system. However, Eino's ADK has **middlewares** (Reduction for large tool results, Skill middleware, Filesystem middleware) that serve different purposes.

**To fill the gap**:
- Implement Eino ADK-style middlewares:
  - `ReductionMiddleware` - Compresses large tool results
  - `SkillMiddleware` - Adds skill-based tool selection
  - `FilesystemMiddleware` - File system access tools

---

## 15. MCP (Model Context Protocol)

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| MCP client | Not in Eino core | llm4s: Full MCP client (tool retrieval, protocol negotiation) | **Scala Advantage** |
| MCP server | Not in Eino core | llm4s: MCP server hosting | **Scala Advantage** |
| MCP transport (stdio/websocket) | Not in Eino core | llm4s: Stdio + WebSocket transport | **Scala Advantage** |
| MCP tool registry | Not in Eino core | llm4s: `MCPToolRegistry` | **Scala Advantage** |

**Gap Assessment**: **Scala advantage**. llm4s has comprehensive MCP support.

---

## 16. Multimodal (Image, Audio, Video)

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Image generation | Not in Eino core | llm4s `ImageGeneration` (DALL-E 3) | **Scala Advantage** |
| Image processing/vision | Not in Eino core | llm4s `ImageProcessing` (GPT-4V, Claude vision) | **Scala Advantage** |
| Audio message support | `AudioContent` in messages | Not supported | **No Parity** |
| Video message support | `VideoContent` in messages | Not supported | **No Parity** |
| Text-to-speech | Not in Eino core | llm4s TTS support | **Scala Advantage** |
| Speech-to-text | Not in Eino core | llm4s STT support (Vosk) | **Scala Advantage** |

**Gap Assessment**: Mixed. llm4s has image generation/processing and speech capabilities. Eino has first-class audio/video content in messages. Both sides have gaps.

---

## 17. Structured Output

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Type-safe structured output | Not in Eino core (JSON schema only) | adk4s `StructuredLLM` with smithy4s schemas | **Scala Advantage** |
| Schema injection into prompts | Not built-in | adk4s `Prompt.withOutputFormat[A]` | **Scala Advantage** |
| Lenient JSON parsing (SAP) | Not built-in | adk4s `SchemaAlignedParser` with 10+ recovery strategies | **Scala Advantage** |
| Schema derivation | Via JSON Schema in ToolInfo | adk4s `Schema.instance` with Smithy IDL | **Scala Advantage** |
| Parse error recovery | Not built-in | adk4s SAP: markdown fences, trailing commas, single quotes, truncation repair | **Scala Advantage** |

**Gap Assessment**: **Major Scala advantage**. `StructuredLLM` with schema injection and SAP is the core innovation of adk4s and has no equivalent in Eino.

---

## 18. Batch Processing

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Batch executor | Not built-in | adk4s `BatchExecutor` (sequential, parallel, streaming) | **Scala Advantage** |
| Configurable concurrency | N/A | `BatchExecutor.invokeAllPar(inputs, concurrency)` | **Scala Advantage** |
| Stream-based batch | N/A | `BatchExecutor.stream(inputs, concurrency)` | **Scala Advantage** |

**Gap Assessment**: **Scala advantage**.

---

## 19. Context Management

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Context window management | Not in Eino core | llm4s `ContextManager` (add, compress, getAvailableTokens) | **Scala Advantage** |
| History compression | Not in Eino core | llm4s: Deterministic, LLM-based, Tool output compressors | **Scala Advantage** |
| Token window sliding | Not in Eino core | llm4s `TokenWindow` | **Scala Advantage** |
| Semantic blocks | Not in Eino core | llm4s `SemanticBlocks` | **Scala Advantage** |

**Gap Assessment**: **Scala advantage**. llm4s has comprehensive context management.

---

## 20. Error Handling

| Feature | Eino | adk4s/llm4s | Status |
|---------|------|-------------|--------|
| Error types | Go `error` interface | adk4s `AdkError` sealed trait (15+ variants) | **Scala Advantage** |
| Typed error ADT | Not structured | `LlmCallError`, `ToolNotFoundError`, `MaxStepsExceededError`, etc. | **Scala Advantage** |
| Error recovery | Not built-in | llm4s `ErrorRecovery` with retry strategies | **Scala Advantage** |
| Recoverable vs Non-recoverable | Not classified | llm4s `RecoverableError`, `NonRecoverableError` traits | **Scala Advantage** |

**Gap Assessment**: **Scala advantage** due to sealed trait ADT for exhaustive error handling.

---

## Priority Gap Summary

### Critical Gaps (High Priority)

| # | Feature | Impact | Effort |
|---|---------|--------|--------|
| 1 | **Interrupt/Resume (Human-in-the-Loop)** | Essential for production agent systems | High |
| 2 | **WorkflowAgent (Sequential/Parallel/Loop)** | Core agent orchestration pattern | Medium |
| 3 | **Agent-as-Tool** | Enables hierarchical agent composition | Medium |
| 4 | **AgentAction model** (Exit, Transfer, BreakLoop) | Richer agent control flow | Medium |

### Important Gaps (Medium Priority)

| # | Feature | Impact | Effort |
|---|---------|--------|--------|
| 5 | **Cyclic Graph (Pregel mode)** | Enables graph-level loops | High |
| 6 | **Callback system** (OnStart, OnEnd, OnError per node) | Observability at compose layer | Medium |
| 7 | **Streaming/Enhanced tools** (multimodal tool results) | Richer tool capabilities | Medium |
| 8 | **Multi-query/Parent/Router retrievers** | Advanced RAG patterns | Medium |
| 9 | **Chain builder** (convenience API) | Developer ergonomics | Low |
| 10 | **RunPath tracking** | Debugging nested agents | Low |

### Lower Priority Gaps

| # | Feature | Impact | Effort |
|---|---------|--------|--------|
| 11 | **Audio/Video content in messages** | Multimodal support | Medium |
| 12 | **Reasoning content** (chain-of-thought) | o1/o3 model support | Low |
| 13 | **Field mapping between nodes** | Graph flexibility | Medium |
| 14 | **MessagesPlaceholder** in templates | Template ergonomics | Low |
| 15 | **Prebuilt agents** (Deep Research, Supervisor) | Reusable patterns | High |
| 16 | **ADK-style middlewares** (Reduction, Skill, Filesystem) | Agent capabilities | Medium |

### Scala Advantages (No Action Needed)

| Feature | Notes |
|---------|-------|
| StructuredLLM + SAP | Core differentiator, no Eino equivalent |
| 8 LLM providers | More than Eino core |
| Tool middleware (logging, timing, retry, validation) | Richer than Eino |
| Dynamic tool registry | Thread-safe with Cats Effect Ref |
| Comprehensive guardrails system | 15+ guardrail types |
| MCP client/server | Full protocol support |
| Memory system (5 types, 4 stores) | Not in Eino core |
| Context management + compression | Not in Eino core |
| Image generation + Vision + Speech | Not in Eino core |
| Batch processing | Not in Eino core |
| Event sourcing via workflows4s | Unique capability |
| Type-safe graph edges (compile-time) | Runtime in Eino |
| Sealed trait error ADT | Go uses plain errors |
| Reranking (Cohere, LLM-based) | Not in Eino core |
| Hybrid search (vector + keyword) | Not in Eino core |
| RAG evaluation metrics | Not in Eino core |
| Tool schema derivation from Smithy | Manual in Eino |

---

## Conclusion

The adk4s + llm4s ecosystem has **strong parity or advantages** in:
- LLM client (more providers)
- Structured output (StructuredLLM is unique)
- Tool system (middleware, dynamic registry, schema derivation)
- RAG infrastructure (chunking, reranking, hybrid search, evaluation)
- Memory and context management
- Guardrails
- MCP support
- Multimodal generation (images, speech)
- Batch processing
- Error handling

The **critical gaps** to address are:
1. **Interrupt/Resume** for human-in-the-loop workflows
2. **WorkflowAgent** for sequential/parallel/loop agent orchestration
3. **Agent-as-tool** for hierarchical agent composition
4. **Richer AgentAction model** with explicit Exit/Transfer/BreakLoop actions

These gaps are concentrated in the **ADK (Agent Development Kit)** layer, which is Eino's most recent and most sophisticated addition. Filling these gaps would bring the Scala ecosystem to full feature parity with Eino while retaining its existing advantages in type safety, structured output, and functional programming foundations.
