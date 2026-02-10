# Structured ToolCall Design

## Context

This design provides a type-safe, composable wrapper for llm4s tool calling that mirrors the `StructuredLLM` abstraction. The core signature is `ToolCall => F[A]`.

### Stakeholders
- ADK4S developers building agents with tool calling
- llm4s tool API consumers wanting type safety

### Constraints
- Must wrap existing llm4s types without replacing them
- Must integrate with `ToolRegistry` and `ToolFunction` from llm4s
- Must follow Scala 3 idioms (opaque types, typeclasses)

## Goals / Non-Goals

### Goals
- Type-safe tool argument parsing via `ToolSchema[I]`
- Type-safe result decoding via `ToolSchema[O]`
- Unified error model for parsing, execution, and decoding failures
- Composable function API: `ToolCall => F[O]`

### Non-Goals
- Replacing llm4s tool API
- Streaming tool output parsing (future enhancement)
- Automatic schema derivation from Smithy (future enhancement)

## Decisions

### Decision 1: Use opaque type for ToolSchema
**What**: `opaque type ToolSchema[A] = ToolSchema.SchemaData[A]`

**Why**: Matches the project's existing pattern for `Schema[A]` in structured-llm. Provides zero-overhead type safety while keeping the implementation private.

**Alternatives**:
- Trait-based typeclass: More verbose, requires evidence parameter syntax
- Case class wrapper: Runtime overhead, less idiomatic for typeclasses

### Decision 2: Wrap ToolRegistry rather than replace it
**What**: `StructuredToolCall.fromRegistry[F](registry: ToolRegistry)` factory method

**Why**:
- Minimizes migration cost for existing llm4s users
- Full compatibility with llm4s tool API
- Allows gradual adoption

**Alternatives**:
- New tool registry: Would duplicate functionality and fragment ecosystem
- Direct ToolFunction wrapping: Less flexible, harder to compose

### Decision 3: Single unified error ADT
**What**: `StructuredToolCallError` with `UnknownTool`, `InvalidArguments`, `ExecutionFailed`, `ResultParsingFailed` cases

**Why**: Consistent error handling and diagnostics. Each error case captures relevant context (raw JSON, tool name, underlying cause).

**Alternatives**:
- Separate error types per stage: More granular but harder to handle uniformly
- Exception-based: Loses type safety and effect composability

### Decision 4: Naming ToolSchema vs Schema
**What**: Use `ToolSchema` as the name when this module coexists with `structured-llm`

**Why**: Avoids naming collision with `org.adk4s.structured.core.Schema`. If this becomes the only schema abstraction in a target module, can alias as `type Schema[A] = ToolSchema[A]`.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         User Code                               │
│  val result: IO[WeatherResult] = structured.execute[Req, Res](tc)│
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                 StructuredToolCall[F[_]]                         │
│  - Parse ToolCall.arguments with ToolSchema[I]                   │
│  - Execute tool via ToolRegistry                                 │
│  - Decode output with ToolSchema[O]                              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        llm4s Tool API                            │
│  - ToolRegistry.execute(...)                                     │
│  - ToolFunction handler logic                                    │
└─────────────────────────────────────────────────────────────────┘
```

## Execution Flow

1. **Receive ToolCall**: `toolCall.name` identifies the tool, `toolCall.arguments` contains JSON
2. **Decode Arguments**: Use `ToolSchema[I].decoder` to transform JSON to typed input `I`
3. **Execute Tool**: Wrap `ToolRegistry.execute` in effect `F`, convert `ToolCall` to `ToolCallRequest`
4. **Decode Result**: Use `ToolSchema[O].decoder` on the tool result JSON
5. **Return F[O]**: Typed result or error raised in `F`

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Schema drift between argument types and actual JSON | Provide clear error messages with raw JSON for debugging |
| Performance overhead from extra parsing layer | Opaque types have zero runtime cost; parsing is unavoidable for type safety |
| llm4s API changes | Depend on stable llm4s Tool API; adapter layer isolates changes |

## Open Questions

- Should `ToolSchema` support Smithy derivation like `Schema[A]` in structured-llm?
- Should we add middleware support for cross-cutting concerns (logging, timing)?
- Should batch tool call execution with typed results be included in v1?
