# Open Points: Tools/Subagents Unification and Absence of Shared Session State

> Research conducted on 2026-08-09. Covers two related architectural
> observations about adk4s: (1) the unified tools/subagents design and its
> documented rationale, and (2) the absence of a shared session state across
> multi-agent conversations and whether that absence is justified.

---

## Part 1 — Unified Tools/Subagents Design

### Observation

adk4s treats subagents as tools (via the `AgentTool` adapter) rather than
maintaining a structural separation between "tools" and "subagents." An
agent has exactly one capability field — `tools: List[InvokableTool[IO]]` —
and agents that should act as subagents are wrapped with
`AgentTool.fromAgent()` and added to that same list.

### Is this deliberate and documented?

**Yes.** The choice is explicit in the archived OpenSpec change
`2026-02-10-agent-orchestration-gaps`, Decision 1:

> **Choice:** `AgentTool` is a concrete class that extends `InvokableTool[IO]`.
>
> **Rationale:** This is the simplest integration path. The parent agent's
> `ToolsNode` already knows how to execute `InvokableTool[IO]`. By making
> `AgentTool` implement the same interface, no changes are needed to the core
> tool dispatch path — an AgentTool looks like any other tool to the LLM and
> to ToolsNode.
>
> **Alternative considered:** A separate `AgentToolNode` in the graph layer.
> Rejected because it couples agent delegation to the graph system. The
> tool-level abstraction is more composable — an AgentTool can be used in
> ReactAgent, in WIOGraph, or standalone.

Source: `openspec/changes/archive/2026-02-10-agent-orchestration-gaps/design.md` (lines 27-33)

### Code evidence

- `ReactAgent.Config` has only `tools: List[InvokableTool[IO]]` — no separate
  subagents parameter.
  `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala` (lines 38-44)
- `AgentTool` is `final class ... extends InvokableTool[IO]`.
  `adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala` (lines 62-67)
- `ToolsNodeConfig` stores everything in one list:
  `tools: List[Either[ToolWrapper, InvokableTool[IO]]]`. The `Either`
  distinguishes llm4s native tools from ADK tools, **not** tools from agents.
  `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNodeConfig.scala` (lines 13-21)
- `withAgentTool` is a convenience alias for `withAdkTool` — both append
  `Right(tool)` to the same list.
  `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNodeConfig.scala` (lines 58-62)

### Documented rationale (distilled)

1. **Simplicity / zero dispatch-path changes** — `ToolsNode` already executes
   `InvokableTool[IO]`; making `AgentTool` implement that interface means no
   new code path.
2. **Composability** — a tool-level abstraction works in `ReactAgent`,
   `WIOGraph`, or standalone; a graph-layer `AgentToolNode` would couple
   delegation to the graph engine.
3. **LLM-driven dispatch** — the routing decision is made by the LLM at
   runtime via tool-call selection, which maps naturally onto "everything is
   a tool the model can choose."

Source: `docs/agent-orchestration-gap-analysis.md` (lines 33-59)

### Known wrinkle

The concept registry notes that the unification is not perfectly uniform in
practice — `ToolsNode` pattern-matches on `AgentTool` to inject a scoped
`AgentEventEmitter`, which is a documented deviation from uniform tool
handling.

Source: `openspec/concepts/tools-node.md` (line 72)

### Comparison with other frameworks

The unified "agents-as-tools" approach is the majority pattern, not an adk4s
oddity:

| Framework | Approach | Notes |
|---|---|---|
| LangChain / LangGraph | Unified | Official "subagents" pattern: supervisor calls workers as tools. |
| AutoGen | Unified | Provides an `AgentTool` wrapper — nearly identical to adk4s's design. |
| CrewAI | Unified | Auto-generates `Delegate Work` / `Ask Question` tools from peer agents. |
| Google ADK (Python) | Unified | `transfer_to_agent` is a tool; rationale: "Transferring to another agent is an action, therefore it's considered as a tool." |
| LlamaIndex | Unified | Orchestrator pattern wraps each agent's `run` as a `QueryEngineTool`. |
| OpenAI Agents SDK / Swarm | Both | Offers `agent.as_tool()` (unified) **and** handoffs (separated). Distinction is about ownership of the final answer, not capability. |
| Semantic Kernel | Separated | Plugins (capabilities) vs Agents (orchestration) are distinct, aimed at enterprise scenarios. |

