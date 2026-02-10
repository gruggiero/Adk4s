# Agent Orchestration Gap Analysis: Eino ADK vs adk4s+workflows4s

**Date**: 2026-02-08
**Focus**: Agent Framework (Section 9) and Interrupt/Resume (Section 10) from the full gap analysis

---

## Context

The philosophy of adk4s is using workflows4s for orchestrating agents. This analysis examines which concrete use cases Eino's ADK enables that are NOT currently possible with adk4s+workflows4s, considering workflows4s's existing primitives.

## workflows4s Primitives Already Covering Eino Patterns

| Eino Pattern | workflows4s Equivalent | Status |
|---|---|---|
| Sequential agents | `andThen` / `>>>` chaining | Fully supported |
| Parallel agents | `Parallel` / `ForEach` | Fully supported |
| Agent loop | `Loop` with `stopCondition` | Fully supported |
| Human-in-the-loop | `HandleSignal` (request-response) | Fully supported |
| Timeout/fallback | `Timer.toInterruption()` | Fully supported |
| Crash recovery + resume | `Checkpoint` + `JournalPersistance` (event sourcing) | Fully supported (stronger than Eino) |
| Retry with backoff | `Retry.statelessly.wakeupIn(...)` | Fully supported |
| Sub-agent embedding | `Embedded` with `WorkflowEmbedding` | Fully supported |
| Multi-agent signaling | `ForEach` + `SignalRouter.Receiver` | Fully supported |
| Branching on LLM output | `Fork` with condition on state | Fully supported |
| Deterministic transfer | Sequential chaining (trivial) | Fully supported |
| Plan-Execute-Replan | `andThen(planner, Loop(executor, replanner))` | Fully supported |

workflows4s is actually **stronger** than Eino in durability: full event sourcing with deterministic replay, Doobie/Pekko persistence backends, and typed error handling. Eino's checkpoint is simpler gob serialization.

---

## Gap 1: LLM-Driven Dynamic Agent Dispatch (Agent-as-Tool)

### The Eino Pattern

```
ReAct Agent (with tools)
  ├── search_tool        (regular tool)
  ├── calculator_tool    (regular tool)
  ├── database_agent     (AGENT wrapped as tool)
  └── code_agent         (AGENT wrapped as tool)
```

The LLM sees all four as tools. It dynamically decides: "I need to query the database" → calls `database_agent` tool → that agent runs its OWN ReAct loop with its own tools → returns result as tool output → parent continues.

### Why workflows4s Can't Do This Today

workflows4s graphs are **statically wired at build time**. A `Fork` requires `condition: In => Option[BranchIn]` — a pure function. But in the agent-as-tool pattern, the routing decision is made BY THE LLM at runtime via tool call selection. The LLM output contains `ToolCall(name="database_agent", args="...")` and the framework must dynamically dispatch to the correct sub-agent.

adk4s's `ReactAgent` + `ToolsNode` already does dynamic tool dispatch in-memory, but it's not wired into workflows4s's durable orchestration. The gap is the **bridge between LLM tool call selection and workflows4s graph routing**.

### What This Blocks

- Supervisor pattern (supervisor LLM picks specialist dynamically)
- Deep research agent (delegates tasks to sub-agents via tool calls)
- Hierarchical agent nesting (agent A calls agent B as a tool, B calls C)
- Any pattern where the LLM decides which sub-agent to invoke

### Possible Approach

The pre-wired approach works if the set of sub-agents is known at build time (which it usually is). The fork condition inspects the LLM's tool call:

```scala
// Pseudo-code
val dispatchFork = WIO.fork[AgentState]
  .addCase(s => s.lastToolCall.exists(_.name == "database_agent"))(databaseAgentWorkflow)
  .addCase(s => s.lastToolCall.exists(_.name == "code_agent"))(codeAgentWorkflow)
  .addCase(s => s.lastToolCall.isEmpty)(returnResult)
```

This is achievable with current workflows4s but requires **explicit wiring per agent** — not as ergonomic as Eino's `NewAgentTool(agent)`.

---

## Gap 2: Nested Interrupt Propagation Across Agent-Tool Boundaries

### The Eino Pattern

