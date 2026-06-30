# Spec: Semantic Streaming with Completion State

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `StructuredLLM[F[_]]` | trait | `org.adk4s.structured.core` |
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `SchemaData[A]` | case class | `org.adk4s.structured.core` |
| `Prompt` | case class | `org.adk4s.structured.core` |
| `StreamingAccumulator` (llm4s) | class | `org.llm4s.llmconnect.streaming` |
| `AccumulatorSnapshot` (llm4s) | case class | `org.llm4s.llmconnect.streaming` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `CompletionState` | enum | Pending / Incomplete / Complete — tracks per-value completeness |
| `StreamingBehavior` | case class | Per-field config: done, needed, withState |
| `StreamState[A]` | case class | Wrapper for @stream.with_state — value: Option[A], state: CompletionState |
| `SemanticStreamingAccumulator` | class | Extends StreamingAccumulator with partial JsonishValue re-parsing |
| `streamPartial` | method | New StructuredLLM method: Stream[F, StreamState[A]] |

## ADDED Requirements

### Requirement: CompletionState tracking

The system SHALL track `CompletionState` (Pending / Incomplete / Complete) on every parsed value node during streaming, indicating whether a value is not yet started, partially received, or fully parsed.

**Given** a streaming LLM response being accumulated token by token
**When** the accumulator processes each chunk
**Then** each field in the partial parse result has a `CompletionState` reflecting its current state

**Rationale**: Completion state enables UIs to show which fields are still being generated vs fully received.

#### Scenario: Field not yet started

**Given** a stream that has sent `{"name": "Jo` (truncated)
**When** the accumulator parses the partial content
**Then** `name` has state `Incomplete` and any unstarted fields have state `Pending`

#### Scenario: Field fully received

**Given** a stream that has sent `{"name": "John", "age": 30}`
**When** the accumulator parses the complete content
**Then** all fields have state `Complete`

### Requirement: StreamingBehavior per-field configuration

The system SHALL support `StreamingBehavior` configuration per field with `done` (only show when complete), `needed` (container won't stream until non-null), and `withState` (wrap in StreamState) flags.

**Given** a `Schema[A]` with `StreamingBehavior(done=true)` on a field
**When** the field is partially streamed (e.g., a number `12` of `129.99`)
**Then** the field is replaced with `null` until complete

**Rationale**: BAML's `@stream.done` prevents showing partially-streamed numbers that would flicker in UIs.

#### Scenario: @stream.done hides incomplete number

**Given** a schema with `StreamingBehavior(done=true)` on `total: Float` and a partial stream `{"total": 12`
**When** the accumulator produces a partial result
**Then** `total` is `null` (not `12`)

#### Scenario: @stream.not_null blocks container

**Given** a schema with `StreamingBehavior(needed=true)` on `name: String` and a partial stream `{"age": 30}`
**When** the accumulator produces a partial result
**Then** the entire object is `null` because `name` is not yet present

### Requirement: Partial parsing during stream

The system SHALL re-parse accumulated text into partial `JsonishValue` on each chunk (throttled to avoid excessive CPU), emitting `StreamState[A]` values as the parse progresses.

**Given** a streaming response being accumulated
**When** sufficient new content has arrived (throttle: minimum 50ms between parses)
**Then** a new `StreamState[A]` is emitted with the current partial value and `CompletionState.Incomplete`

**Rationale**: Real-time UI updates require partial structured data, not just raw text tokens.

#### Scenario: Partial object emitted mid-stream

**Given** a stream producing `{"name": "John", "age":` (incomplete)
**When** the throttle period elapses
**Then** a `StreamState(Some(partialA), Incomplete)` is emitted with `name="John"` and `age=null`

#### Scenario: Final complete value emitted

**Given** a stream that has finished
**When** the final chunk is processed
**Then** a `StreamState(Some(completeA), Complete)` is emitted

### Requirement: streamPartial API method

The system SHALL provide `streamPartial[A: Schema](prompt: Prompt): Stream[F, StreamState[A]]` on `StructuredLLM[F]` that emits partial structured values during streaming.

**Given** a `StructuredLLM[F]` and a `Prompt`
**When** `streamPartial(prompt)` is called
**Then** the result is an `fs2.Stream[F, StreamState[A]]` emitting partial values and ending with a complete value

#### Scenario: Stream emits multiple partials then final

**Given** a mock LLM streaming a JSON object token by token
**When** `streamPartial` is consumed
**Then** the stream emits at least 2 `StreamState` values with `Incomplete` state, followed by 1 with `Complete` state

## Properties (Ring 3)

### Property: Final stream value equals non-streaming parse

**Invariant**: For any LLM response, the `Complete` value from `streamPartial` equals the value from `complete`.

**Generator strategy**: `Gen` of valid JSON strings via smithy4s schema-aware generation, classify by field-count.

```
forAll { (json: String) =>
  val streamed = streamPartial(mockPrompt(json)).compile.toList.last
  val completed = complete(mockPrompt(json))
  streamed.state == Complete && streamed.value == completed
}
```

### Property: @stream.done fields are null until complete

**Invariant**: For any field with `StreamingBehavior(done=true)`, the field value is `None` in all partial emissions where the field's `CompletionState` is not `Complete`.

**Generator strategy**: `Gen` of partial JSON strings (truncated at various points), classify by truncation-point.

```
forAll { (partialJson: String) =>
  val result = parsePartial(partialJson, schemaWithDoneField)
  result.fieldWithDone.exists(_.state != Complete) ==> result.fieldWithDone.value.isEmpty
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| CompletionState tracked per field | Requirement 1 | Scenario test | SemanticStreamingSpec |
| @stream.done hides incomplete | Requirement 2 | Hedgehog property | SemanticStreamingSpec |
| @stream.not_null blocks container | Requirement 2 | Scenario test | SemanticStreamingSpec |
| Throttled partial parsing | Requirement 3 | Scenario test | SemanticStreamingSpec |
| streamPartial emits StreamState stream | Requirement 4 | Scenario test | SemanticStreamingSpec |
| Final value equals complete | Property 1 | Hedgehog property | SemanticStreamingSpec |
