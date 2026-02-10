## ADDED Requirements

### Requirement: Message Type Conversion
The system SHALL provide bidirectional conversion between structured-llm Message types and LLM4S Message types, preserving all message properties including role, content, tool calls, and tool call identifiers.

#### Scenario: Convert System message from ADK to LLM4S
- **GIVEN** an ADK Message with Role.System and content "You are a helpful assistant"
- **WHEN** the message is converted using asLlm4s
- **THEN** a LLM4S SystemMessage is created with the same content

#### Scenario: Convert User message from ADK to LLM4S
- **GIVEN** an ADK Message with Role.User and content "Hello, AI!"
- **WHEN** the message is converted using asLlm4s
- **THEN** a LLM4S UserMessage is created with the same content

#### Scenario: Convert Assistant message from ADK to LLM4S
- **GIVEN** an ADK Message with Role.Assistant and content "I can help you"
- **WHEN** the message is converted using asLlm4s
- **THEN** a LLM4S AssistantMessage is created with content set to Some("I can help you") and empty toolCalls

#### Scenario: Convert Assistant message with tool calls from ADK to LLM4S
- **GIVEN** an ADK Message with Role.Assistant and content "Let me use a tool"
- **WHEN** the message is converted using asLlm4s and includes tool calls
- **THEN** a LLM4S AssistantMessage is created with the content and tool calls preserved

#### Scenario: Convert Tool message from ADK to LLM4S
- **GIVEN** an ADK Message with Role.Tool and content "Result" and toolCallId "call_123"
- **WHEN** the message is converted using asLlm4s
- **THEN** a LLM4S ToolMessage is created with content "Result" and toolCallId "call_123"

#### Scenario: Convert LLM4S message to ADK
- **GIVEN** any LLM4S message type (System, User, Assistant, Tool)
- **WHEN** the message is converted using asAdk
- **THEN** an ADK Message is created with the appropriate Role and content preserved

#### Scenario: Round-trip conversion preserves data
- **GIVEN** any ADK Message
- **WHEN** converted to LLM4S and back to ADK
- **THEN** the resulting ADK Message has the same Role, content, and metadata as the original

### Requirement: Conversation Conversion
The system SHALL provide bidirectional conversion between structured-llm Prompt and LLM4S Conversation, preserving message ordering and all message properties.

#### Scenario: Convert empty prompt to conversation
- **GIVEN** an ADK Prompt with no messages
- **WHEN** the prompt is converted using asConversation
- **THEN** a LLM4S Conversation with empty messages vector is created

#### Scenario: Convert single-message prompt to conversation
- **GIVEN** an ADK Prompt with one system message
- **WHEN** the prompt is converted using asConversation
- **THEN** a LLM4S Conversation with one message is created with correct message type

#### Scenario: Convert multi-message prompt to conversation
- **GIVEN** an ADK Prompt with system, user, and assistant messages in that order
- **WHEN** the prompt is converted using asConversation
- **THEN** a LLM4S Conversation is created with messages in the same order
- **AND** each message has the correct type and content

#### Scenario: Convert conversation to prompt
- **GIVEN** a LLM4S Conversation with multiple messages
- **WHEN** the conversation is converted using asPrompt
- **THEN** an ADK Prompt is created with the same number of messages in the same order

#### Scenario: Round-trip conversation conversion preserves data
- **GIVEN** an ADK Prompt with multiple messages
- **WHEN** converted to Conversation and back to Prompt
- **THEN** the resulting Prompt has the same number of messages
- **AND** each message is identical to the original (same role and content)

### Requirement: Unified Error Handling
The system SHALL provide a unified AdkError hierarchy that wraps LLM4S and structured-llm errors, with clear formatting via Show instance.

#### Scenario: Wrap LLM call errors
- **GIVEN** a LLM4S LLMError with underlying failure
- **WHEN** wrapped in LlmCallError
- **THEN** the error message includes "LLM call failed:" prefix
- **AND** includes formatted LLM4S error details

#### Scenario: Wrap structured output errors
- **GIVEN** a StructuredLLMError from structured-llm
- **WHEN** wrapped in StructuredOutputError
- **THEN** the error message includes "Structured output error:" prefix
- **AND** includes underlying error message

#### Scenario: Format type mismatch errors
- **GIVEN** a TypeMismatchError with expected "String", actual "Number", and path ["user", "age"]
- **WHEN** the error message is formatted
- **THEN** the message reads "Type mismatch at user.age: expected String, got Number"

#### Scenario: Format missing field errors
- **GIVEN** a MissingFieldError with field "email" and path ["user"]
- **WHEN** the error message is formatted
- **THEN** the message reads "Missing required field: user.email"

#### Scenario: Format node not found errors
- **GIVEN** a NodeNotFoundError with nodeKey "agent_1"
- **WHEN** the error message is formatted
- **THEN** the message reads "Node 'agent_1' not found in graph"

#### Scenario: Format max steps exceeded errors
- **GIVEN** a MaxStepsExceededError with steps 15 and max 10
- **WHEN** the error message is formatted
- **THEN** the message reads "Exceeded maximum steps: 15 > 10"

