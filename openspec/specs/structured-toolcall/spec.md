# structured-toolcall Specification

## Purpose
TBD - created by archiving change add-structured-toolcall. Update Purpose after archive.
## Requirements
### Requirement: ToolSchema Typeclass

The system SHALL provide a `ToolSchema[A]` typeclass for defining tool argument and result schemas with JSON encoding and decoding capabilities. The `ToolSchema.derive` method SHALL derive both the JSON Schema (for `AdkToolInfo.parameters`) and the decoder/encoder from a smithy4s `Schema[A]` (or `JsonCodecMaker.make[A]` if a case class has no natural Smithy shape), matching `structured-llm`'s existing `Schema[A]` design so there is one schema system instead of two. The derivation SHALL NOT use `asInstanceOf` (removing the four existing sites in `ToolInfer`/`ToolSchema.derive`), SHALL support nested case classes, collections, and enums (beyond the current six primitives + `Option[primitive]`), and SHALL NOT have a silent `case other => ujson.Str(other.toString)` fallback.

**Given** a case class `WeatherRequest(location: String, unit: String, days: Option[Int], tags: List[String])` with nested types and collections
**When** `ToolSchema.derive[WeatherRequest]` is called
**Then** a `ToolSchema[WeatherRequest]` is derived that decodes JSON with `location`, `unit`, optional `days`, and `tags` array — no `asInstanceOf`, no silent string fallback, and `ToolSchemaError`'s specific cases are populated on failure

