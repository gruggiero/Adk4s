## 1. Implementation

### 1.1 Message Type Converters
- [x] 1.1.1 Create MessageConverter object with toLlm4s and fromLlm4s methods
- [x] 1.1.2 Add extension methods for asLlm4s and asAdk conversions
- [x] 1.1.3 Handle all role types (System, User, Assistant, Tool)
- [x] 1.1.4 Handle tool calls in AssistantMessage
- [x] 1.1.5 Handle toolCallId in ToolMessage
- [x] 1.1.6 Write unit tests for all conversion paths
- [x] 1.1.7 Test round-trip conversion preserves data

### 1.2 Conversation Converters
- [x] 1.2.1 Create ConversationConverter object
- [x] 1.2.2 Implement toConversation method
- [x] 1.2.3 Implement fromConversation method
- [x] 1.2.4 Add extension methods asConversation and asPrompt
- [x] 1.2.5 Ensure message ordering is preserved
- [x] 1.2.6 Write unit tests for empty, single, and multi-message cases
- [x] 1.2.7 Test round-trip preservation

### 1.3 ADK4S Error Types
- [x] 1.3.1 Create sealed trait AdkError with message field
- [x] 1.3.2 Implement LlmCallError wrapping LLM4S errors
- [x] 1.3.3 Implement StructuredOutputError wrapping structured-llm errors
- [x] 1.3.4 Implement validation error types (TypeMismatchError, MissingFieldError)
- [x] 1.3.5 Implement graph/workflow error types
- [x] 1.3.6 Implement tool error types
- [x] 1.3.7 Implement state error types
- [x] 1.3.8 Implement Show[AdkError] instance
- [x] 1.3.9 Write unit tests for error formatting and wrapping

### 1.4 Core Type Aliases
- [x] 1.4.1 Create AdkIO, AdkResult, AdkIOResult type aliases
- [x] 1.4.2 Re-export structured-llm core types
- [x] 1.4.3 Add LLM4S type aliases
- [x] 1.4.4 Verify compile-time compatibility

### 1.5 NodeKey Opaque Type
- [x] 1.5.1 Create NodeKey opaque type as String
- [x] 1.5.2 Define START and END reserved keys
- [x] 1.5.3 Implement validation (non-empty, no reserved names)
- [x] 1.5.4 Add apply/unsafeApply factory methods
- [x] 1.5.5 Add extension methods (value, isStart, isEnd, isReserved)
- [x] 1.5.6 Implement Eq, Show, Order instances
- [x] 1.5.7 Write unit tests for validation and typeclass instances

### 1.6 FieldPath Opaque Type
- [x] 1.6.1 Create FieldPath opaque type as Vector[String]
- [x] 1.6.2 Implement path parsing from dotted string
- [x] 1.6.3 Add Root constant
- [x] 1.6.4 Implement fromSegments factory
- [x] 1.6.5 Add extension methods (segments, isEmpty, nonEmpty, head, tail)
- [x] 1.6.6 Implement path concatenation (:+ and ++)
- [x] 1.6.7 Implement render method
- [x] 1.6.8 Implement Show instance
- [x] 1.6.9 Write unit tests for parsing, concatenation, and edge cases

### 1.7 RunInfo Case Class
- [x] 1.7.1 Create RunInfo case class with nodeKey, componentType, nodeName, startTime, parentPath
- [x] 1.7.2 Add fullPath method
- [x] 1.7.3 Add factory methods (forNode overloads)
- [x] 1.7.4 Implement Show instance
- [x] 1.7.5 Write unit tests for creation, formatting, and path handling

### 1.8 Integration Testing
- [x] 1.8.1 Test ADK4S types work with actual LLM4S client
- [x] 1.8.2 Test structured-llm integration with new converters
- [x] 1.8.3 Verify >90% code coverage

## 2. Validation
- [x] 2.1 Run `openspec validate add-core-types --strict`
- [x] 2.2 Run sbt compile
- [x] 2.3 Run sbt test
- [ ] 2.4 Run sbt scalafmtCheck
- [ ] 2.5 Run sbt scalafixAll