```
Runner.Run(messages)
  → Supervisor agent running
    → LLM calls database_agent tool
      → database_agent runs its own loop
        → database_agent calls query_tool
          → query_tool needs user approval
          → Interrupt(ctx, "Approve query: SELECT * FROM users?")
      ← CompositeInterrupt propagates up through database_agent
    ← CompositeInterrupt propagates up through Supervisor
  ← Runner saves checkpoint with full hierarchy

Runner.ResumeWithParams(checkpointID, {
  Address["supervisor", "database_agent", "query_tool"]: ApprovalData("approved")
})
  → Resume flows DOWN through hierarchy to exact interrupt point
  → query_tool receives approval
  → database_agent continues its loop
  → Supervisor continues
```

### Why workflows4s Can't Do This Today

workflows4s has `HandleSignal` which pauses a workflow waiting for external input — this IS the interrupt/resume mechanism. And `Embedded` + `ForEach` with `SignalRouter` can route signals to specific sub-workflows.

But there's a fundamental difference: in workflows4s, signals are delivered to a **specific workflow instance** at a **known signal point**. The caller must know which `SignalDef` to deliver and to which workflow.

In Eino's model, the interrupt bubbles UP through arbitrary nesting (agent → agent-tool → agent → tool), and resume flows DOWN with address-based routing. The nesting depth is dynamic (depends on which tools the LLM called), and the interrupt can happen at any point in a nested agent's execution.

workflows4s's `HandleInterruption` is about EXTERNAL signals interrupting a running workflow (e.g., user cancels). It's not about a NESTED sub-workflow requesting human input and propagating that request up.

### What This Blocks

- Human approval gates inside nested agent hierarchies
- Tool-level confirmation requests that propagate to the user
- Long-running nested agent processes that need checkpointing at arbitrary nesting depth

### Possible Approach

The building blocks exist in workflows4s (`HandleSignal`, `Embedded`, `SignalRouter`), but a **convention layer** is needed:
1. Define a standard `InterruptSignal` / `ResumeSignal` signal pair
2. When a sub-workflow (embedded agent) needs human input, it waits on `InterruptSignal`
3. The parent workflow detects this (via the embedded workflow's state) and propagates up
4. Resume delivers the signal down through the hierarchy

This is more of an **architectural pattern** than a new primitive.

---

## Gap 3: Real-Time Event Streaming from Nested Agents

### The Eino Pattern

```
Runner.Run(messages) → AsyncIterator[AgentEvent]

Events emitted in real-time:
  AgentEvent{agent="supervisor", output="I'll delegate to database agent"}
  AgentEvent{agent="database_agent", output="Querying customer info..."}
  AgentEvent{agent="database_agent", output=ToolCall("query", "SELECT...")}
  AgentEvent{agent="database_agent", output="Found 3 records"}
  AgentEvent{agent="supervisor", output="Now analyzing results..."}
```

Each event carries `RunPath` (e.g., `[supervisor → database_agent]`) so the UI knows which agent is "speaking".

### Why workflows4s Can't Do This Today

workflows4s is **event-sourced** — events are persisted for replay and state reconstruction. But these are _internal state-transition events_, not _user-facing observation events_. There's no built-in mechanism to stream "agent X is currently doing Y" to an external observer in real-time.

The `WorkflowInstance` API exposes `queryState()` (poll current state) and `getProgress()` (execution progress), but not a live event stream.

### What This Blocks

- Real-time UI showing agent progress
- Streaming agent "thoughts" to the user as they happen
- Debugging nested agent execution in real-time
- Token-by-token streaming from LLM calls within workflows

### Possible Approach

An observation/callback layer, either:
1. A `Stream[IO, AgentEvent]` that emits events as workflow steps execute
2. A callback hook on `RunIO` / `HandleSignal` steps that publishes to an external observer
3. Integration with adk4s's existing `StreamingLLMClient` at the node level

---

## Summary

| Gap | Severity | Effort | Workaround Available? |
|---|---|---|---|
| **LLM-driven dynamic agent dispatch** | High | Medium | Yes — pre-wire all agents in Fork, use LLM tool call name as fork condition |
| **Nested interrupt propagation** | Medium | Medium | Yes — use HandleSignal + Embedded + convention layer |
| **Real-time event streaming** | Medium | Low | Yes — callback wrapper around workflow execution |

**None of these are fundamental blockers.** workflows4s has all the low-level primitives needed. What's missing is the **agent-specific convenience layer** — the glue code that connects LLM tool calls to workflow routing, propagates interrupts through nesting, and streams events to observers.

The philosophy of "use workflows4s for orchestrating agents" is sound — it gives durability, event sourcing, typed error handling, and signal-based human interaction that Eino's simpler in-memory model doesn't match.
