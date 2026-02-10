# Change: Add Core Types & Schema System

## Why
ADK4S needs a foundational type system to bridge structured-llm and LLM4S libraries, providing type-safe conversions, unified error handling, and core domain types for agent development. Without this foundation, agent orchestration cannot be built.

## What Changes
- Add MessageConverter for bidirectional conversion between structured-llm and LLM4S message types
- Add ConversationConverter for Prompt/Conversation conversion
- Add unified AdkError hierarchy wrapping LLM4S and structured-llm errors
- Add core type aliases for common effectful operations
- Add NodeKey opaque type for graph node identification
- Add FieldPath opaque type for field path navigation
- Add RunInfo case class for node execution metadata

## Impact
- New capabilities: core-types
- Affected code: adk4s-core module (new module)
- Dependencies: structured-llm, llm4s, Cats Effect, Smithy4s
- Breaking changes: None (new module)
