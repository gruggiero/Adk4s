# agent-as-tool Specification

## Purpose
Provides the AgentTool abstraction that wraps an agent (with its own ReAct loop, tools, and state) as an InvokableTool that can be called by a parent agent's LLM.

## Requirements

### Requirement: AgentTool wraps an agent as an InvokableTool

The system SHALL provide an `AgentTool` that wraps any agent implementing the ReAct loop (or equivalent) as an `InvokableTool[IO]`, allowing a parent agent's LLM to invoke sub-agents via tool calls. The `AgentTool` SHALL expose the inner agent's name and description as `AdkToolInfo` so the LLM can discover and select it.

#### Scenario: Create AgentTool from ReactAgent
- **WHEN** `AgentTool.fromReactAgent(reactAgent, config)` is called with a ReactAgent that has name "database-agent" and description "Handles database queries"
- **THEN** an `InvokableTool[IO]` is returned whose `info.name` is "database-agent" and `info.description` is "Handles database queries"

#### Scenario: AgentTool tool info includes input schema
- **WHEN** an AgentTool is created with default input handling
- **THEN** the `AdkToolInfo.parameters` SHALL contain a JSON Schema with a required `request` field of type string

#### Scenario: AgentTool with custom input schema
- **WHEN** an AgentTool is created with `AgentToolConfig.withInputSchema(customSchema)`
- **THEN** the `AdkToolInfo.parameters` SHALL use the provided custom JSON Schema

### Requirement: AgentTool executes the inner agent's ReAct loop

When invoked, the `AgentTool` SHALL run the inner agent's full tool loop (LLM calls, tool executions, iterations) and return the final result as a string. The inner agent's execution SHALL be self-contained.

#### Scenario: Invoke AgentTool with request string
- **WHEN** an AgentTool is invoked with arguments `{"request": "Find user by email foo@bar.com"}`
- **THEN** the inner agent executes its full ReAct loop with the request as the user message
- **AND** the final assistant response is returned as the tool output string

#### Scenario: Invoke AgentTool with full chat history
- **WHEN** an AgentTool is created with `AgentToolConfig.withFullChatHistory` and invoked
- **THEN** the inner agent receives the entire parent conversation history as input instead of just the request field

#### Scenario: Inner agent uses its own tools
- **WHEN** the inner agent has tools ["query_db", "format_result"] and is invoked
- **THEN** the inner agent can call its own tools during its ReAct loop independently of the parent agent's tools

### Requirement: AgentTool action scoping

Actions emitted by the inner agent SHALL be scoped to the AgentTool boundary. Inner agent Exit, TransferToAgent, and BreakLoop actions SHALL NOT propagate to the parent agent. Only Interrupt actions SHALL propagate.

#### Scenario: Inner agent exits
- **WHEN** the inner agent emits an Exit action during its ReAct loop
- **THEN** the AgentTool treats this as normal completion and returns the last output as tool result
- **AND** no Exit action propagates to the parent agent

#### Scenario: Inner agent transfers to another agent
- **WHEN** the inner agent emits a TransferToAgent("other-agent") action
- **THEN** the AgentTool ignores the transfer action
- **AND** the inner agent's output before the transfer is returned as tool result

#### Scenario: Inner agent interrupts
- **WHEN** the inner agent emits an Interrupt action requesting human input
- **THEN** the AgentTool SHALL propagate the interrupt to the parent agent via an `AgentToolInterrupt`
- **AND** the interrupt SHALL include the inner agent's state for later resumption

### Requirement: AgentTool state persistence across interrupt/resume

When an inner agent is interrupted, the AgentTool SHALL persist the inner agent's state so that resumption can continue the inner agent from exactly where it stopped.

#### Scenario: Persist inner agent state on interrupt
- **WHEN** the inner agent is interrupted mid-execution
- **THEN** the AgentTool saves the inner agent's accumulated messages, iteration count, and tool call state to a `Ref[IO, Option[AgentToolState]]`

#### Scenario: Resume inner agent from persisted state
- **WHEN** the AgentTool is re-invoked after an interrupt with resume data
- **THEN** the inner agent resumes from its persisted state with the resume data injected
- **AND** the inner agent continues its ReAct loop from the interrupted step

#### Scenario: No state leaks between invocations
- **WHEN** an AgentTool completes normally (no interrupt)
- **THEN** the persisted state is cleared
- **AND** the next invocation starts the inner agent fresh

### Requirement: AgentTool factory methods

The system SHALL provide convenient factory methods for creating AgentTools.

#### Scenario: Create from ReactAgent with defaults
- **WHEN** `AgentTool.fromReactAgent(agent)` is called
- **THEN** an AgentTool is created with default input handling (request string) and no custom schema

#### Scenario: Create from ReactAgent with config
- **WHEN** `AgentTool.fromReactAgent(agent, config)` is called with `AgentToolConfig(withFullChatHistory = true)`
- **THEN** an AgentTool is created that passes full chat history to the inner agent

#### Scenario: Create from function with agent semantics
- **WHEN** `AgentTool.fromFunction(name, description, fn: List[Message] => IO[String])` is called
- **THEN** an AgentTool is created that delegates to the function, treating it as a single-step agent
