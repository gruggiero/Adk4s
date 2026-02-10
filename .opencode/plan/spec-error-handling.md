# Spec: error-handling

## Purpose

Provides a unified error hierarchy for ADK4S that wraps errors from LLM4S and structured-llm, plus defines ADK4S-specific error types for validation, workflow, tool, and state management. All errors implement Show for consistent error formatting.

## Requirements

### Requirement: AdkError Base Type

The system SHALL provide a sealed trait `AdkError` that extends Throwable, with a `message: String` field and overrides `getMessage` to return the message.

#### Scenario: AdkError is a Throwable
- **GIVEN** any AdkError instance
- **WHEN** getMessage is called
- **THEN** the error message is returned

#### Scenario: AdkError has custom message field
- **GIVEN** any AdkError instance
- **WHEN** message is accessed
- **THEN** a descriptive string is returned

### Requirement: LLM Call Error

The system SHALL provide `LlmCallError` case class wrapping LLM4S LLMError with a formatted message.

#### Scenario: Wrap LLMError
- **GIVEN** an LLM4S LLMError with formatted message "API timeout"
- **WHEN** LlmCallError(underlying) is created
- **THEN** the message is "LLM call failed: API timeout"
- **AND** the underlying LLMError is preserved

#### Scenario: LlmCallError is an AdkError
- **GIVEN** a LlmCallError instance
- **WHEN** checked with isInstanceOf[AdkError]
- **THEN** the result is true

### Requirement: Structured Output Error

The system SHALL provide `StructuredOutputError` case class wrapping structured-llm StructuredLLMError with a formatted message.

#### Scenario: Wrap StructuredLLMError
- **GIVEN** a StructuredLLMError with message "Invalid JSON"
- **WHEN** StructuredOutputError(underlying) is created
- **THEN** the message is "Structured output error: Invalid JSON"
- **AND** the underlying StructuredLLMError is preserved

#### Scenario: StructuredOutputError is an AdkError
- **GIVEN** a StructuredOutputError instance
- **WHEN** checked with isInstanceOf[AdkError]
- **THEN** the result is true

### Requirement: Type Mismatch Error

The system SHALL provide `TypeMismatchError` case class indicating a schema validation failure at a specific field path.

#### Scenario: Create type mismatch error
- **GIVEN** expected type "String" and actual type "Integer"
- **GIVEN** path ["user", "age"]
- **WHEN** TypeMismatchError(expected, actual, path) is created
- **THEN** the message is "Type mismatch at user.age: expected String, got Integer"

#### Scenario: Type mismatch with single-level path
- **GIVEN** expected type "Int" and actual type "String"
- **GIVEN** path ["value"]
- **WHEN** TypeMismatchError is created
- **THEN** the message is "Type mismatch at value: expected Int, got String"

#### Scenario: Type mismatch with empty path
- **GIVEN** expected type "List" and actual type "String"
- **GIVEN** empty path List()
- **WHEN** TypeMismatchError is created
- **THEN** the message is "Type mismatch at : expected List, got String"

### Requirement: Missing Field Error

The system SHALL provide `MissingFieldError` case class indicating a required field is missing at a specific field path.

#### Scenario: Create missing field error
- **GIVEN** field name "email"
- **GIVEN** path ["user", "profile"]
- **WHEN** MissingFieldError(field, path) is created
- **THEN** the message is "Missing required field: user.profile.email"

#### Scenario: Missing field with empty path
- **GIVEN** field name "id"
- **GIVEN** empty path List()
- **WHEN** MissingFieldError is created
- **THEN** the message is "Missing required field: id"

#### Scenario: Missing field with multi-level path
- **GIVEN** field name "token"
- **GIVEN** path ["auth", "session", "credentials"]
- **WHEN** MissingFieldError is created
- **THEN** the message is "Missing required field: auth.session.credentials.token"

### Requirement: Node Not Found Error

The system SHALL provide `NodeNotFoundError` case class indicating a referenced node does not exist in a graph.

#### Scenario: Create node not found error
- **GIVEN** node key "extractor"
- **WHEN** NodeNotFoundError(nodeKey) is created
- **THEN** the message is "Node 'extractor' not found in graph"

### Requirement: Edge Validation Error

The system SHALL provide `EdgeValidationError` case class indicating an edge between nodes is invalid.

