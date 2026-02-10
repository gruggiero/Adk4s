## MODIFIED Requirements

### Requirement: ToolsNode Configuration
The system SHALL provide a configuration class with builder pattern for registering tools, configuring execution modes, and applying middleware. The configuration SHALL support agent-backed tools (AgentTool) as a distinct tool category alongside LLM4S ToolFunctions and ADK4S InvokableTools.

#### Scenario: Create config from LLM4S ToolFunctions
- **GIVEN** a list of LLM4S ToolFunctions
- **WHEN** ToolsNodeConfig.fromToolFunctions is called
- **THEN** a config is created with tools wrapped in Left(ToolFunction)

#### Scenario: Create config from LLM4S ToolRegistry
- **GIVEN** an LLM4S ToolRegistry
- **WHEN** ToolsNodeConfig.fromRegistry is called
- **THEN** a config is created with tools from the registry

#### Scenario: Create config from ADK4S Tools
- **GIVEN** a list of ADK4S InvokableTools
- **WHEN** ToolsNodeConfig.fromAdkTools is called
- **THEN** a config is created with tools wrapped in Right(InvokableTool)

#### Scenario: Builder adds LLM4S tool
- **GIVEN** an LLM4S ToolFunction
- **WHEN** builder.withTool is called
- **THEN** the tool is added to the tools list as Left(tool)

#### Scenario: Builder adds ADK4S tool
- **GIVEN** an ADK4S InvokableTool
- **WHEN** builder.withAdkTool is called
- **THEN** the tool is added to the tools list as Right(tool)

#### Scenario: Builder adds AgentTool
- **GIVEN** an AgentTool wrapping a ReactAgent
- **WHEN** builder.withAgentTool is called
- **THEN** the AgentTool is added to the tools list as Right(agentTool) since AgentTool extends InvokableTool

#### Scenario: Builder sets sequential execution
- **GIVEN** a config builder
- **WHEN** builder.sequential is called
- **THEN** executeSequentially is set to true

#### Scenario: Builder sets parallel execution
- **GIVEN** a config builder
- **WHEN** builder.parallel(maxConcurrency=5) is called
- **THEN** executeSequentially is false and maxConcurrency is 5

#### Scenario: Builder adds middleware
- **GIVEN** a config builder and a middleware
- **WHEN** builder.withMiddleware is called
- **THEN** the middleware is added to the middlewares list

#### Scenario: Builder sets unknown tool handler
- **GIVEN** a config builder and a handler function
- **WHEN** builder.withUnknownHandler is called
- **THEN** unknownToolHandler is set to Some(handler)

#### Scenario: Config to ToolRegistry
- **GIVEN** a config with mixed LLM4S and ADK4S tools
- **WHEN** toToolRegistry is called
- **THEN** only LLM4S tools are included in the registry

#### Scenario: Config adkTools
- **GIVEN** a config with mixed LLM4S and ADK4S tools
- **WHEN** adkTools is called
- **THEN** only ADK4S tools are returned

#### Scenario: Builder sets event emitter
- **GIVEN** a config builder and an AgentEventEmitter
- **WHEN** builder.withEventEmitter(emitter) is called
- **THEN** the emitter is stored in the config for event forwarding during AgentTool execution

### Requirement: ToolsNode Execution
The system SHALL provide ToolsNode for executing tool calls from LLM responses with configurable parallel or sequential execution modes. When executing an AgentTool, the ToolsNode SHALL forward events from the inner agent and capture interrupt signals.

#### Scenario: Execute single tool
- **GIVEN** a ToolsNode with a registered weather tool
- **WHEN** executeTool is called with ToolInput for weather
- **THEN** the weather tool is executed and ToolOutput is returned

#### Scenario: Execute multiple tools sequentially
- **GIVEN** a ToolsNode with executeSequentially=true and multiple tool calls
- **WHEN** executeTools is called
- **THEN** tools execute one after another in order

#### Scenario: Execute multiple tools in parallel
- **GIVEN** a ToolsNode with executeSequentially=false and multiple tool calls
- **WHEN** executeTools is called
- **THEN** tools execute concurrently up to maxConcurrency

#### Scenario: Execute from LLM4S ToolCalls
- **GIVEN** a list of LLM4S ToolCalls
- **WHEN** executeFromToolCalls is called
- **THEN** ToolCalls are converted to ToolInputs and executed

#### Scenario: Execute LLM4S tool
- **GIVEN** a registered LLM4S ToolFunction
- **WHEN** the tool is executed with valid JSON arguments
- **THEN** the tool handler is called with parsed parameters

#### Scenario: Execute ADK4S tool
- **GIVEN** a registered ADK4S InvokableTool
- **WHEN** the tool is executed with string arguments
- **THEN** the tool.run method is called

#### Scenario: Execute AgentTool with event forwarding
- **GIVEN** a ToolsNode with an AgentTool and an AgentEventEmitter configured
- **WHEN** the AgentTool is executed
- **THEN** events from the inner agent are forwarded to the emitter with extended RunPath
- **AND** the final agent output is returned as a ToolOutput

#### Scenario: Execute AgentTool that interrupts
- **GIVEN** a ToolsNode with an AgentTool whose inner agent interrupts
- **WHEN** the AgentTool is executed
- **THEN** the ToolsNode captures the InterruptSignal from the AgentTool
- **AND** the execution result includes the interrupt signal for propagation to the parent agent

#### Scenario: Unknown tool with handler
- **GIVEN** a ToolsNode with unknownToolHandler set
- **WHEN** an unknown tool is executed
- **THEN** the handler is called and its result is returned

#### Scenario: Unknown tool without handler
- **GIVEN** a ToolsNode without unknownToolHandler
- **WHEN** an unknown tool is executed
- **THEN** a ToolOutput with isError=true and unknown tool message is returned

#### Scenario: Tool execution error
- **GIVEN** a tool that throws an exception
- **WHEN** the tool is executed
- **THEN** a ToolOutput with isError=true and error message is returned

#### Scenario: Middleware is applied
- **GIVEN** a ToolsNode with logging middleware configured
- **WHEN** a tool is executed
- **THEN** the middleware logs the tool call and result

#### Scenario: Arguments preprocessing
- **GIVEN** a ToolsNode with argumentsHandler configured
- **WHEN** a tool is executed
- **THEN** arguments are preprocessed before tool execution

## ADDED Requirements

### Requirement: ToolExecutionResult carries interrupt signals

The `ToolExecutionResult` SHALL carry an optional `InterruptSignal` when an AgentTool interrupts during batch execution. This allows the caller (ReactAgent) to detect and propagate the interrupt.

#### Scenario: Batch result with no interrupts
- **WHEN** all tools in a batch complete normally
- **THEN** `ToolExecutionResult.interruptSignal` is `None`

#### Scenario: Batch result with AgentTool interrupt
- **WHEN** one of the tools in a batch is an AgentTool that interrupts
- **THEN** `ToolExecutionResult.interruptSignal` is `Some(signal)` containing the interrupt from the AgentTool
- **AND** other tools that completed before the interrupt have their outputs in `outputs`

#### Scenario: Sequential execution stops on interrupt
- **WHEN** executing tools sequentially and an AgentTool interrupts
- **THEN** subsequent tools are NOT executed
- **AND** the result contains outputs from tools that ran before the interrupt plus the interrupt signal
