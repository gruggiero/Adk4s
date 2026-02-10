## Context

adk4s currently has a working agent layer: `ReactAgent` runs a ReAct loop using `ChatModel[IO]` and `ToolsNode`, `InvokableTool[IO]` provides the tool abstraction, and `CheckpointStore` exists for state persistence. The workflows4s integration provides `WIOGraph` with event-sourced orchestration.

However, the agent layer is "flat" — there's no way for one agent to delegate to another agent at runtime (the LLM decides), no way for a nested tool/agent to pause and request human input, and no way to observe what's happening inside agent execution in real time.

This design adds three interlocking capabilities on top of the existing infrastructure, keeping the surface area minimal and backward-compatible.

## Goals / Non-Goals

**Goals:**
- Enable LLM-driven dynamic agent dispatch: an LLM selects which sub-agent to invoke via tool calls
- Enable human-in-the-loop: agents can pause, persist state, and resume when given external input
- Enable real-time observability: consumers can subscribe to a stream of agent execution events
- Maintain backward compatibility: existing ReactAgent and ToolsNode code continues to work unchanged
- Keep it functional and pure: all state via `Ref[IO, _]`, all effects via `IO`, all streaming via `fs2.Stream`

**Non-Goals:**
- Building a full multi-agent framework with supervisors, parallel agents, or workflow agents (these are orchestration patterns that compose from these primitives)
- Replacing workflows4s for durable orchestration — this complements it
- Providing a web UI or API server for interrupt/resume — that's application-layer concern
- Supporting distributed agent execution across processes/machines
- Streaming individual LLM tokens through the event system (TokenDelta events are included but LLM streaming integration is deferred to a follow-up)

## Decisions

### Decision 1: AgentTool extends InvokableTool[IO]

**Choice:** `AgentTool` is a concrete class that extends `InvokableTool[IO]`.

**Rationale:** This is the simplest integration path. The parent agent's `ToolsNode` already knows how to execute `InvokableTool[IO]`. By making `AgentTool` implement the same interface, no changes are needed to the core tool dispatch path — an AgentTool looks like any other tool to the LLM and to ToolsNode.

**Alternative considered:** A separate `AgentToolNode` in the graph layer. Rejected because it couples agent delegation to the graph system. The tool-level abstraction is more composable — an AgentTool can be used in ReactAgent, in WIOGraph, or standalone.

**Implementation:**
```
AgentTool(
  innerAgent: ReactAgent,
  config: AgentToolConfig,
  stateRef: Ref[IO, Option[AgentToolState]],
  emitter: Option[AgentEventEmitter]
) extends InvokableTool[IO]
```

The `run` method:
1. Parses input (extract `request` string or use full chat history)
2. Checks `stateRef` for persisted state from a previous interrupt
3. Runs the inner agent's `generate` method
4. If the inner agent returns normally → return result as JSON string
5. If the inner agent raises an `AgentInterruptedException` → save state to `stateRef`, re-raise for ToolsNode to capture

### Decision 2: Interrupts are exceptions, not return values

**Choice:** An agent signals an interrupt by raising an `AgentInterruptedException(signal: InterruptSignal)` in `IO`.

**Rationale:** The ReactAgent's `generateLoop` is a recursive `IO.flatMap` chain. Inserting a new return type (e.g., `Either[InterruptSignal, AssistantMessage]`) would require changing every call site. Using the error channel (`IO.raiseError`) naturally propagates through `flatMap`, `handleError`, and `attempt` without changing the happy-path types.

**Alternative considered:** Changing `ReactAgent.generate` to return `IO[Either[InterruptSignal, AssistantMessage]]`. Rejected because it would be a breaking change to the trait signature and forces every consumer to handle the `Left` case even if they don't use interrupts.

**Mitigation:** `AgentInterruptedException` extends `AdkError` (not `Throwable` directly), so it integrates with the existing error hierarchy. The `AgentRunner` catches it specifically; regular error handlers propagate it.

### Decision 3: InterruptSignal is a sealed trait with address tracking

**Choice:** Three variants: `Simple(address, info)`, `Stateful(address, info, state)`, `Composite(address, info, state, children)`.

