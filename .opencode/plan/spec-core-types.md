# Spec: core-types

## Purpose

Provides foundational opaque types and case classes for ADK4S, including type-safe identifiers (NodeKey, FieldPath), execution metadata (RunInfo), and convenient type aliases for effects and common types.

## Requirements

### Requirement: NodeKey Opaque Type

The system SHALL provide an opaque type `NodeKey` representing type-safe node identifiers for graph construction.

#### Scenario: Create valid NodeKey
- **GIVEN** a string "processor"
- **WHEN** NodeKey.apply("processor") is called
- **THEN** Right(nodeKey) is returned
- **AND** nodeKey.value is "processor"

#### Scenario: Create NodeKey with unsafeApply
- **GIVEN** a string "extractor"
- **WHEN** NodeKey.unsafeApply("extractor") is called
- **THEN** a NodeKey is returned
- **AND** nodeKey.value is "extractor"

#### Scenario: Reject empty NodeKey
- **GIVEN** an empty string ""
- **WHEN** NodeKey.apply("") is called
- **THEN** Left("Node key cannot be empty") is returned

#### Scenario: Reject reserved key __start__
- **GIVEN** string "__start__"
- **WHEN** NodeKey.apply("__start__") is called
- **THEN** Left("Node key '__start__' is reserved") is returned

#### Scenario: Reject reserved key __end__
- **GIVEN** string "__end__"
- **WHEN** NodeKey.apply("__end__") is called
- **THEN** Left("Node key '__end__' is reserved") is returned

#### Scenario: Access START constant
- **WHEN** NodeKey.START is accessed
- **THEN** a NodeKey with value "__start__" is returned

#### Scenario: Access END constant
- **WHEN** NodeKey.END is accessed
- **THEN** a NodeKey with value "__end__" is returned

#### Scenario: Check if NodeKey is start
- **GIVEN** NodeKey.START
- **WHEN** isStart is called
- **THEN** the result is true

#### Scenario: Check if NodeKey is end
- **GIVEN** NodeKey.END
- **WHEN** isEnd is called
- **THEN** the result is true

#### Scenario: Check if NodeKey is reserved
- **GIVEN** NodeKey.START
- **WHEN** isReserved is called
- **THEN** the result is true

#### Scenario: Check non-reserved NodeKey
- **GIVEN** a NodeKey with value "analyzer"
- **WHEN** isReserved is called
- **THEN** the result is false

### Requirement: NodeKey Typeclass Instances

The system SHALL provide Eq, Show, and Order instances for NodeKey.

#### Scenario: Eq instance compares by value
- **GIVEN** two NodeKeys with value "processor"
- **WHEN** Eq[NodeKey].eqv(key1, key2) is called
- **THEN** the result is true

#### Scenario: Eq instance distinguishes different values
- **GIVEN** NodeKey with value "processor" and NodeKey with value "extractor"
- **WHEN** Eq[NodeKey].eqv(key1, key2) is called
- **THEN** the result is false

#### Scenario: Show instance formats NodeKey
- **GIVEN** a NodeKey with value "analyzer"
- **WHEN** Show[NodeKey].show(key) is called
- **THEN** the result is "analyzer"

#### Scenario: Order instance sorts by value
- **GIVEN** NodeKeys "analyzer", "processor", "extractor"
- **WHEN** Order[NodeKey].compare is used to sort
- **THEN** the order is ["analyzer", "extractor", "processor"]

### Requirement: FieldPath Opaque Type

The system SHALL provide an opaque type `FieldPath` representing type-safe field paths for workflow field mapping.

#### Scenario: Create FieldPath from empty string
- **GIVEN** an empty string ""
- **WHEN** FieldPath.apply("") is called
- **THEN** a FieldPath with empty segments is returned
- **AND** isEmpty is true

#### Scenario: Create FieldPath from dotted string
- **GIVEN** string "user.profile.email"
- **WHEN** FieldPath.apply("user.profile.email") is called
- **THEN** a FieldPath with segments ["user", "profile", "email"] is returned
- **AND** isEmpty is false

#### Scenario: Create FieldPath from segments
- **GIVEN** segments "user", "name", "first"
- **WHEN** FieldPath.fromSegments("user", "name", "first") is called
- **THEN** a FieldPath with segments ["user", "name", "first"] is returned

#### Scenario: Access Root constant
- **WHEN** FieldPath.Root is accessed
- **THEN** a FieldPath with empty segments is returned
- **AND** isEmpty is true

#### Scenario: Get segments from FieldPath
- **GIVEN** FieldPath "user.profile.email"
- **WHEN** segments is accessed
- **THEN** Vector("user", "profile", "email") is returned

