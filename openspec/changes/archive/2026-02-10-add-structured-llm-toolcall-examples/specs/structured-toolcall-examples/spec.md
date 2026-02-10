## ADDED Requirements

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
