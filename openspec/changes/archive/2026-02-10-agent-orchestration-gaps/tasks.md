## 1. Core Types (adk4s-core)

- [x] 1.1 Create `InterruptSignal` sealed trait with `Simple`, `Stateful`, `Composite` variants in `org.adk4s.core.interrupt`
- [x] 1.2 Create `AddressSegment` sealed trait with `Agent(name)` and `Tool(name)` variants
- [x] 1.3 Create `InterruptResult(address: List[AddressSegment], data: ujson.Value)` case class
- [x] 1.4 Create `AgentInterruptedException(signal: InterruptSignal)` extending `AdkError`
- [x] 1.5 Create `RunPath` opaque type wrapping `List[RunStep]` with `show` method and `RunStep(name: String)` case class
- [x] 1.6 Create `AgentEvent` sealed trait with variants: `MessageOutput`, `ToolCallRequested`, `ToolCallCompleted`, `IterationCompleted`, `Interrupted`, `ErrorOccurred`, `TokenDelta`
- [x] 1.7 Create `AgentEventEmitter` backed by `cats.effect.std.Queue[IO, Option[AgentEvent]]` with `emit`, `subscribe`, `scoped`, and `complete` methods

## 2. AgentTool (adk4s-core)

- [x] 2.1 Create `AgentToolConfig` case class with `withFullChatHistory`, `withInputSchema` options
- [x] 2.2 Create `AgentToolState` case class for persisting inner agent state across interrupt/resume
- [x] 2.3 Create `AgentTool` extending `InvokableTool[IO]` with `Ref[IO, Option[AgentToolState]]` and optional `AgentEventEmitter`
- [x] 2.4 Implement `AgentTool.run` method: parse input, check stateRef for resume, run inner agent, handle interrupt propagation
- [x] 2.5 Implement action scoping: catch inner agent Exit/TransferToAgent and return result, propagate only Interrupt
- [x] 2.6 Implement `AgentTool.fromReactAgent` factory methods (with defaults and with config)
- [x] 2.7 Add upickle `ReadWriter` instances for `AgentToolState` serialization

## 3. ToolsNode Modifications (adk4s-core)

- [x] 3.1 Add `withAgentTool(agentTool: AgentTool)` to `ToolsNodeConfig` builder
- [x] 3.2 Add `withEventEmitter(emitter: AgentEventEmitter)` to `ToolsNodeConfig` builder
- [x] 3.3 Add optional `interruptSignal: Option[InterruptSignal]` field to `ToolExecutionResult`
- [x] 3.4 Modify `executeTool` to catch `AgentInterruptedException` from AgentTool and capture signal in result
- [x] 3.5 Modify sequential execution to stop on interrupt and include outputs from tools that ran before
- [x] 3.6 Forward events from AgentTool execution to configured emitter with extended RunPath

## 4. AgentRunner (adk4s-orchestration)

- [x] 4.1 Create `RunResult` sealed trait with `Completed`, `Interrupted`, `Failed` variants
- [x] 4.2 Create `AgentRunner` class wrapping `ReactAgent`, `CheckpointStore`, and `AgentEventEmitter`
- [x] 4.3 Implement `run(messages)`: execute agent, catch `AgentInterruptedException`, save checkpoint, return `RunResult`
- [x] 4.4 Implement `resume(checkpointId, results)`: load checkpoint, route `InterruptResult` to target address, re-run agent
- [x] 4.5 Implement `runWithEvents(messages)`: return `(IO[RunResult], Stream[IO, AgentEvent])` tuple
- [x] 4.6 Implement checkpoint serialization/deserialization via upickle (messages, interrupt signal, inner agent states)
- [x] 4.7 Add checkpoint cleanup: delete on successful completion, clear AgentTool stateRef on normal finish

## 5. ReactAgent Integration (adk4s-orchestration)

- [x] 5.1 Add optional `AgentEventEmitter` parameter to `ReactAgent` trait and implementation
- [x] 5.2 Emit `ToolCallRequested` before tool execution and `ToolCallCompleted` after in `generateLoop`
- [x] 5.3 Emit `IterationCompleted` at end of each ReAct loop iteration
- [x] 5.4 Emit `MessageOutput` when agent produces final response
- [x] 5.5 Verify backward compatibility: ReactAgent without emitter behaves identically to current implementation

## 6. Tests

- [x] 6.1 Unit tests for `InterruptSignal` creation and address tracking
- [x] 6.2 Unit tests for `AgentEventEmitter`: emit, subscribe, scoped emitter prepends RunStep
- [x] 6.3 Unit tests for `AgentTool`: basic invocation, action scoping (Exit/Transfer captured, Interrupt propagated)
- [x] 6.4 Unit tests for `AgentTool` state persistence: interrupt saves state, resume restores state, normal completion clears state
- [x] 6.5 Unit tests for `ToolsNode` with AgentTool: interrupt signal captured in `ToolExecutionResult`, sequential stops on interrupt
- [x] 6.6 Unit tests for `AgentRunner`: run to completion, run with interrupt, resume from checkpoint, resume with invalid checkpoint ID
- [x] 6.7 Integration test: nested agent-tool interrupt propagation (parent → agent-tool → inner tool interrupts → propagates up)
- [x] 6.8 Integration test: event stream from nested agent-tools shows correct RunPath hierarchy

## 7. Examples

- [x] 7.1 Create `AgentToolExample`: supervisor agent delegating to a database-agent via AgentTool
- [x] 7.2 Create `InterruptResumeExample`: tool that requests human approval, agent interrupts, resumes with approval
- [x] 7.3 Create `EventStreamExample`: agent execution with real-time event stream printed to console
