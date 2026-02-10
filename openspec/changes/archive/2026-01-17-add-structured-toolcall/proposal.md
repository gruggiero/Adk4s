# Change: Add Structured ToolCall Abstraction

## Why

The current tool calling workflow using llm4s requires manual JSON argument parsing and result decoding, leading to boilerplate code and inconsistent error handling. Developers need a type-safe, composable wrapper that mirrors the `StructuredLLM` abstraction, providing the signature `ToolCall => F[A]` with predictable error semantics.

## What Changes

- Add `ToolSchema[A]` typeclass for defining tool argument and result schemas with JSON encoding/decoding
- Add `StructuredToolCall[F]` trait providing typed tool execution with `execute[I, O](toolCall): F[O]`
- Add `StructuredToolCallError` ADT consolidating argument parsing, execution, and output parsing errors
- Add `ToolSchemaError` for schema-level validation and decoding failures
- Add factory methods to create `StructuredToolCall` from existing `ToolRegistry`
- Add optional `StructuredToolFunction[I, O]` wrapper for building typed tool functions

## Impact

- Affected specs: Creates new `structured-toolcall` capability
- Affected code:
  - `adk4s-core/src/main/scala/org/adk4s/core/tools/` - new module location
  - Integrates with llm4s `ToolRegistry`, `ToolCall`, `ToolFunction`
  - Builds on existing Schema-Aligned Parser (SAP) patterns from `structured-llm`