Key insight from OpenAI docs: the tool-vs-handoff distinction is about **who
owns the conversation**, not about technical capability. adk4s implements
only the "agents-as-tools" model. It does **not** currently offer a
handoff/transfer primitive where a subagent assumes ownership of the
conversation — a genuine gap relative to OpenAI's SDK and Google ADK.

There is **no documented comparison with other frameworks** in the repo. The
reference framework cited is Eino (a Go ADK).

---

## Part 2 — Absence of Shared Session State

### Observation

adk4s does **not** expose a shared session state (or conversation memory) that
spans a whole multi-agent conversation. When a parent agent delegates to
subagent B and later to subagent C, B and C cannot share state through any
framework-provided mechanism — they communicate only through the parent's LLM
reasoning over tool return values.

### Is this correct?

**Yes.** When a parent agent calls a subagent via `AgentTool`, the subagent
receives **only the `request` string** extracted from the tool arguments. The
parent's conversation history is not passed.

`AgentTool.run` and `AgentTool.buildMessages`:
`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala` (lines 92-114)

The `stateRef` on each `AgentTool` is private to that tool instance and exists
solely for interrupt/resume persistence — it is not a shared blackboard.

### Existing state abstractions (none serve as shared session state)

| Abstraction | Location | What it is | Shared across agents? |
|---|---|---|---|
| `AgentTool.stateRef` | `adk4s-core/.../AgentTool.scala` | Private `Ref[IO, Option[AgentToolState]]` for interrupt/resume | No — per-tool |
| `CheckpointStore` | `adk4s-orchestration/.../interrupt/` | Blob store for interrupt/resume snapshots | No — per-checkpoint-ID |
| `StateRef` / `StatefulNode` | `adk4s-orchestration/.../state/` | Per-node mutable state with pre/post handlers | No — per-node |
| `EventSourcedState` / `AgentStateContext` | `adk4s-orchestration/.../state/` | workflows4s WIO event-sourced state | No — per-workflow |
| `GraphWorkflowContext` | `adk4s-orchestration/.../execution/` | Type-erased (`State = Any`) WIO context | No — per-workflow |
| `AgentEventEmitter` | `adk4s-core/.../interrupt/` | fs2 event queue for observability | No — event routing only |
| `HarnessState` / `StateCell` | `adk4s-harness-api/.../` | Typed heterogeneous map with `Private`/`Inherited`/`Shared` visibility | **Potentially yes**, but not wired to `AgentTool` — see below |
| `AgentMemory` | `adk4s-memory-api/.../` | Cross-run semantic memory (remember/recall) | No — opt-in decorator, prompt-injected, not direct state |

### Is the absence documented or justified?

**No.** There is no ADR, design decision, or architectural justification for
the absence of shared session state. The docs research was exhaustive and
found zero matches. What the docs *do* contain is a mix of
acknowledgments-of-limitation and forward-looking work:

#### a) `withFullChatHistory` is a known limitation, not a design choice

The `AgentToolConfig.withFullChatHistory` flag exists but is non-functional:

```scala
/** NOTE: withFullChatHistory is not yet functional - parent conversation is not passed to AgentTool.run().
  * This flag is reserved for future implementation. Currently all invocations use only the request field. */
```

Source: `adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala` (lines 20-22)

The limitations doc classifies this as a **limitation**, with a workaround and
a future-work estimate of "1-2 days":

> - Inner agents cannot see parent conversation context
> - Inner agents only receive the request field from tool arguments
> - Works for stateless tools that don't need conversation history
> - Limits context-aware agent delegation

Source: `docs/agent-orchestration-limitations.md` (lines 77-129)

This is framed as "not yet built," not as "deliberately omitted."

#### b) Cross-run memory is acknowledged as absent, then added as opt-in

The memory API doc opens by stating the gap:

> ADK4S agents are currently *stateless across runs*. [...] There is no
> contract for **durable, cross-session, semantically-searchable memory**

But the justification given is about **implementation weight** (not bundling
Neo4j/Lucene/embeddings), not about whether shared in-conversation state is a
good idea:

> We deliberately do **not** put a memory *implementation* in ADK4S. A real
> temporal knowledge-graph backend [...] pulls in Neo4j, Lucene, an embedding
> client, and a connection pool. [...] Instead we add a **lightweight
> capability interface**.

Source: `docs/adk4s-memory-api.md` (lines 19-30)

This justifies *where* the abstraction lives, not *why* there's no shared
session state.