#### Scenario: Check empty FieldPath
- **GIVEN** FieldPath.Root
- **WHEN** isEmpty is checked
- **THEN** the result is true

#### Scenario: Check non-empty FieldPath
- **GIVEN** FieldPath "user.email"
- **WHEN** nonEmpty is checked
- **THEN** the result is true

#### Scenario: Get head of FieldPath
- **GIVEN** FieldPath "user.profile.email"
- **WHEN** head is accessed
- **THEN** Some("user") is returned

#### Scenario: Get head of empty FieldPath
- **GIVEN** FieldPath.Root
- **WHEN** head is accessed
- **THEN** None is returned

#### Scenario: Get tail of FieldPath
- **GIVEN** FieldPath "user.profile.email"
- **WHEN** tail is accessed
- **THEN** a FieldPath with segments ["profile", "email"] is returned

#### Scenario: Get tail of empty FieldPath
- **GIVEN** FieldPath.Root
- **WHEN** tail is accessed
- **THEN** FieldPath.Root is returned

#### Scenario: Append segment to FieldPath
- **GIVEN** FieldPath "user.profile"
- **WHEN** :+ ("email") is called
- **THEN** a FieldPath with segments ["user", "profile", "email"] is returned

#### Scenario: Concatenate two FieldPaths
- **GIVEN** FieldPath "user"
- **GIVEN** FieldPath "profile.email"
- **WHEN** ++ is called
- **THEN** a FieldPath with segments ["user", "profile", "email"] is returned

#### Scenario: Render FieldPath to string
- **GIVEN** FieldPath with segments ["user", "profile", "email"]
- **WHEN** render is accessed
- **THEN** "user.profile.email" is returned

#### Scenario: Render empty FieldPath
- **GIVEN** FieldPath.Root
- **WHEN** render is accessed
- **THEN** empty string is returned

### Requirement: FieldPath Show Instance

The system SHALL provide a Show instance for FieldPath.

#### Scenario: Show multi-segment FieldPath
- **GIVEN** FieldPath with segments ["user", "profile", "email"]
- **WHEN** Show[FieldPath].show(path) is called
- **THEN** "user.profile.email" is returned

#### Scenario: Show empty FieldPath
- **GIVEN** FieldPath.Root
- **WHEN** Show[FieldPath].show(path) is called
- **THEN** empty string is returned

### Requirement: RunInfo Case Class

The system SHALL provide a `RunInfo` case class for metadata about node execution.

#### Scenario: Create minimal RunInfo
- **GIVEN** NodeKey "analyzer"
- **GIVEN** componentType "LLMNode"
- **WHEN** RunInfo.forNode(key, componentType) is called
- **THEN** RunInfo with nodeKey, componentType, None nodeName, None startTime, and empty parentPath is returned

#### Scenario: Create RunInfo with name
- **GIVEN** NodeKey "analyzer"
- **GIVEN** componentType "LLMNode"
- **GIVEN** name "Sentiment Analyzer"
- **WHEN** RunInfo.forNode(key, componentType, name) is called
- **THEN** RunInfo with nodeName Some("Sentiment Analyzer") is returned

#### Scenario: Create RunInfo with full metadata
- **GIVEN** NodeKey "validator"
- **GIVEN** componentType "ToolNode"
- **GIVEN** nodeName "Schema Validator"
- **GIVEN** startTime Some(Instant.now())
- **GIVEN** parentPath List(NodeKey("processor"))
- **WHEN** RunInfo is constructed
- **THEN** all fields are set correctly

#### Scenario: Calculate fullPath with empty parentPath
- **GIVEN** RunInfo with nodeKey NodeKey("analyzer") and empty parentPath
- **WHEN** fullPath is accessed
- **THEN** List(NodeKey("analyzer")) is returned

#### Scenario: Calculate fullPath with parentPath
- **GIVEN** RunInfo with nodeKey NodeKey("validator") and parentPath List(NodeKey("processor"))
- **WHEN** fullPath is accessed
- **THEN** List(NodeKey("processor"), NodeKey("validator")) is returned

#### Scenario: Calculate fullPath with multi-level parentPath
- **GIVEN** RunInfo with nodeKey NodeKey("result") and parentPath List(NodeKey("processor"), NodeKey("analyzer"))
- **WHEN** fullPath is accessed
- **THEN** List(NodeKey("processor"), NodeKey("analyzer"), NodeKey("result")) is returned

### Requirement: RunInfo Show Instance

The system SHALL provide a Show instance for RunInfo.