#### Scenario: Format tool execution errors
- **GIVEN** a ToolExecutionError with toolName "search" and underlying exception
- **WHEN** the error message is formatted
- **THEN** the message reads "Tool 'search' execution failed:" with cause message

#### Scenario: Show instance formats errors correctly
- **GIVEN** any AdkError instance
- **WHEN** Show.show is called on the error
- **THEN** the result equals the error's message field

### Requirement: Core Type Aliases
The system SHALL provide type aliases for common effectful operations and re-export commonly used types from dependencies for convenience.

#### Scenario: Use AdkIO type alias
- **GIVEN** a function returning IO[A]
- **WHEN** typed as AdkIO[A]
- **THEN** the function compiles successfully
- **AND** AdkIO[A] is equivalent to IO[A]

#### Scenario: Use AdkResult type alias
- **GIVEN** a function returning Either[AdkError, A]
- **WHEN** typed as AdkResult[A]
- **THEN** the function compiles successfully
- **AND** AdkResult[A] is equivalent to Either[AdkError, A]

#### Scenario: Use AdkIOResult type alias
- **GIVEN** a function returning IO[Either[AdkError, A]]
- **WHEN** typed as AdkIOResult[A]
- **THEN** the function compiles successfully
- **AND** AdkIOResult[A] is equivalent to IO[Either[AdkError, A]]

#### Scenario: Re-export structured-llm types
- **GIVEN** code needing to use Message, Role, Prompt, Schema, PromptTemplate
- **WHEN** imported from org.adk4s.core.types.*
- **THEN** all types are available without additional imports

#### Scenario: Re-export LLM4S types
- **GIVEN** code needing to use LlmConversation, LlmCompletion, LlmMessage, ToolCall, FunctionCall
- **WHEN** imported from org.adk4s.core.types.*
- **THEN** all types are available without additional imports

### Requirement: NodeKey Type-Safe Identifier
The system SHALL provide an opaque NodeKey type for type-safe node identifiers in graph construction, with validation for non-empty strings and reserved name protection.

#### Scenario: Create valid node key
- **GIVEN** a string "agent_1"
- **WHEN** NodeKey.apply is called
- **THEN** Right(nodeKey) is returned
- **AND** nodeKey.value equals "agent_1"

#### Scenario: Reject empty node key
- **GIVEN** an empty string ""
- **WHEN** NodeKey.apply is called
- **THEN** Left("Node key cannot be empty") is returned

#### Scenario: Reject reserved start key
- **GIVEN** the string "__start__"
- **WHEN** NodeKey.apply is called
- **THEN** Left("Node key '__start__' is reserved") is returned

#### Scenario: Reject reserved end key
- **GIVEN** the string "__end__"
- **WHEN** NodeKey.apply is called
- **THEN** Left("Node key '__end__' is reserved") is returned

#### Scenario: Use unsafeApply for trusted keys
- **GIVEN** a trusted string "model_node"
- **WHEN** NodeKey.unsafeApply is called
- **THEN** a NodeKey is returned directly
- **AND** nodeKey.value equals "model_node"

#### Scenario: UnsafeApply throws on invalid key
- **GIVEN** an empty string ""
- **WHEN** NodeKey.unsafeApply is called
- **THEN** IllegalArgumentException is thrown with message containing "Invalid node key"

#### Scenario: Check if node key is start
- **GIVEN** the START reserved node key
- **WHEN** nodeKey.isStart is called
- **THEN** true is returned

#### Scenario: Check if node key is not start
- **GIVEN** a custom node key "agent_1"
- **WHEN** nodeKey.isStart is called
- **THEN** false is returned

#### Scenario: Check if node key is end
- **GIVEN** the END reserved node key
- **WHEN** nodeKey.isEnd is called
- **THEN** true is returned

#### Scenario: Check if node key is reserved
- **GIVEN** any reserved node key ("__start__" or "__end__")
- **WHEN** nodeKey.isReserved is called
- **THEN** true is returned

#### Scenario: Eq instance compares by value
- **GIVEN** two NodeKeys with same string value
- **WHEN** compared using Eq[NodeKey].eqv
- **THEN** true is returned

#### Scenario: Order instance sorts alphabetically
- **GIVEN** three NodeKeys: "agent_1", "agent_2", "agent_10"
- **WHEN** sorted using Order[NodeKey]
- **THEN** order is "agent_1", "agent_10", "agent_2" (lexicographic)

#### Scenario: Show instance formats to value
- **GIVEN** a NodeKey with value "model_node"
- **WHEN** Show[NodeKey].show is called
- **THEN** result equals "model_node"

### Requirement: FieldPath Type-Safe Navigation
The system SHALL provide an opaque FieldPath type for type-safe field path navigation in workflow field mapping, with parsing from dotted strings and path manipulation operations.

#### Scenario: Parse dotted path string
- **GIVEN** a string "user.profile.name"
- **WHEN** FieldPath.apply is called
- **THEN** FieldPath with segments Vector("user", "profile", "name") is created

