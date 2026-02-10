# agent-interrupt-resume Specification

## Purpose
Provides hierarchical interrupt/resume protocol for human-in-the-loop workflows, allowing agents to pause, persist state, and resume when given external input.

## Requirements

### Requirement: InterruptSignal sealed trait hierarchy

The system SHALL provide an `InterruptSignal` sealed trait representing an agent's request to pause execution and await external input. Three variants SHALL exist: stateless, stateful, and composite.

#### Scenario: Create stateless interrupt
- **WHEN** `InterruptSignal.simple(info)` is called with info describing why the interrupt is needed
- **THEN** an `InterruptSignal.Simple` is created with the info payload and no persisted state

#### Scenario: Create stateful interrupt
- **WHEN** `InterruptSignal.stateful(info, state)` is called with info and an agent's internal state
- **THEN** an `InterruptSignal.Stateful` is created carrying both the user-facing info and the serializable agent state

#### Scenario: Create composite interrupt
- **WHEN** `InterruptSignal.composite(info, state, childSignals)` is called with one or more child `InterruptSignal` values
- **THEN** an `InterruptSignal.Composite` is created that wraps the child signals with the parent's own info and state

### Requirement: Address-based interrupt identification

Each `InterruptSignal` SHALL carry an `Address` representing its position in the agent hierarchy. An `Address` is a `List[AddressSegment]` where each segment identifies either an agent or a tool.

#### Scenario: Address for a top-level agent interrupt
- **WHEN** a top-level agent named "supervisor" emits an interrupt
- **THEN** the interrupt's address is `List(AddressSegment.Agent("supervisor"))`

#### Scenario: Address for a nested agent-tool interrupt
- **WHEN** agent "supervisor" invokes agent-tool "database-agent" which invokes tool "query" which interrupts
- **THEN** the interrupt's address is `List(AddressSegment.Agent("supervisor"), AddressSegment.Agent("database-agent"), AddressSegment.Tool("query"))`

#### Scenario: AddressSegment sealed trait
- **WHEN** an AddressSegment is constructed
- **THEN** it SHALL be one of `AddressSegment.Agent(name: String)` or `AddressSegment.Tool(name: String)`

### Requirement: InterruptResult for resume data

The system SHALL provide an `InterruptResult` type that pairs an `Address` with the resume data provided by the user/system for that specific interrupt point.

#### Scenario: Create resume data for a specific address
- **WHEN** `InterruptResult(address, data)` is constructed with the address of the interrupted tool and the user's approval
- **THEN** the result carries the address for routing and the opaque resume data

#### Scenario: Multiple resume targets
- **WHEN** a composite interrupt has two child interrupts at different addresses
- **THEN** the caller SHALL provide a `List[InterruptResult]` with one entry per address that needs resume data

### Requirement: AgentRunner manages interrupt/resume lifecycle

The system SHALL provide an `AgentRunner` that executes an agent, handles interrupt signals, persists checkpoint state, and supports resumption.

#### Scenario: Run agent to completion
- **WHEN** `AgentRunner.run(agent, messages)` is called and the agent completes without interrupting
- **THEN** a `RunResult.Completed(output)` is returned with the final agent output

#### Scenario: Run agent that interrupts
- **WHEN** `AgentRunner.run(agent, messages)` is called and the agent emits an `InterruptSignal`
- **THEN** a `RunResult.Interrupted(checkpointId, interruptSignal)` is returned
- **AND** the agent's full state is persisted to the configured `CheckpointStore`

#### Scenario: Resume from checkpoint
- **WHEN** `AgentRunner.resume(checkpointId, results: List[InterruptResult])` is called
- **THEN** the agent's state is loaded from the `CheckpointStore`
- **AND** each `InterruptResult` is routed to its target address segment
- **AND** the agent continues execution from the interrupt point

#### Scenario: Resume with wrong checkpoint ID
- **WHEN** `AgentRunner.resume(invalidId, results)` is called with a non-existent checkpoint ID
- **THEN** an `AdkError.CheckpointNotFoundError` is raised

### Requirement: CheckpointStore for interrupt state persistence

The system SHALL use the existing `CheckpointStore[IO]` trait for persisting interrupt state. The state SHALL include the agent's accumulated messages, the interrupt signal hierarchy, and any inner agent states from AgentTools.

#### Scenario: Save checkpoint on interrupt
- **WHEN** an agent interrupts and checkpoint is saved
- **THEN** the stored state includes: accumulated messages, interrupt signal with addresses, inner agent states, and streaming mode flag

#### Scenario: Load checkpoint on resume
- **WHEN** a checkpoint is loaded for resume
- **THEN** the agent's message history, interrupt context, and inner agent states are all restored

#### Scenario: Delete checkpoint after completion
- **WHEN** a resumed agent completes successfully
- **THEN** the checkpoint is deleted from the store

### Requirement: Interrupt propagation through AgentTool boundaries

When a tool inside an AgentTool's inner agent interrupts, the interrupt SHALL propagate up through the AgentTool boundary to the parent agent, preserving the full address chain.

#### Scenario: Tool interrupt propagates through AgentTool
- **WHEN** tool "query" inside agent-tool "database-agent" emits `InterruptSignal.simple("Approve query?")`
- **THEN** the AgentTool wraps it as `InterruptSignal.composite(agentToolInfo, agentToolState, List(childSignal))`
- **AND** the parent agent receives the composite interrupt

#### Scenario: Resume flows down through AgentTool
- **WHEN** `AgentRunner.resume` is called with `InterruptResult(address=["supervisor", "database-agent", "query"], data=approval)`
- **THEN** the runner routes to "supervisor" which routes to "database-agent" AgentTool
- **AND** the AgentTool restores its inner agent state and delivers the resume data to "query"

### Requirement: RunResult sealed trait

The system SHALL provide a `RunResult` sealed trait representing the outcome of an agent execution.

#### Scenario: Completed result
- **WHEN** an agent finishes normally
- **THEN** `RunResult.Completed(output: String, messages: List[Message])` is returned

#### Scenario: Interrupted result
- **WHEN** an agent emits an interrupt
- **THEN** `RunResult.Interrupted(checkpointId: String, signal: InterruptSignal)` is returned

#### Scenario: Failed result
- **WHEN** an agent fails with an error
- **THEN** `RunResult.Failed(error: AdkError)` is returned
