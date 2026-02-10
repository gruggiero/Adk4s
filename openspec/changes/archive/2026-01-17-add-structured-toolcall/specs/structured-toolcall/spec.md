# structured-toolcall Specification

## Purpose

Provides a type-safe, composable wrapper for llm4s tool calling with the signature `ToolCall => F[A]`. Enables typed argument parsing, typed result decoding, and unified error handling for tool execution.

## ADDED Requirements

### Requirement: ToolSchema Typeclass

The system SHALL provide a `ToolSchema[A]` typeclass for defining tool argument and result schemas with JSON encoding and decoding capabilities.

#### Scenario: Create ToolSchema instance
- **GIVEN** a case class type representing tool arguments
- **WHEN** `ToolSchema.instance` is called with jsonSchema, description, decoder, and encoder
- **THEN** a `ToolSchema[A]` instance is created

#### Scenario: Decode valid JSON to typed value
- **GIVEN** a `ToolSchema[A]` instance and valid JSON matching the schema
- **WHEN** the decoder is applied to the JSON
- **THEN** a `Right[A]` containing the decoded value is returned

#### Scenario: Decode invalid JSON returns error
- **GIVEN** a `ToolSchema[A]` instance and JSON with missing required fields
- **WHEN** the decoder is applied to the JSON
- **THEN** a `Left[ToolSchemaError]` with validation details is returned

#### Scenario: Encode typed value to JSON
- **GIVEN** a `ToolSchema[A]` instance and a typed value
- **WHEN** the encoder is applied
- **THEN** a `ujson.Value` representing the JSON is returned

#### Scenario: Access JSON schema definition
- **GIVEN** a `ToolSchema[A]` instance
- **WHEN** `jsonSchema` is accessed
- **THEN** the JSON schema definition is returned as `ujson.Value`

### Requirement: StructuredToolCall Trait

The system SHALL provide a `StructuredToolCall[F[_]]` trait for executing tool calls with typed inputs and outputs.

#### Scenario: Execute tool call with typed input and output
- **GIVEN** a `StructuredToolCall[F]` instance and a `ToolCall` with valid arguments
- **WHEN** `execute[I, O](toolCall)` is called with `ToolSchema` evidence for I and O
- **THEN** arguments are parsed to type I, tool executes, and result is decoded to `F[O]`

#### Scenario: Execute tool with invalid arguments fails
- **GIVEN** a `StructuredToolCall[F]` instance and a `ToolCall` with invalid arguments
- **WHEN** `execute[I, O](toolCall)` is called
- **THEN** `F` raises `InvalidArguments` error with parsing details

#### Scenario: Execute unknown tool fails
- **GIVEN** a `StructuredToolCall[F]` instance and a `ToolCall` for unregistered tool
- **WHEN** `execute[I, O](toolCall)` is called
- **THEN** `F` raises `UnknownTool` error with tool name

#### Scenario: Execute tool with execution failure
- **GIVEN** a `StructuredToolCall[F]` instance and a tool that throws an exception
- **WHEN** `execute[I, O](toolCall)` is called
- **THEN** `F` raises `ExecutionFailed` error with underlying cause

#### Scenario: Execute tool with invalid result fails
- **GIVEN** a `StructuredToolCall[F]` instance and a tool returning invalid JSON for output type
- **WHEN** `execute[I, O](toolCall)` is called
- **THEN** `F` raises `ResultParsingFailed` error with raw JSON and message

#### Scenario: Create function from tool name
- **GIVEN** a `StructuredToolCall[F]` instance
- **WHEN** `function[I, O](toolName)` is called
- **THEN** a `ToolCall => F[O]` function is returned for that tool

### Requirement: StructuredToolCall Factory

The system SHALL provide factory methods to create `StructuredToolCall` instances from llm4s `ToolRegistry`.

#### Scenario: Create from ToolRegistry
- **GIVEN** an llm4s `ToolRegistry` with registered tools
- **WHEN** `StructuredToolCall.fromRegistry[F](registry)` is called
- **THEN** a `StructuredToolCall[F]` instance wrapping the registry is returned

#### Scenario: Execute via wrapped registry
- **GIVEN** a `StructuredToolCall[F]` created from a `ToolRegistry`
- **WHEN** a tool call is executed
- **THEN** the underlying `ToolRegistry.execute` is invoked with proper conversion

### Requirement: StructuredToolCallError ADT

The system SHALL provide a sealed error ADT for all structured tool call failures with context for debugging.

#### Scenario: UnknownTool error captures tool name
- **GIVEN** a tool call for an unregistered tool named "missing_tool"
- **WHEN** execution fails
- **THEN** `UnknownTool("missing_tool")` error is raised with descriptive message

#### Scenario: InvalidArguments error captures parsing errors
- **GIVEN** a tool call with arguments missing required field "location"
- **WHEN** argument parsing fails
- **THEN** `InvalidArguments(List("missing required field: location"))` is raised

#### Scenario: ExecutionFailed error captures cause
- **GIVEN** a tool handler that throws `RuntimeException("network error")`
- **WHEN** execution fails
- **THEN** `ExecutionFailed(cause)` is raised with the original exception accessible

#### Scenario: ResultParsingFailed captures raw JSON
- **GIVEN** a tool returning `{"temp": "hot"}` when `Double` expected for temperature
- **WHEN** result parsing fails
- **THEN** `ResultParsingFailed(message, rawJson)` is raised with the raw JSON for debugging

### Requirement: ToolSchemaError

The system SHALL provide error types for schema-level validation and decoding failures.

#### Scenario: Missing required field error
- **GIVEN** JSON missing a required field defined in the schema
- **WHEN** decoding is attempted
- **THEN** a `ToolSchemaError` indicating the missing field is returned

#### Scenario: Type mismatch error
- **GIVEN** JSON with a string value where number is expected
- **WHEN** decoding is attempted
- **THEN** a `ToolSchemaError` indicating the type mismatch is returned

#### Scenario: Invalid enum value error
- **GIVEN** JSON with a value not in the allowed enum set
- **WHEN** decoding is attempted
- **THEN** a `ToolSchemaError` indicating invalid enum value is returned

### Requirement: StructuredToolFunction Wrapper

The system SHALL optionally provide a typed wrapper for building tool functions with input and output schemas.

#### Scenario: Create StructuredToolFunction
- **GIVEN** a tool name, description, `ToolSchema[I]`, and handler `I => Either[Error, O]`
- **WHEN** `StructuredToolFunction[I, O]` is constructed
- **THEN** a typed tool function wrapper is created

#### Scenario: Convert to llm4s ToolFunction
- **GIVEN** a `StructuredToolFunction[I, O]`
- **WHEN** conversion to `ToolFunction` is requested
- **THEN** an llm4s `ToolFunction` with JSON schema and handler is returned
