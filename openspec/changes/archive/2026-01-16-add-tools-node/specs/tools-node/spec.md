## ADDED Requirements

### Requirement: Tool Input/Output Types
The system SHALL provide types for tool execution inputs and outputs with conversion methods between ADK4S and LLM4S message formats.

#### Scenario: Convert ToolCall to ToolInput
- **GIVEN** a ToolCall from LLM4S with function name, arguments, and call ID
- **WHEN** ToolInput.fromToolCall is called
- **THEN** ToolInput contains name, arguments, and callId from the ToolCall

#### Scenario: Convert multiple ToolCalls to ToolInputs
- **GIVEN** a list of ToolCalls from LLM4S
- **WHEN** ToolInput.fromToolCalls is called
- **THEN** a list of ToolInputs is returned, one per ToolCall

#### Scenario: Convert ToolOutput to ADK4S Message
- **GIVEN** a ToolOutput with name, result, callId, and isError flag
- **WHEN** toMessage is called
- **THEN** an ADK4S Message is created with Role.Tool and the result as content

#### Scenario: Convert ToolOutput to LLM4S ToolMessage
- **GIVEN** a ToolOutput with name, result, callId, and isError flag
- **WHEN** toLlm4sMessage is called
- **THEN** an LLM4S ToolMessage is created with the result and callId

#### Scenario: Batch result to Messages
- **GIVEN** a ToolExecutionResult with multiple ToolOutputs
- **WHEN** toMessages is called
- **THEN** a list of ADK4S Messages is returned, one per ToolOutput

#### Scenario: Batch result to LLM4S Messages
- **GIVEN** a ToolExecutionResult with multiple ToolOutputs
- **WHEN** toLlm4sMessages is called
- **THEN** a list of LLM4S ToolMessages is returned, one per ToolOutput

#### Scenario: Check batch success
- **GIVEN** a ToolExecutionResult with outputs but no failures
- **WHEN** allSucceeded is checked
- **THEN** it returns true

#### Scenario: Check batch failure
- **GIVEN** a ToolExecutionResult with failedTools
- **WHEN** allSucceeded is checked
- **THEN** it returns false

### Requirement: Tool Middleware Pattern
The system SHALL provide a middleware pattern for wrapping tool execution with cross-cutting concerns like logging, timing, validation, and retry.

#### Scenario: Identity middleware
- **GIVEN** an endpoint and identity middleware
- **WHEN** middleware is applied
- **THEN** the endpoint executes without modification

#### Scenario: Logging middleware logs tool calls
- **GIVEN** a logging middleware with a log function
- **WHEN** a tool is executed
- **THEN** the log function receives tool call and result messages

#### Scenario: Timing middleware records duration
- **GIVEN** a timing middleware with an onTiming callback
- **WHEN** a tool is executed
- **THEN** the callback receives tool name and execution time in milliseconds

#### Scenario: Validation middleware validates input
- **GIVEN** a validation middleware that checks input
- **WHEN** input passes validation
- **THEN** the tool executes with the original input

#### Scenario: Validation middleware rejects invalid input
- **GIVEN** a validation middleware that rejects specific inputs
- **WHEN** input fails validation
- **THEN** a ToolOutput with isError=true and validation error message is returned

#### Scenario: Retry middleware retries on failure
- **GIVEN** a retry middleware with maxRetries=2 and initialDelay=100ms
- **WHEN** tool execution fails twice then succeeds
- **THEN** the final successful result is returned

#### Scenario: Retry middleware exhausts retries
- **GIVEN** a retry middleware with maxRetries=2
- **WHEN** tool execution fails 3 times
- **THEN** an error is raised after retries are exhausted

#### Scenario: Middleware composition
- **GIVEN** logging, timing, and retry middlewares
- **WHEN** middlewares are composed and applied
- **THEN** tool execution passes through logging, timing, then retry in that order

#### Scenario: Middleware >> operator
- **GIVEN** logging middleware and timing middleware
- **WHEN** logging >> timing is used
- **THEN** timing is applied outer to logging

### Requirement: ToolsNode Configuration
The system SHALL provide a configuration class with builder pattern for registering tools, configuring execution modes, and applying middleware.

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

### Requirement: ToolsNode Execution
The system SHALL provide ToolsNode for executing tool calls from LLM responses with configurable parallel or sequential execution modes.

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

### Requirement: Runnable Integration
The system SHALL provide Runnable wrappers for ToolsNode to integrate with graph orchestration and workflow systems.

#### Scenario: Runnable from AssistantMessage
- **GIVEN** a ToolsNode
- **WHEN** ToolsNodeRunnable.fromAssistantMessage is called
- **THEN** a Runnable[AssistantMessage, List[ToolMessage]] is returned

#### Scenario: Runnable from ToolCalls
- **GIVEN** a ToolsNode
- **WHEN** ToolsNodeRunnable.fromToolCalls is called
- **THEN** a Runnable[List[ToolCall], List[ToolMessage]] is returned

#### Scenario: Streaming Runnable
- **GIVEN** a ToolsNode
- **WHEN** ToolsNodeRunnable.streaming is called
- **THEN** a Runnable[List[ToolCall], ToolMessage] is returned that streams results

#### Scenario: ToolsNode toRunnable extension
- **GIVEN** a ToolsNode instance
- **WHEN** asRunnable is called
- **THEN** a Runnable[List[ToolCall], List[ToolMessage]] is returned

#### Scenario: ToolsNode asStreamingRunnable extension
- **GIVEN** a ToolsNode instance
- **WHEN** asStreamingRunnable is called
- **THEN** a Runnable[List[ToolCall], ToolMessage] is returned

### Requirement: Batch Execution Results
The system SHALL provide comprehensive batch execution result tracking with success/failure separation and error details.

#### Scenario: All tools succeed
- **GIVEN** a batch execution where all tools succeed
- **WHEN** results are collected
- **THEN** ToolExecutionResult.outputs contains all outputs and failedTools is empty

#### Scenario: Some tools fail
- **GIVEN** a batch execution where some tools throw exceptions
- **WHEN** results are collected
- **THEN** ToolExecutionResult.outputs contains successful outputs and failedTools contains failures

#### Scenario: Parallel execution collects failures
- **GIVEN** parallel execution with multiple tools and some failures
- **WHEN** executeParallel completes
- **THEN** successes and failures are partitioned correctly

#### Scenario: Sequential execution collects failures
- **GIVEN** sequential execution with multiple tools and some failures
- **WHEN** executeSequentially completes
- **THEN** successes and failures are accumulated correctly