#### Scenario: Show minimal RunInfo
- **GIVEN** RunInfo with nodeKey "analyzer", componentType "LLMNode", no name, no startTime, empty parentPath
- **WHEN** Show[RunInfo].show(info) is called
- **THEN** "analyzer: LLMNode" is returned

#### Scenario: Show RunInfo with name
- **GIVEN** RunInfo with nodeKey "analyzer", componentType "LLMNode", nodeName Some("Sentiment Analyzer")
- **WHEN** Show[RunInfo].show(info) is called
- **THEN** "analyzer (Sentiment Analyzer): LLMNode" is returned

#### Scenario: Show RunInfo with parentPath
- **GIVEN** RunInfo with nodeKey "validator", componentType "ToolNode", parentPath List(NodeKey("processor"))
- **WHEN** Show[RunInfo].show(info) is called
- **THEN** the result includes " [processor -> validator]"

#### Scenario: Show RunInfo with name and parentPath
- **GIVEN** RunInfo with nodeKey "validator", componentType "ToolNode", nodeName Some("Schema Validator"), parentPath List(NodeKey("processor"), NodeKey("analyzer"))
- **WHEN** Show[RunInfo].show(info) is called
- **THEN** the result includes name and parent path

### Requirement: Core Type Aliases

The system SHALL provide type aliases for common effectful operations and re-export commonly used types from dependencies.

#### Scenario: AdkIO alias
- **GIVEN** type alias AdkIO[A] = IO[A]
- **WHEN** AdkIO[String] is used
- **THEN** it compiles as IO[String]

#### Scenario: AdkResult alias
- **GIVEN** type alias AdkResult[A] = Either[AdkError, A]
- **WHEN** AdkResult[String] is used
- **THEN** it compiles as Either[AdkError, String]

#### Scenario: AdkIOResult alias
- **GIVEN** type alias AdkIOResult[A] = IO[Either[AdkError, A]]
- **WHEN** AdkIOResult[String] is used
- **THEN** it compiles as IO[Either[AdkError, String]]

#### Scenario: Message type alias
- **GIVEN** type alias Message = org.adk4s.structured.core.Message
- **WHEN** Message is used in code
- **THEN** it compiles as org.adk4s.structured.core.Message

#### Scenario: Prompt type alias
- **GIVEN** type alias Prompt = org.adk4s.structured.core.Prompt
- **WHEN** Prompt is used in code
- **THEN** it compiles as org.adk4s.structured.core.Prompt

#### Scenario: Schema type alias
- **GIVEN** type alias Schema[A] = org.adk4s.structured.core.Schema[A]
- **WHEN** Schema[String] is used in code
- **THEN** it compiles as org.adk4s.structured.core.Schema[String]

#### Scenario: LlmConversation type alias
- **GIVEN** type alias LlmConversation = org.llm4s.llmconnect.model.Conversation
- **WHEN** LlmConversation is used in code
- **THEN** it compiles as org.llm4s.llmconnect.model.Conversation

#### Scenario: LlmCompletion type alias
- **GIVEN** type alias LlmCompletion = org.llm4s.llmconnect.model.Completion
- **WHEN** LlmCompletion is used in code
- **THEN** it compiles as org.llm4s.llmconnect.model.Completion

#### Scenario: ToolCall type alias
- **GIVEN** type alias ToolCall = org.llm4s.llmconnect.model.ToolCall
- **WHEN** ToolCall is used in code
- **THEN** it compiles as org.llm4s.llmconnect.model.ToolCall

#### Scenario: FunctionCall type alias
- **GIVEN** type alias FunctionCall = org.llm4s.llmconnect.model.FunctionCall
- **WHEN** FunctionCall is used in code
- **THEN** it compiles as org.llm4s.llmconnect.model.FunctionCall

### Requirement: Type Alias Import Testing

The system SHALL ensure all type aliases work correctly when imported.

#### Scenario: Import and use Message alias
- **GIVEN** import org.adk4s.core.types.*
- **WHEN** Message is used
- **THEN** code compiles successfully

#### Scenario: Import and use AdkIO alias
- **GIVEN** import org.adk4s.core.types.*
- **WHEN** AdkIO is used
- **THEN** code compiles successfully

#### Scenario: Import and use AdkResult alias
- **GIVEN** import org.adk4s.core.types.*
- **WHEN** AdkResult is used
- **THEN** code compiles successfully

#### Scenario: Import and use multiple aliases together
- **GIVEN** import org.adk4s.core.types.*
- **WHEN** Message, Prompt, AdkIO, AdkResult are used together
- **THEN** code compiles successfully
