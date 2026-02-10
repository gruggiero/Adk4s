## ADDED Requirements

### Requirement: AgentEvent sealed trait hierarchy

The system SHALL provide an `AgentEvent` sealed trait representing observable events during agent execution. Each event SHALL carry a `RunPath` identifying which agent in the hierarchy produced it.

#### Scenario: Message output event
- **WHEN** an agent produces a final or intermediate text response
- **THEN** an `AgentEvent.MessageOutput(runPath, message: String, role: Role)` is emitted

#### Scenario: Tool call requested event
- **WHEN** an agent's LLM decides to call a tool
- **THEN** an `AgentEvent.ToolCallRequested(runPath, toolName: String, arguments: String, callId: String)` is emitted

#### Scenario: Tool call completed event
- **WHEN** a tool execution finishes
- **THEN** an `AgentEvent.ToolCallCompleted(runPath, toolName: String, result: String, callId: String, isError: Boolean)` is emitted

#### Scenario: Agent iteration event
- **WHEN** an agent completes one iteration of its ReAct loop
- **THEN** an `AgentEvent.IterationCompleted(runPath, iteration: Int, remainingSteps: Int)` is emitted

#### Scenario: Interrupt event
- **WHEN** an agent emits an interrupt signal
- **THEN** an `AgentEvent.Interrupted(runPath, signal: InterruptSignal)` is emitted

#### Scenario: Error event
- **WHEN** an agent encounters an error during execution
- **THEN** an `AgentEvent.ErrorOccurred(runPath, error: AdkError)` is emitted

#### Scenario: Streaming token event
- **WHEN** an agent's LLM streams a token
- **THEN** an `AgentEvent.TokenDelta(runPath, delta: String)` is emitted

### Requirement: RunPath tracks agent hierarchy

The system SHALL provide a `RunPath` opaque type representing the execution path from the root agent to the current event source. A `RunPath` is a `List[RunStep]` where each step identifies an agent or tool.

#### Scenario: Top-level agent RunPath
- **WHEN** the root agent "supervisor" emits an event
- **THEN** the event's `runPath` is `RunPath(List(RunStep("supervisor")))`

#### Scenario: Nested agent RunPath
- **WHEN** agent "supervisor" delegates to agent-tool "database-agent" which calls tool "query"
- **THEN** events from "query" have `runPath` equal to `RunPath(List(RunStep("supervisor"), RunStep("database-agent"), RunStep("query")))`

#### Scenario: RunPath display
- **WHEN** `runPath.show` is called on a RunPath with steps ["supervisor", "database-agent", "query"]
- **THEN** the string "supervisor > database-agent > query" is returned

### Requirement: AgentEventEmitter for publishing events

The system SHALL provide an `AgentEventEmitter` that agents and tools use to publish events during execution. The emitter SHALL be threaded through the execution context.

#### Scenario: Create emitter
- **WHEN** `AgentEventEmitter.create` is called
- **THEN** an emitter is returned backed by a `cats.effect.std.Queue[IO, AgentEvent]`

#### Scenario: Emit event
- **WHEN** `emitter.emit(event)` is called
- **THEN** the event is enqueued for consumption by subscribers

#### Scenario: Subscribe to events
- **WHEN** `emitter.subscribe` is called
- **THEN** a `Stream[IO, AgentEvent]` is returned that emits events as they are published

#### Scenario: Scoped emitter for nested agent
- **WHEN** `emitter.scoped(runStep)` is called with RunStep("database-agent")
- **THEN** a new emitter is returned that automatically prepends the run step to all emitted events' RunPaths

### Requirement: AgentRunner emits event stream

The `AgentRunner` SHALL provide access to a `Stream[IO, AgentEvent]` that emits all events from the executed agent and its nested agent-tools in real time.

#### Scenario: Run with event stream
- **WHEN** `AgentRunner.runWithEvents(agent, messages)` is called
- **THEN** an `(IO[RunResult], Stream[IO, AgentEvent])` tuple is returned
- **AND** the stream emits events as the agent executes

#### Scenario: Events from nested agent-tools appear in stream
- **WHEN** agent "supervisor" invokes agent-tool "database-agent"
- **THEN** events from both "supervisor" and "database-agent" appear in the same stream
- **AND** each event's `runPath` identifies which agent produced it

#### Scenario: Stream completes when agent finishes
- **WHEN** the agent completes (success, failure, or interrupt)
- **THEN** the event stream terminates

### Requirement: Event forwarding from AgentTool

When an AgentTool executes its inner agent, events from the inner agent SHALL be forwarded to the parent's event emitter with the RunPath extended.

#### Scenario: Inner agent events forwarded
- **WHEN** agent-tool "database-agent" inner agent emits `ToolCallRequested(RunPath(["database-agent"]), "query", ...)`
- **THEN** the parent stream receives `ToolCallRequested(RunPath(["supervisor", "database-agent"]), "query", ...)`

#### Scenario: Inner agent token streaming forwarded
- **WHEN** the inner agent streams LLM tokens
- **THEN** `TokenDelta` events appear in the parent stream with the extended RunPath

#### Scenario: Inner agent errors forwarded
- **WHEN** the inner agent encounters an error
- **THEN** an `ErrorOccurred` event appears in the parent stream before the AgentTool handles the error

### Requirement: Integration with ReactAgent

The `ReactAgent` SHALL accept an optional `AgentEventEmitter` and emit events at each step of its ReAct loop.

#### Scenario: ReactAgent emits tool call events
- **WHEN** a ReactAgent with an emitter calls a tool
- **THEN** `ToolCallRequested` is emitted before execution and `ToolCallCompleted` after

#### Scenario: ReactAgent emits iteration events
- **WHEN** a ReactAgent with an emitter completes a loop iteration
- **THEN** `IterationCompleted` is emitted with the current iteration count

#### Scenario: ReactAgent without emitter
- **WHEN** a ReactAgent is created without an emitter (backward compatible)
- **THEN** no events are emitted and behavior is identical to current implementation
