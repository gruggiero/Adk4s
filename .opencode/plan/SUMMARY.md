# Plan Summary: Core Types & Schema System

## Overview

This plan contains OpenSpec specifications for implementing the Core Types & Schema System for ADK4S, based on the implementation plan in `docs/implementation-plan/01-core-types-schema.md`.

## Created Documents

### 1. proposal.md
Main change proposal document that describes:
- **Why**: Foundation types needed for ADK4S compatibility between structured-llm and LLM4S
- **What Changes**: 7 implementation tasks organized into 3 capabilities
- **Impact**: New module `adk4s-core` with two packages
- **Implementation Order**: Suggested sequence for the 7 tasks
- **Testing Strategy**: Unit and integration test approach
- **Questions for Reviewer**: 3 open questions for clarification

### 2. tasks.md
Detailed implementation checklist with 42 tasks organized in 3 sections:
- **Implementation**: 7 main tasks with 33 subtasks
- **Integration & Verification**: 6 verification tasks
- **Finalization**: 6 finalization tasks

### 3. spec-message-conversion.md
Specification for message conversion capability with 17 scenarios covering:
- MessageConverter: bidirectional conversion between ADK and LLM4S message types
- Extension methods: asLlm4s, asAdk for convenient conversion
- ConversationConverter: bidirectional conversion between Prompt and Conversation
- Round-trip preservation and message ordering guarantees

### 4. spec-error-handling.md
Specification for error handling capability with 18 scenarios covering:
- AdkError base type extending Throwable
- 9 specific error types:
  - LlmCallError (wraps LLM4S LLMError)
  - StructuredOutputError (wraps StructuredLLMError)
  - TypeMismatchError (validation failures)
  - MissingFieldError (required field missing)
  - NodeNotFoundError (graph operations)
  - EdgeValidationError (graph operations)
  - MaxStepsExceededError (workflow limits)
  - GraphCompiledError (immutable graphs)
  - ToolNotFoundError, ToolExecutionError (tool operations)
  - StateTypeMismatchError (workflow state)
- Show[AdkError] instance for consistent formatting
- Error wrapping preserving underlying causes

### 5. spec-core-types.md
Specification for core types capability with 37 scenarios covering:
- **NodeKey**: Opaque type for graph node identifiers
  - Validation (non-empty, no reserved keys)
  - Reserved constants (START, END)
  - Extension methods (value, isStart, isEnd, isReserved)
  - Typeclass instances (Eq, Show, Order)
- **FieldPath**: Opaque type for field path navigation
  - Parsing dotted strings
  - Path manipulation (head, tail, concatenation)
  - Root constant for empty path
  - Show instance
- **RunInfo**: Case class for execution metadata
  - Node key, component type, optional name, timing, parent path
  - fullPath calculation
  - Show instance with path formatting
- **Type Aliases**: 10 aliases for convenience
  - Effect aliases (AdkIO, AdkResult, AdkIOResult)
  - structured-llm re-exports (Message, Role, Prompt, Schema, PromptTemplate)
  - LLM4S aliases (LlmConversation, LlmCompletion, LlmMessage, ToolCall, FunctionCall)

## Total Coverage

- **3 Capabilities**: message-conversion, error-handling, core-types
- **7 Implementation Tasks**: Organized logically with dependencies
- **72 Scenarios**: Comprehensive test coverage for all requirements
- **42 Implementation Tasks**: Detailed checklist with subtasks

## Next Steps

1. **Review the proposal.md** to understand the overall change
2. **Review the three spec documents** to understand requirements
3. **Address the 3 questions** in the proposal:
   - Should we include additional error types?
   - Should we include a design.md document?
   - Are the capability names appropriate?
4. **Approve the plan** to proceed with implementation
5. **Create actual OpenSpec structure** in `openspec/changes/add-core-types-schema/`
6. **Validate** with `openspec validate add-core-types-schema --strict`

## Key Design Decisions

1. **Single Change Proposal**: All 7 tasks grouped together as foundational foundation layer
2. **Three Logical Capabilities**: Organized by domain rather than implementation task
3. **Comprehensive Scenarios**: Each requirement has at least one scenario, most have multiple
4. **Type Safety Focus**: Emphasis on opaque types (NodeKey, FieldPath) and type aliases
5. **Error Hierarchy**: Unified AdkError base wrapping external errors plus ADK4S-specific types
6. **Implementation Order**: Dependencies respected (type aliases → errors → NodeKey → FieldPath → RunInfo → MessageConverter → ConversationConverter)
