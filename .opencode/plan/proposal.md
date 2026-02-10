# Change Proposal: Add Core Types & Schema System

## Why

ADK4S needs foundational type definitions that establish compatibility between structured-llm and LLM4S, providing type-safe abstractions for the agent framework. These core types form the foundation layer for all future agent capabilities including multi-agent orchestration, tool calling, and workflow management.

## What Changes

- Add MessageConverter for bidirectional conversion between structured-llm Messages and LLM4S Messages
- Add ConversationConverter for bidirectional conversion between structured-llm Prompts and LLM4S Conversations
- Add AdkError hierarchy providing unified error types for LLM failures, parsing, validation, workflow, tool, and state errors
- Add NodeKey opaque type for type-safe node identifiers in graph construction
- Add FieldPath opaque type for type-safe field paths in workflow field mapping
- Add RunInfo case class for metadata about node execution for callbacks
- Add type aliases for common effectful operations and type re-exports

**BREAKING**: None (this is foundational, no existing code to break)

## Impact

- Affected specs: New capabilities (message-conversion, error-handling, core-types)
- Affected code: New module `adk4s-core` with packages `org.adk4s.core.types` and `org.adk4s.core.error`
- Dependencies: structured-llm (existing), LLM4S (local), Cats Effect 3.6.3

## Proposed Structure

```
openspec/changes/add-core-types-schema/
├── proposal.md              # This file
├── tasks.md                 # Implementation checklist
└── specs/
    ├── message-conversion/
    │   └── spec.md         # MessageConverter, ConversationConverter
    ├── error-handling/
    │   └── spec.md         # AdkError hierarchy
    └── core-types/
        └── spec.md         # NodeKey, FieldPath, RunInfo, type aliases
```

## Capabilities Breakdown

### 1. message-conversion
Covers Tasks 1-2 from implementation plan:
- MessageConverter: bidirectional conversion between ADK and LLM4S messages
- ConversationConverter: bidirectional conversion between Prompt and Conversation

### 2. error-handling
Covers Task 3 from implementation plan:
- AdkError hierarchy with Show instances
- Wraps LLM4S LLMError and structured-llm StructuredLLMError
- Validation, workflow, tool, and state error types

### 3. core-types
Covers Tasks 4-7 from implementation plan:
- Type aliases for effects and common types
- NodeKey opaque type for graph node identifiers
- FieldPath opaque type for field path navigation
- RunInfo case class for execution metadata

## Implementation Order

The tasks should be implemented in this order:
1. Core Type Aliases (Task 4) - foundational, no dependencies
2. Error Types (Task 3) - foundational, only depends on type aliases
3. NodeKey (Task 5) - independent opaque type
4. FieldPath (Task 6) - independent opaque type
5. RunInfo (Task 7) - depends on NodeKey
6. Message Converter (Task 1) - depends on type aliases and errors
7. Conversation Converter (Task 2) - depends on Message Converter

## Testing Strategy

Each capability will have comprehensive unit tests:
- MessageConverter: role conversion, round-trip, tool calls
- ConversationConverter: empty/single/multi-message, ordering
- AdkError: all error types, Show instance, wrapping
- NodeKey: validation, reserved keys, typeclass instances
- FieldPath: parsing, concatenation, edge cases
- RunInfo: creation, Show formatting, path handling

Integration tests will verify:
- ADK4S types work with actual LLM4S client
- Structured-llm integration with new converters

## Completion Criteria

- [ ] All converter methods implemented and tested
- [ ] All opaque types implemented with validation
- [ ] Error hierarchy complete with Show instances
- [ ] Unit tests passing with >90% coverage
- [ ] Integration with structured-llm verified
- [ ] Integration with LLM4S verified
- [ ] Documentation updated

## Questions for Reviewer

1. Should the error hierarchy include additional error types not specified in the implementation plan?
2. Should we include a design.md document given this is a foundational change affecting multiple modules?
3. Are the proposed capability names (message-conversion, error-handling, core-types) appropriate?