#### c) `HarnessState` is the closest thing to a future session-state mechanism

The deepagents4s Phase 0 design doc describes `HarnessState` with three
visibility levels and lattice-based merge for parallel delegation:

> A typed, enumerable, serializable `HarnessState` replacing Python's
> `TypedDict`-merging [...]
> Sub-agent state isolation with *three* visibility levels (deepagents has
> two), with lattice-based merge for parallel delegation.

Source: `docs/deepagents4s-phase0-agent-middleware-DESIGN.md` (lines 50-53)

This is explicitly modeled on Google's **deepagents** framework (which itself
builds on Google ADK's `session.state`). But the design doc never uses the
word "session" or "sessionState" — it frames everything as "middleware state"
and "harness state." And critically, the non-goals section says:

> No concrete harness middlewares (todos, filesystem, sub-agents, ...) — those
> are Phases 1-3.

Source: `docs/deepagents4s-phase0-agent-middleware-DESIGN.md` (lines 62-66)

So sub-agent state sharing via `HarnessState` is explicitly deferred to a
later phase.

#### d) The gap analyses do not flag session state as a gap

Neither `docs/agent-orchestration-gap-analysis.md` nor
`docs/gap-analysis-eino-vs-adk4s.md` mentions session state, shared context,
or conversation memory as a gap versus Eino or any other framework.

### Comparison with other frameworks

| Camp | Frameworks | Mechanism |
|---|---|---|
| **Direct shared mutable state** | Google ADK (`session.state`), LangGraph (shared `State` with reducers) | Subagents read/write a shared dict/state object directly |
| **Message passing / context isolation** | AutoGen (actor model), OpenAI Agents SDK (Context + history), Semantic Kernel (`AgentThread`) | No shared store; agents communicate via messages/tool returns |
| **Hybrid: shared memory via tools** | CrewAI (unified `Memory` with `RecallMemoryTool`/`RememberTool`) | Shared store exists but is accessed through LLM tool calls |

adk4s currently sits firmly in the **context isolation** camp — the same camp
as AutoGen and the OpenAI Agents SDK's "agents-as-tools" pattern. This is
consistent with its unified tools/subagents design: since subagents are tools,
and tools communicate via call/return, subagents inherit that same call/return
isolation.

However, adk4s is **more isolated than its peers** in one respect: even the
OpenAI SDK passes a shared `Context` object to every agent and tool in a run,
and AutoGen agents can message each other directly. adk4s gives the subagent
*only* the request string — not even the parent conversation history (the
`withFullChatHistory` flag is non-functional).

The `HarnessState` work-in-progress suggests adk4s is **moving toward** the
Google ADK / deepagents model (shared state with visibility controls), but it
has not arrived there yet, and it is not wired into `AgentTool`.

---

## Summary

| Question | Answer |
|---|---|
| Is the unified tools/subagents design deliberate? | **Yes**, explicitly documented in `openspec/changes/archive/2026-02-10-agent-orchestration-gaps/design.md`. |
| Is the unified design mainstream? | **Yes** — 5 of 7 major frameworks use the same pattern. Only Semantic Kernel structurally separates them; OpenAI offers both. |
| Does adk4s expose shared session state for multi-agent conversations? | **No.** Subagents receive only the request string. No shared blackboard exists. |
| Is the absence of session state justified in the docs? | **No.** It is acknowledged as a limitation (`withFullChatHistory` not functional) and as a deferred phase (`HarnessState` sub-agent middleware is "Phases 1-3"), but never defended as an architectural decision. |
| Is there a trajectory toward shared state? | **Yes.** `HarnessState`/`StateCell` (Phase 0 of the deepagents4s port) provides the machinery (visibility levels, lattice merge), but it is not yet connected to `AgentTool`. |

### Open points for the project

1. **Document the rationale** (or acknowledge the gap) for the absence of
   shared session state, rather than leaving it implicit.
2. **Wire `HarnessState` into `AgentTool`** so that subagents can access
   shared state with visibility controls (Phase 1-3 work).
3. **Implement `withFullChatHistory`** so subagents can at least see parent
   conversation context (estimated 1-2 days per the limitations doc).
4. **Consider a handoff/transfer primitive** alongside the existing
   agents-as-tools pattern, as OpenAI's SDK and Google ADK do, for scenarios
   where a subagent should own the conversation rather than return a bounded
   artifact.
5. **Add session state to the gap analysis** against Eino and other
   frameworks, since it is currently not flagged.
