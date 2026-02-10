## Why

adk4s uses workflows4s as its orchestration backbone, which provides durable, event-sourced workflow execution with signals, loops, forks, and parallel processing. However, three specific agent orchestration patterns enabled by Eino's ADK (Go) cannot be achieved with the current adk4s infrastructure: (1) LLM-driven dynamic agent dispatch where the LLM decides at runtime which sub-agent to invoke via tool calls, (2) nested interrupt propagation where human-in-the-loop approval requests bubble up through agent hierarchies, and (3) real-time event streaming from nested agent execution for UI observability. Filling these gaps completes adk4s's agent framework story.

## What Changes

- Add **AgentTool** abstraction that wraps an agent (with its own tool loop) as an `InvokableTool[IO]`, allowing parent agents to delegate to sub-agents via LLM tool call selection
- Add **InterruptSignal** protocol with hierarchical address-based routing, enabling sub-agents to request human input and propagate the request up through arbitrary nesting depth
- Add **AgentEventStream** observation layer that emits `Stream[IO, AgentEvent]` with `RunPath` context, allowing real-time visibility into nested agent execution
- Extend `ReactAgent` to support agent-backed tools alongside regular tools, with interrupt propagation
- Extend `ToolsNode` to handle agent-tool execution with event forwarding and interrupt capture
- Add `AgentRunner` as the top-level entry point that manages checkpoint persistence for interrupt/resume and event stream emission

## Capabilities

### New Capabilities

- `agent-as-tool`: Wrapping an agent (with its own ReAct loop, tools, and state) as an InvokableTool that can be called by a parent agent's LLM. Covers agent-tool creation, input/output handling, action scoping (inner Exit/Transfer/BreakLoop are contained), and state persistence for the inner agent across interrupt/resume cycles.
- `agent-interrupt-resume`: Hierarchical interrupt/resume protocol for human-in-the-loop workflows. Covers interrupt signals (stateless and stateful), composite interrupts that combine sub-agent interrupts, address-based signal routing for targeted resume, and checkpoint serialization for durable interrupt state.
- `agent-event-stream`: Real-time observation of agent execution via `Stream[IO, AgentEvent]`. Covers event types (message output, tool calls, errors, actions), RunPath tracking through agent hierarchies, event forwarding from nested agent-tools to parent streams, and integration with LLM token streaming.

### Modified Capabilities

- `tools-node`: ToolsNode must support executing agent-backed tools (AgentTool) alongside regular tools, forwarding events from agent-tool execution and capturing interrupt signals from nested agents.

## Impact

- **adk4s-core**: New types in `org.adk4s.core` — `AgentTool`, `AgentEvent`, `AgentAction`, `InterruptSignal`, `RunPath`, `AgentRunner`
- **adk4s-core/tool**: `ToolsNode` extended to detect and handle agent-tool execution differently from regular tools
- **adk4s-core/agent**: `ReactAgent` extended to work with the interrupt protocol and event streaming
- **adk4s-orchestration**: WIOGraph nodes may optionally emit agent events during execution for observability
- **Dependencies**: No new external dependencies. Uses existing workflows4s `HandleSignal` for interrupt/resume, Cats Effect `Stream` for event streaming, and existing `Ref[IO, _]` for state management
- **APIs**: New public APIs for creating agent-tools, emitting/handling interrupts, and subscribing to event streams. No breaking changes to existing APIs.
