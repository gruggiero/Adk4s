# ADR-001: ChatModel Foundation Layer

## Status

**Accepted**

## Date

2026-01-12

## Context

ADK4S needs to define a `ChatModel` abstraction as part of its component system (Feature 03: Component Abstractions). The project has two existing LLM integration layers available:

1. **LLMClient** (from llm4s) - Low-level client providing raw access to LLM providers with `Conversation => Completion` signature, streaming support, and tool calling
2. **structured-llm** - Higher-level wrapper around LLMClient providing type-safe structured outputs with `Prompt => F[A]` signature, Smithy schema injection, and Schema-Aligned Parser (SAP) for error recovery

The decision concerns which layer should serve as the foundation for `ChatModel[F]`.

### Requirements

ADK4S agents need:
- Raw text generation for reasoning/thought extraction
- Tool calling with access to tool call metadata (IDs, function names, arguments)
- Real-time streaming for UX and early stopping
- Type-safe structured outputs for final results
- Support for ReAct patterns with custom parsing

### Reference Architecture

Eino (CloudWego) separates `ChatModel` as a raw building block from structured output handling, composing them at higher levels.

## Decision

**Base `ChatModel[F]` directly on LLMClient, not on structured-llm.**

Provide structured output capability through composition via a separate `StructuredChatModel[F]` trait.

### Architecture

```
┌─────────────────────────────────────────────────┐
│           StructuredChatModel[F]                │ ← Typed outputs
│  (composes ChatModel + structured-llm)          │
└─────────────────────────────────────────────────┘
                      │
┌─────────────────────────────────────────────────┐
│              ChatModel[F]                       │ ← Raw LLM access
│  (wraps LLMClient with fs2 streaming)           │
└─────────────────────────────────────────────────┘
                      │
┌─────────────────────────────────────────────────┐
│              LLMClient                          │ ← llm4s
└─────────────────────────────────────────────────┘
                      │
┌─────────────────────────────────────────────────┐
│          LLM Providers                          │ ← OpenAI, Anthropic, etc.
└─────────────────────────────────────────────────┘
```

### API Design

```scala
// Base trait - raw LLM access
trait ChatModel[F[_]]:
  def generate(conversation: Conversation, config: ChatModelConfig): F[Completion]
  def stream(conversation: Conversation, config: ChatModelConfig): Stream[F, StreamedChunk]
  def streamContent(conversation: Conversation, config: ChatModelConfig): Stream[F, String]

object ChatModel:
  def fromLlm4s(client: LLMClient): ChatModel[IO]

// Extended trait - adds type-safe structured outputs
trait StructuredChatModel[F[_]] extends ChatModel[F]:
  def generateTyped[A: Schema](conversation: Conversation, config: ChatModelConfig): F[A]

object StructuredChatModel:
  def fromChatModel(chatModel: ChatModel[IO], structured: StructuredLLM[IO]): StructuredChatModel[IO]
```

## Alternatives Considered

### Alternative A: Base ChatModel on structured-llm

```scala
// ChatModel would wrap StructuredLLM instead of LLMClient
trait ChatModel[F[_]]:
  def generate[A: Schema](prompt: Prompt): F[A]
```

**Rejected because:**

1. **Forced schema overhead** - `StructuredLLM.complete[A](prompt)` requires `Schema[A]`. No natural way to handle free-form chat without defining a wrapper type like `case class RawResponse(content: String)`

2. **Tool calling incompatibility** - structured-llm's signature is `Prompt => F[A]` where `A` is a user-defined domain type. Tool calls require access to `Completion.toolCalls` (tool call IDs, function names, raw arguments), which is not exposed in this abstraction

3. **Streaming impedance** - structured-llm collects the full response before parsing with SAP. Agents need real-time streaming for:
   - User experience (showing generation progress)
   - Early stopping (detecting when to interrupt)
   - Token-by-token processing

4. **Conversation vs Prompt mismatch** - structured-llm uses immutable `Prompt`, while agent loops work with `Conversation` that evolves over multiple turns with tool results appended

5. **Architectural conflict** - Forces structured output at the foundation when many agent operations don't need it

### Alternative B: Parallel hierarchies (no inheritance)

```scala
trait ChatModel[F[_]]        // Raw LLM
trait StructuredModel[F[_]]  // Typed outputs, no relationship to ChatModel
```

**Rejected because:**

- Loses the benefit of `StructuredChatModel` being usable anywhere `ChatModel` is expected
- Forces users to choose one or the other rather than getting both capabilities

## Consequences

### Positive

1. **Flexibility** - Users can choose raw or structured access based on their needs
2. **Tool calling works naturally** - `Completion` includes full tool call metadata
3. **Real-time streaming** - Direct access to `StreamedChunk` for UX
4. **Eino alignment** - Matches reference architecture's separation of concerns
5. **ReAct compatibility** - Custom parsing of Thought/Action/Observation sections works without fighting the abstraction
6. **Composition over inheritance** - StructuredChatModel adds capability without forcing it

### Negative

1. **Two APIs to learn** - Users need to understand both ChatModel and StructuredChatModel
2. **Manual composition** - Users must explicitly create StructuredChatModel when needed
3. **Potential code duplication** - Some JSON extraction logic may be duplicated if not carefully factored

### Neutral

1. **structured-llm remains valuable** - Used internally by StructuredChatModel and directly for non-agent use cases
2. **Migration path exists** - If structured-first proves better, StructuredChatModel can become the primary API

## Agent Use Case Analysis

| Agent Phase | Required Access | Supported By |
|-------------|-----------------|--------------|
| Tool selection | Raw `Completion` with `toolCalls` | ChatModel |
| Tool argument extraction | JSON string from tool call | ChatModel |
| Tool argument parsing | Type-safe `Tool.run[A](args)` | Tool abstraction |
| Reasoning extraction | Custom parsing (Thought/Action) | ChatModel + custom parser |
| Streaming output | Real-time `StreamedChunk` | ChatModel |
| Final structured result | `Schema[A]` validated output | StructuredChatModel |

## References

- [Feature 03: Component Abstractions](/docs/implementation-plan/03-component-abstractions.md)
- [Eino Architecture Analysis](/docs/eino-analysis/04-architectural-patterns.md)
- [structured-llm Implementation](/structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala)