**Rationale**: The pre-change `ToolInfer`/`ToolSchema.derive` uses four `asInstanceOf` casts (against the project's "NEVER use asInstanceOf" rule), supports only six primitive types + `Option[primitive]` (no nested case classes, no collections, no enums), has a silent `case other => ujson.Str(other.toString)` fallback that hides type errors, and discards `ToolSchemaError`'s rich cases (`MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, each carrying a `path`) in favor of a flattened `getMessage` string. The fix unifies the two schema systems (ADK `Schema[A]` + tool codec) on smithy4s.

#### Scenario: Create ToolSchema instance
- **GIVEN** a case class type representing tool arguments
- **WHEN** `ToolSchema.instance` is called with jsonSchema, description, decoder, and encoder
- **THEN** a `ToolSchema[A]` instance is created

#### Scenario: Decode valid JSON to typed value
- **GIVEN** a `ToolSchema[A]` instance and valid JSON matching the schema
- **WHEN** the decoder is applied to the JSON
- **THEN** a `Right[A]` containing the decoded value is returned

#### Scenario: Decode invalid JSON returns specific ToolSchemaError

**Given** a `ToolSchema[A]` instance and JSON with a missing required field "location"
**When** the decoder is applied to the JSON
**Then** a `Left[ToolSchemaError.MissingRequiredField]` is returned carrying the field path `["location"]` — NOT a flattened `getMessage` string

#### Scenario: Type mismatch produces ToolSchemaError.TypeMismatch

**Given** JSON with a string value `"hot"` where `Int` is expected for field `temperature`
**When** the decoder is applied
**Then** a `Left[ToolSchemaError.TypeMismatch]` is returned carrying the field path `["temperature"]`, the expected type `Int`, and the actual value

#### Scenario: Invalid enum value produces ToolSchemaError.InvalidEnumValue

**Given** JSON with a value `"Pending"` not in the allowed enum set `["Active", "Inactive"]`
**When** the decoder is applied
**Then** a `Left[ToolSchemaError.InvalidEnumValue]` is returned carrying the field path, the invalid value `"Pending"`, and the allowed values

#### Scenario: Encode typed value to JSON
- **GIVEN** a `ToolSchema[A]` instance and a typed value
- **WHEN** the encoder is applied
- **THEN** a `ujson.Value` representing the JSON is returned (unchanged — `jsonSchema` and encoder output stay `ujson.Value` because they feed `AdkToolInfo.parameters` → `org.llm4s.toolapi`, which is boundary data)

#### Scenario: Access JSON schema definition
- **GIVEN** a `ToolSchema[A]` instance
- **WHEN** `jsonSchema` is accessed
- **THEN** the JSON schema definition is returned as `ujson.Value` (unchanged — boundary data for `AdkToolInfo.parameters`)

#### Scenario: Derive schema for nested case class with collections

**Given** a case class `Order(items: List[Item], total: BigDecimal)` where `Item` is a nested case class
**When** `ToolSchema.derive[Order]` is called
**Then** the derived schema decodes JSON with a `items` array of nested `Item` objects and a `total` `BigDecimal` — no `asInstanceOf`, no silent fallback, no six-primitive limit

#### Scenario: No silent string fallback

**Given** a JSON value of an unexpected type for a field
**When** the decoder is applied
**Then** a `Left[ToolSchemaError]` is returned (specific case) — NOT a `Right` with the value silently converted via `other.toString`

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

The system SHALL provide error types for schema-level validation and decoding failures. The decoder SHALL populate the specific cases (`MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, `DecodingFailed`) with field paths and contextual details, NOT a flattened `getMessage` string. Every match over `ToolSchemaError` SHALL be exhaustive — no catch-all arm is permitted (enforced by the project's exhaustiveness escalation).

**Given** JSON missing a required field defined in the schema
**When** decoding is attempted
**Then** a `ToolSchemaError.MissingRequiredField` is returned carrying the field path

**Rationale**: `ToolSchemaError`'s sealed hierarchy already defines `MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, and `DecodingFailed` (verified in the concept inventory — 4 variants). The pre-change decoder discards these in favor of a flattened `getMessage` string, hiding the structured error information that callers and debuggers need. Populating the specific cases makes the error algebra useful and enables callers to pattern-match on the failure kind.

#### Scenario: Missing required field error

**Given** JSON missing a required field "location" defined in the schema
**When** decoding is attempted
**Then** a `ToolSchemaError.MissingRequiredField(path = List("location"))` is returned

#### Scenario: Type mismatch error

**Given** JSON with a string value where number is expected at field "temperature"
**When** decoding is attempted
**Then** a `ToolSchemaError.TypeMismatch(path = List("temperature"), expected = "Int", actual = "String")` is returned

#### Scenario: Invalid enum value error

**Given** JSON with a value not in the allowed enum set at field "status"
**When** decoding is attempted
**Then** a `ToolSchemaError.InvalidEnumValue(path = List("status"), value = "Pending", allowed = List("Active", "Inactive"))` is returned

#### Scenario: Decoding failure (catch-all for smithy4s decode errors)

**Given** a JSON value that fails smithy4s decoding for a reason not covered by the above cases
**When** decoding is attempted
**Then** a `ToolSchemaError.DecodingFailed(path, message)` is returned carrying the smithy4s error message

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

### Requirement: StructuredToolCall examples SHALL demonstrate ReAct agent patterns with typed tools
The system SHALL provide examples showing how to use StructuredToolCall for ReAct agent patterns with compile-time type-safe tool arguments and results.

#### Scenario: ReAct agent with typed tools
- **WHEN** user runs ReactAgentStructuredExample
- **THEN** system executes ReAct loop using StructuredToolCall with typed tool arguments and results

#### Scenario: Tool argument type safety
- **WHEN** ReactAgentStructuredExample receives tool call from LLM
- **THEN** StructuredToolCall parses arguments into typed case class using ToolSchema.derive

#### Scenario: Tool result type safety
- **WHEN** ReactAgentStructuredExample tool execution completes
- **THEN** result is encoded to JSON using ToolSchema encoder and returned as typed response

### Requirement: StructuredToolCall examples SHALL demonstrate dynamic tool registry patterns
The system SHALL provide examples showing how to use StructuredToolCall with dynamic tool registration while maintaining compile-time type safety.

#### Scenario: Dynamic tool registration example
- **WHEN** user runs DynamicToolRegistryStructuredExample
- **THEN** system dynamically registers typed tools using StructuredToolCall.createTool API

#### Scenario: Typed tool creation
- **WHEN** DynamicToolRegistryStructuredExample creates tools at runtime
- **THEN** each tool is created with TypedTool[F, I, O] maintaining input/output type information

#### Scenario: Registry compatibility
- **WHEN** DynamicToolRegistryStructuredExample converts TypedTool to InvokableTool
- **THEN** tool can be added to ToolRegistry via asInvokableTool method

### Requirement: StructuredToolCall examples SHALL demonstrate WIOGraph tool integration
The system SHALL provide examples showing how to integrate StructuredToolCall with WIOGraph for typed tool execution within workflow graphs.

#### Scenario: WIOGraph tool node integration
- **WHEN** user runs WIOGraphToolStructuredExample
- **THEN** system executes graph with tool nodes using StructuredToolCall for typed tool execution

#### Scenario: Graph node receives typed tool results
- **WHEN** WIOGraphToolStructuredExample tool node executes
- **THEN** subsequent graph nodes receive typed tool results for flow control

#### Scenario: Tool execution within graph context
- **WHEN** WIOGraphToolStructuredExample executes tool in graph node
- **THEN** tool has access to graph context and can emit structured results

### Requirement: StructuredToolCall examples SHALL enhance ToolSchemaExample with complete execution lifecycle
The system SHALL extend existing ToolSchemaExample to demonstrate complete StructuredToolCall execution patterns beyond just schema inference.

#### Scenario: ToolSchemaExample includes StructuredToolCall execution
- **WHEN** user runs ToolSchemaExample
- **THEN** example includes new "Scenario 4: Execute with StructuredToolCall" section demonstrating typed tool execution

#### Scenario: ToolSchemaExample shows tool creation
- **WHEN** ToolSchemaExample Scenario 4 executes
- **THEN** example creates TypedTool using StructuredToolCall.createTool with derived schemas

#### Scenario: ToolSchemaExample shows typed execution
- **WHEN** ToolSchemaExample Scenario 4 receives ToolCall
- **THEN** example executes using typed arguments and returns typed result

#### Scenario: Existing ToolSchemaExample scenarios preserved
- **WHEN** ToolSchemaExample is enhanced
- **THEN** existing Scenarios 1-3 (schema inference, tool creation, JSON fix) remain unchanged

### Requirement: StructuredToolCall examples SHALL use ToolSchema.derive for automatic schema derivation
The system SHALL demonstrate ToolSchema.derive method for automatic encoder/decoder generation from case classes.

#### Scenario: Derive input schema
- **WHEN** StructuredToolCall example defines tool input case class
- **THEN** example uses ToolSchema.derive to automatically generate input schema

#### Scenario: Derive output schema
- **WHEN** StructuredToolCall example defines tool result case class
- **THEN** example uses ToolSchema.derive to automatically generate output encoder/decoder

#### Scenario: Round-trip encoding/decoding
- **WHEN** StructuredToolCall example executes tool
- **THEN** arguments are decoded to typed input, processed, and result encoded to JSON automatically

### Requirement: StructuredToolCall examples SHALL support both mock and real LLM execution
The system SHALL allow all StructuredToolCall examples to run with either MockChatModel or real LLM based on OPENAI_API_KEY environment variable.

#### Scenario: Mock mode execution
- **WHEN** user runs any StructuredToolCall example without OPENAI_API_KEY set
- **THEN** example uses MockChatModel returning deterministic tool call requests

#### Scenario: Real LLM execution
- **WHEN** user runs any StructuredToolCall example with OPENAI_API_KEY environment variable set
- **THEN** example uses real LLM client and executes actual tool calls

#### Scenario: Mock tool call responses
- **WHEN** MockChatModel returns tool call in StructuredToolCall example
- **THEN** tool call includes schema-compliant arguments matching ToolSchema expectations

### Requirement: StructuredToolCall examples SHALL demonstrate error handling patterns
The system SHALL show how StructuredToolCall handles various error conditions with typed error responses.

#### Scenario: Invalid argument handling
- **WHEN** StructuredToolCall example receives tool call with invalid arguments
- **THEN** StructuredToolCall returns StructuredToolCallError.InvalidArguments with detailed schema errors

#### Scenario: Tool execution error handling
- **WHEN** tool implementation raises exception during execution
- **THEN** StructuredToolCall wraps error in StructuredToolCallError.ExecutionFailed with cause

#### Scenario: Result parsing error handling
- **WHEN** tool returns JSON that doesn't match output schema
- **THEN** StructuredToolCall returns StructuredToolCallError.ResultParsingFailed with raw JSON for debugging

### Requirement: StructuredToolCall examples SHALL use Smithy or derived schemas for tool types
The system SHALL define StructuredToolCall example tool input/output types using either Smithy schemas or ToolSchema.derive.

#### Scenario: Derived schemas for simple types
- **WHEN** StructuredToolCall example has simple tool input/output case classes
- **THEN** example uses ToolSchema.derive for automatic schema generation

#### Scenario: Smithy schemas for complex types
- **WHEN** StructuredToolCall example has complex tool types with validation rules
- **THEN** example defines types in examples.smithy and creates Schema[A] wrapper

#### Scenario: Consistent namespace
- **WHEN** StructuredToolCall example defines Smithy schemas
- **THEN** schemas use org.adk4s.examples.structured.schemas namespace

### Requirement: StructuredToolCall examples SHALL be runnable via run-example.sh script
The system SHALL update run-example.sh to include entries for all 3 new StructuredToolCall examples plus enhanced ToolSchemaExample.

#### Scenario: Script includes new examples
- **WHEN** user runs run-example.sh --list
- **THEN** script displays 3 new StructuredToolCall examples under "Structured ToolCall Examples" section

#### Scenario: Script includes enhanced ToolSchemaExample
- **WHEN** user runs run-example.sh ToolSchemaExample
- **THEN** script executes enhanced version showing all 4 scenarios including StructuredToolCall execution

#### Scenario: Script executes example by name
- **WHEN** user runs run-example.sh ReactAgentStructuredExample
- **THEN** script executes org.adk4s.examples.structured.toolcall.ReactAgentStructuredExample

### Requirement: StructuredToolCall examples SHALL update README with structured examples section
The system SHALL add "Structured ToolCall (Type-Safe Tool Execution)" subsection to adk4s-examples/README.md.

#### Scenario: README includes StructuredToolCall section
- **WHEN** user reads adk4s-examples/README.md
- **THEN** README contains "Structured ToolCall" subsection listing all 3 new examples and enhanced ToolSchemaExample

#### Scenario: README documents TypedTool API
- **WHEN** user reads StructuredToolCall README section
- **THEN** README explains StructuredToolCall.createTool convenience API and TypedTool usage

#### Scenario: README cross-references tool examples
- **WHEN** structured tool example mirrors existing tool pattern
- **THEN** README documents comparison between manual ujson parsing and typed StructuredToolCall approach