#### Scenario: Create edge validation error
- **GIVEN** from node "processor"
- **GIVEN** to node "validator"
- **GIVEN** reason "validator requires string input but processor produces integer"
- **WHEN** EdgeValidationError(from, to, reason) is created
- **THEN** the message is "Invalid edge processor -> validator: validator requires string input but processor produces integer"

### Requirement: Max Steps Exceeded Error

The system SHALL provide `MaxStepsExceededError` case class indicating a workflow exceeded the maximum allowed steps.

#### Scenario: Create max steps exceeded error
- **GIVEN** steps taken 105
- **GIVEN** max steps 100
- **WHEN** MaxStepsExceededError(steps, max) is created
- **THEN** the message is "Exceeded maximum steps: 105 > 100"

#### Scenario: Max steps exactly at limit
- **GIVEN** steps taken 100
- **GIVEN** max steps 100
- **WHEN** MaxStepsExceededError(steps, max) is created
- **THEN** the message is "Exceeded maximum steps: 100 > 100"

### Requirement: Graph Compiled Error

The system SHALL provide `GraphCompiledError` case class indicating an operation on an already-compiled graph.

#### Scenario: Create graph compiled error
- **WHEN** GraphCompiledError() is created
- **THEN** the message is "Graph already compiled, cannot be modified"

### Requirement: Tool Not Found Error

The system SHALL provide `ToolNotFoundError` case class indicating a tool function does not exist in the registry.

#### Scenario: Create tool not found error
- **GIVEN** tool name "calculator"
- **WHEN** ToolNotFoundError(toolName) is created
- **THEN** the message is "Tool 'calculator' not found in registry"

### Requirement: Tool Execution Error

The system SHALL provide `ToolExecutionError` case class indicating a tool function failed during execution.

#### Scenario: Create tool execution error
- **GIVEN** tool name "web_search"
- **GIVEN** cause a new RuntimeException("Network timeout")
- **WHEN** ToolExecutionError(toolName, cause) is created
- **THEN** the message is "Tool 'web_search' execution failed: Network timeout"
- **AND** the cause is preserved

#### Scenario: Tool execution error with null cause
- **GIVEN** tool name "database_query"
- **GIVEN** cause is null
- **WHEN** ToolExecutionError(toolName, cause) is created
- **THEN** the message includes the tool name
- **AND** no NullPointerException is thrown

### Requirement: State Type Mismatch Error

The system SHALL provide `StateTypeMismatchError` case class indicating a workflow state has the wrong type.

#### Scenario: Create state type mismatch error
- **GIVEN** expected type "WorkflowState"
- **GIVEN** actual type "AgentState"
- **WHEN** StateTypeMismatchError(expected, actual) is created
- **THEN** the message is "State type mismatch: expected WorkflowState, got AgentState"

### Requirement: Show Instance for AdkError

The system SHALL provide a Show[AdkError] instance that formats errors using their message field.

#### Scenario: Show LlmCallError
- **GIVEN** a LlmCallError with message "LLM call failed: API timeout"
- **WHEN** Show[AdkError].show(error) is called
- **THEN** the result is "LLM call failed: API timeout"

#### Scenario: Show TypeMismatchError
- **GIVEN** a TypeMismatchError with message "Type mismatch at user.age: expected String, got Integer"
- **WHEN** Show[AdkError].show(error) is called
- **THEN** the result is "Type mismatch at user.age: expected String, got Integer"

#### Scenario: Show all error types
- **GIVEN** any AdkError instance
- **WHEN** Show[AdkError].show(error) is called
- **THEN** the result equals error.message

### Requirement: Error Wrapping

The system SHALL ensure all wrapped errors (LlmCallError, StructuredOutputError, ToolExecutionError) preserve their underlying causes.

#### Scenario: Preserve LLM4S underlying error
- **GIVEN** a LlmCallError wrapping LLMError
- **WHEN** underlying field is accessed
- **THEN** the original LLMError is returned

#### Scenario: Preserve StructuredLLMError underlying error
- **GIVEN** a StructuredOutputError wrapping StructuredLLMError
- **WHEN** underlying field is accessed
- **THEN** the original StructuredLLMError is returned

#### Scenario: Preserve Throwable cause
- **GIVEN** a ToolExecutionError with cause
- **WHEN** cause field is accessed
- **THEN** the original Throwable is returned