**Rationale:** Mirrors Eino's three interrupt types but adapted to Scala's type system. The `address: List[AddressSegment]` is built incrementally as execution enters each agent/tool boundary. Composite is needed only for AgentTool propagation (wrapping the inner agent's interrupt).

**Address construction:** Each layer prepends its segment:
- `AgentRunner.run(agent)` → starts with `AddressSegment.Agent(agent.name)`
- `AgentTool.run(args)` → prepends `AddressSegment.Agent(innerAgent.name)`
- `Tool.interrupt(info)` → appends `AddressSegment.Tool(tool.name)`

The address is built bottom-up (innermost first) then reversed when the interrupt reaches AgentRunner.

### Decision 4: AgentRunner is a standalone coordinator, not part of ReactAgent

**Choice:** `AgentRunner` is a separate class that wraps any `ReactAgent` with interrupt/resume and event streaming capabilities.

**Rationale:** Keeps ReactAgent focused on the ReAct loop. AgentRunner adds the "infrastructure" concerns: checkpoint persistence, event stream management, resume routing. This separation means ReactAgent remains testable without checkpoint/event infrastructure.

**Implementation:**
```
AgentRunner(
  agent: ReactAgent,
  checkpointStore: CheckpointStore,
  emitter: AgentEventEmitter
)
```

- `run(messages)`: Creates an `AgentEventEmitter`, runs the agent, catches `AgentInterruptedException`, saves checkpoint, returns `RunResult`
- `resume(checkpointId, results)`: Loads checkpoint, injects resume data into the tools that interrupted, re-runs the agent from saved state
- `runWithEvents(messages)`: Returns `(IO[RunResult], Stream[IO, AgentEvent])`

### Decision 5: AgentEventEmitter uses cats.effect.std.Queue

**Choice:** `AgentEventEmitter` wraps a bounded `Queue[IO, Option[AgentEvent]]` (None = stream termination signal).

**Rationale:** `Queue` provides backpressure-aware, concurrent-safe event publishing. The `subscribe` method converts it to `Stream[IO, AgentEvent]` via `Stream.fromQueueNoneTerminated`. Bounded queue prevents memory issues if consumers are slow.

**Alternative considered:** `fs2.Topic` for fan-out to multiple subscribers. Deferred because single-subscriber is sufficient for the initial use case (one UI consumer). Topic can be added later if needed.

**Scoping mechanism:** `emitter.scoped(runStep)` returns a new `AgentEventEmitter` that wraps the same queue but prepends the run step to every event's RunPath. This is how nested AgentTools get proper path tracking without explicit threading.

### Decision 6: Serialization via upickle for checkpoint state

**Choice:** Checkpoint state is serialized to `Array[Byte]` via upickle JSON, matching the existing `CheckpointStore` interface.

**Rationale:** upickle is already a dependency (used in Tool.scala and elsewhere). It handles case class serialization natively. The `CheckpointStore` already works with `Array[Byte]`.

**Alternative considered:** Java serialization, circe, or a custom binary format. upickle is simplest and already in the dependency tree.

**Serialized state includes:**
- `messages: List[SerializableMessage]` — the conversation so far
- `interruptSignal: InterruptSignal` — the full interrupt hierarchy
- `innerAgentStates: Map[String, Array[Byte]]` — serialized states of any AgentTools that were mid-execution
- `streamingEnabled: Boolean`

### Decision 7: Module placement

**Choice:**
- `InterruptSignal`, `AddressSegment`, `RunPath`, `AgentEvent`, `AgentEventEmitter` → `adk4s-core` (they are foundational types)
- `AgentTool` → `adk4s-core` (extends `InvokableTool[IO]` which is in core)
- `AgentRunner`, `RunResult` → `adk4s-orchestration` (depends on `CheckpointStore` which is in orchestration)

**Rationale:** Core types that tools/agents depend on go in `adk4s-core`. The runner that coordinates checkpoint persistence goes in `adk4s-orchestration` since it depends on orchestration infrastructure.

## Risks / Trade-offs

**[Risk] Interrupt propagation adds complexity to the tool execution path** → Mitigation: The interrupt is carried via the exception channel, which ToolsNode already handles via `handleError`. Only `AgentRunner` needs to specifically catch `AgentInterruptedException`; existing code treats it as a regular error.

**[Risk] Serialization compatibility across versions** → Mitigation: Use upickle with explicit ReadWriter instances. Add a version field to the checkpoint format so future changes can migrate.

**[Risk] Queue backpressure if event consumers are slow** → Mitigation: Use bounded queue (default capacity 256). If full, `offer` semantically blocks the producer, which naturally slows down agent execution. This is acceptable since agent execution is I/O-bound (waiting for LLM responses).

**[Risk] AgentTool state leaks if interrupt is not properly resumed** → Mitigation: AgentTool clears its `stateRef` on normal completion. `AgentRunner` clears checkpoint on successful resume completion. Add a TTL-based cleanup to `CheckpointStore` as a future enhancement.

**[Trade-off] Interrupts as exceptions vs return values** → Using exceptions means the interrupt bypasses normal control flow, which is the desired behavior (stop everything, propagate up). The trade-off is that a careless `handleError` could swallow the interrupt. Mitigation: `AgentInterruptedException` extends `AdkError`, and existing error handlers in ToolsNode already re-throw `AdkError`.

**[Trade-off] Single event stream subscriber** → Queue supports one consumer. Multiple consumers would need `Topic`. Acceptable for v1; can be upgraded without API changes since `subscribe` returns `Stream[IO, AgentEvent]` regardless of the backing implementation.

## Open Questions

1. **Should `ReactAgent.generate` return `IO[Either[InterruptSignal, AssistantMessage]]` instead of using exceptions?** — The exception approach is simpler and backward-compatible, but a more principled approach would use typed errors. This could be revisited in a future iteration if the exception approach proves problematic.

2. **Should the event emitter be passed via implicit/contextual parameters or explicit parameters?** — Explicit is simpler and more visible, but verbose. Could use Cats Effect's `IOLocal` for transparent threading through the call stack.

3. **How should the resume data be typed?** — Currently `InterruptResult.data` is `ujson.Value` (opaque JSON). A more type-safe approach would use a typeclass, but that complicates the checkpoint serialization. Deferred to design review.
