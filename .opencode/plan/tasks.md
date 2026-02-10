# Tasks: Add Core Types & Schema System

## 1. Implementation

### Task 1: Message Converter
- [ ] 1.1 Create MessageConverter.scala with toLlm4s and fromLlm4s methods
- [ ] 1.2 Implement bidirectional conversion for all role types (System, User, Assistant, Tool)
- [ ] 1.3 Handle tool calls in AssistantMessage conversions
- [ ] 1.4 Handle tool responses (ToolMessage with toolCallId)
- [ ] 1.5 Add extension methods for convenient conversion (asLlm4s, asAdk)
- [ ] 1.6 Write unit tests for all role type conversions
- [ ] 1.7 Write round-trip conversion tests
- [ ] 1.8 Write tests for tool call handling

### Task 2: Conversation Converter
- [ ] 2.1 Create ConversationConverter.scala with toConversation and fromConversation methods
- [ ] 2.2 Implement bidirectional conversion between Prompt and Conversation
- [ ] 2.3 Ensure message ordering is preserved in conversions
- [ ] 2.4 Add extension methods for convenient conversion (asConversation, asPrompt)
- [ ] 2.5 Write unit tests for empty prompt/conversation
- [ ] 2.6 Write unit tests for single message conversion
- [ ] 2.7 Write unit tests for multi-message conversion
- [ ] 2.8 Write round-trip preservation tests

### Task 3: ADK4S Error Types
- [ ] 3.1 Create AdkError sealed trait hierarchy
- [ ] 3.2 Implement LlmCallError wrapping LLM4S LLMError
- [ ] 3.3 Implement StructuredOutputError wrapping StructuredLLMError
- [ ] 3.4 Implement validation errors (TypeMismatchError, MissingFieldError)
- [ ] 3.5 Implement graph/workflow errors (NodeNotFoundError, EdgeValidationError, MaxStepsExceededError, GraphCompiledError)
- [ ] 3.6 Implement tool errors (ToolNotFoundError, ToolExecutionError)
- [ ] 3.7 Implement state errors (StateTypeMismatchError)
- [ ] 3.8 Implement Show[AdkError] instance for error formatting
- [ ] 3.9 Write unit tests for error message formatting
- [ ] 3.10 Write unit tests for error wrapping
- [ ] 3.11 Write unit tests for Show instance

### Task 4: Core Type Aliases
- [ ] 4.1 Create package.scala in org.adk4s.core.types
- [ ] 4.2 Add effect aliases (AdkIO, AdkResult, AdkIOResult)
- [ ] 4.3 Add re-export aliases for structured-llm types (Message, Role, Prompt, Schema, PromptTemplate)
- [ ] 4.4 Add LLM4S type aliases (LlmConversation, LlmCompletion, LlmMessage, ToolCall, FunctionCall)
- [ ] 4.5 Write import tests to verify aliases work
- [ ] 4.6 Verify compilation succeeds

### Task 5: NodeKey Opaque Type
- [ ] 5.1 Create NodeKey.scala with opaque type definition
- [ ] 5.2 Define reserved keys (START, END)
- [ ] 5.3 Implement apply method with validation (non-empty, no reserved names)
- [ ] 5.4 Implement unsafeApply method for convenience
- [ ] 5.5 Add extension methods (value, isStart, isEnd, isReserved)
- [ ] 5.6 Implement Eq, Show, Order instances
- [ ] 5.7 Write unit tests for validation rules
- [ ] 5.8 Write unit tests for reserved keys
- [ ] 5.9 Write unit tests for typeclass instances

### Task 6: FieldPath Opaque Type
- [ ] 6.1 Create FieldPath.scala with opaque type definition
- [ ] 6.2 Define Root constant for empty path
- [ ] 6.3 Implement apply method for parsing dotted strings
- [ ] 6.4 Implement fromSegments for creating paths from segments
- [ ] 6.5 Add extension methods (segments, isEmpty, nonEmpty, head, tail, :+, ++, render)
- [ ] 6.6 Implement Show instance
- [ ] 6.7 Write unit tests for path parsing
- [ ] 6.8 Write unit tests for concatenation
- [ ] 6.9 Write unit tests for edge cases (empty path, single segment)

### Task 7: RunInfo Case Class
- [ ] 7.1 Create RunInfo.scala case class definition
- [ ] 7.2 Implement fullPath method combining parentPath and nodeKey
- [ ] 7.3 Create companion object with factory methods (forNode)
- [ ] 7.4 Implement Show instance for RunInfo
- [ ] 7.5 Write unit tests for RunInfo creation
- [ ] 7.6 Write unit tests for Show formatting
- [ ] 7.7 Write unit tests for path handling

## 2. Integration & Verification

- [ ] 2.1 Verify integration with actual LLM4S client
- [ ] 2.2 Verify structured-llm integration with new converters
- [ ] 2.3 Run all unit tests and ensure >90% code coverage
- [ ] 2.4 Run integration tests
- [ ] 2.5 Run linting and type checking (scalafmt, scalafix)
- [ ] 2.6 Update documentation

## 3. Finalization

- [ ] 3.1 Review all code for adherence to project conventions
- [ ] 3.2 Ensure all public APIs have Scaladoc comments
- [ ] 3.3 Verify package organization matches file structure
- [ ] 3.4 Confirm no breaking changes introduced
- [ ] 3.5 Run openspec validate with --strict flag
- [ ] 3.6 Request approval before implementation