#### Scenario: Parse empty path string
- **GIVEN** an empty string ""
- **WHEN** FieldPath.apply is called
- **THEN** Root (empty FieldPath) is returned

#### Scenario: Create path from segments
- **GIVEN** segments "response", "data", "items"
- **WHEN** FieldPath.fromSegments is called
- **THEN** FieldPath with segments Vector("response", "data", "items") is created

#### Scenario: Access path segments
- **GIVEN** a FieldPath with segments Vector("a", "b", "c")
- **WHEN** path.segments is accessed
- **THEN** Vector("a", "b", "c") is returned

#### Scenario: Check if path is empty
- **GIVEN** the Root FieldPath
- **WHEN** path.isEmpty is called
- **THEN** true is returned

#### Scenario: Check if path is non-empty
- **GIVEN** a FieldPath with one segment
- **WHEN** path.nonEmpty is called
- **THEN** true is returned

#### Scenario: Get head of path
- **GIVEN** a FieldPath with segments Vector("a", "b", "c")
- **WHEN** path.head is called
- **THEN** Some("a") is returned

#### Scenario: Get head of empty path
- **GIVEN** the Root FieldPath
- **WHEN** path.head is called
- **THEN** None is returned

#### Scenario: Get tail of path
- **GIVEN** a FieldPath with segments Vector("a", "b", "c")
- **WHEN** path.tail is called
- **THEN** FieldPath with segments Vector("b", "c") is returned

#### Scenario: Get tail of single-element path
- **GIVEN** a FieldPath with segments Vector("a")
- **WHEN** path.tail is called
- **THEN** Root (empty FieldPath) is returned

#### Scenario: Append segment to path
- **GIVEN** a FieldPath with segments Vector("a", "b")
- **WHEN** path :+ "c" is called
- **THEN** FieldPath with segments Vector("a", "b", "c") is returned

#### Scenario: Concatenate two paths
- **GIVEN** FieldPath1 with segments Vector("a", "b") and FieldPath2 with segments Vector("c", "d")
- **WHEN** path1 ++ path2 is called
- **THEN** FieldPath with segments Vector("a", "b", "c", "d") is returned

#### Scenario: Render path to dotted string
- **GIVEN** a FieldPath with segments Vector("user", "profile", "name")
- **WHEN** path.render is called
- **THEN** "user.profile.name" is returned

#### Scenario: Render empty path to string
- **GIVEN** the Root FieldPath
- **WHEN** path.render is called
- **THEN** empty string "" is returned

#### Scenario: Show instance uses render
- **GIVEN** a FieldPath with segments Vector("a", "b", "c")
- **WHEN** Show[FieldPath].show is called
- **THEN** result equals "a.b.c"

### Requirement: RunInfo Execution Metadata
The system SHALL provide a RunInfo case class for metadata about node execution, including node key, component type, optional name, optional timing, and parent path for nested execution.

#### Scenario: Create basic RunInfo
- **GIVEN** a NodeKey "model" and componentType "LLM"
- **WHEN** RunInfo.forNode is called with these parameters
- **THEN** RunInfo is created with nodeKey "model", componentType "LLM", and None for optional fields

#### Scenario: Create RunInfo with name
- **GIVEN** a NodeKey "model", componentType "LLM", and name "Primary Model"
- **WHEN** RunInfo.forNode is called with these parameters
- **THEN** RunInfo is created with nodeKey "model", componentType "LLM", nodeName Some("Primary Model")

#### Scenario: Create RunInfo with all fields
- **GIVEN** all parameters: nodeKey, componentType, nodeName, startTime, and parentPath
- **WHEN** RunInfo case class is instantiated
- **THEN** all fields are stored correctly

#### Scenario: Calculate full path from parent path
- **GIVEN** RunInfo with nodeKey "inner" and parentPath List(NodeKey("outer"))
- **WHEN** info.fullPath is calculated
- **THEN** List(NodeKey("outer"), NodeKey("inner")) is returned

#### Scenario: Full path is empty for top-level node
- **GIVEN** RunInfo with nodeKey "root" and empty parentPath
- **WHEN** info.fullPath is calculated
- **THEN** List(NodeKey("root")) is returned

#### Scenario: Show formats RunInfo with name
- **GIVEN** RunInfo with nodeKey "model", componentType "LLM", nodeName Some("GPT-4")
- **WHEN** Show[RunInfo].show is called
- **THEN** result includes nodeKey value, name in parentheses, and componentType

#### Scenario: Show formats RunInfo without name
- **GIVEN** RunInfo with nodeKey "tool", componentType "Function", nodeName None
- **WHEN** Show[RunInfo].show is called
- **THEN** result includes nodeKey value and componentType without parentheses

#### Scenario: Show formats RunInfo with parent path
- **GIVEN** RunInfo with nodeKey "inner", componentType "LLM", and parentPath with one parent
- **WHEN** Show[RunInfo].show is called
- **THEN** result includes parent path in brackets with " -> " separator
