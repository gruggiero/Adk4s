# Structured ToolCall: Typed Tool Execution on Top of llm4s

## Overview

This design proposes a **type-safe, composable wrapper** for llm4s tool calling that mirrors the `StructuredLLM` abstraction. The goal is to provide a structured, typed interface over `ToolCall` with predictable error handling and minimal ceremony. The core abstraction is:

```
ToolCall => F[A]
```

Key objectives:

1. **Typed tool arguments**: Parse tool call arguments into typed input models.
2. **Typed tool results**: Decode tool execution results into typed outputs.
3. **Composable functions**: Provide reusable functions for tool execution pipelines.
4. **Clean error model**: One error ADT for argument parsing, execution, and output parsing.

This design wraps existing llm4s types without replacing them. It builds on:

- `ToolCall` in llm4s message model.
- `ToolRegistry` + `ToolFunction` in the tool API.

Reference docs:
- [llm4s Tool Calling API Design](https://github.com/llm4s/llm4s/blob/main/docs/tool-calling-api-design.md)
- [llm4s API Spec](https://github.com/llm4s/llm4s/blob/main/docs/llm4s-api-spec.md)

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

## Core Design Decisions

### 1. Schema Typeclass for Tool Arguments and Results

We define a `ToolSchema[A]` typeclass that provides:

- JSON schema definition (for validation or prompt/tool definition usage)
- A decoder from `ujson.Value` to `A`
- An encoder from `A` to `ujson.Value`

This mirrors `Schema[A]` from structured-llm but stays aligned with llm4s tool JSON schemas. It avoids `Any` and requires explicit types.

**Naming guidance:**
- Prefer `ToolSchema` when the structured tool module lives alongside `structured-llm` to avoid clashes with `Schema[A]`.
- Prefer `Schema` if this abstraction is the only public schema in the target module, but document the alias explicitly (e.g., `type ToolSchema[A] = Schema[A]`) to keep migration easy.
- Keep the companion and methods stable (`Schema.instance` or `ToolSchema.instance`) to reduce downstream churn.

```scala
opaque type ToolSchema[A] = ToolSchema.SchemaData[A]

object ToolSchema:
  final case class SchemaData[A](
    jsonSchema: ujson.Value,
    description: Option[String],
    decoder: ujson.Value => Either[ToolSchemaError, A],
    encoder: A => ujson.Value
  )
```

### 2. StructuredToolCall Abstraction

The main trait provides the same ergonomics as `StructuredLLM`, focused on tool execution:

```scala
trait StructuredToolCall[F[_]]:
  def execute[I: ToolSchema, O: ToolSchema](toolCall: ToolCall): F[O]
  def executeRaw[I: ToolSchema, O: ToolSchema](toolCall: ToolCall): F[O]
  def function[I: ToolSchema, O: ToolSchema](toolName: String): ToolCall => F[O]
  def extractor[I: ToolSchema, O: ToolSchema](toolName: String): ToolCall => F[O]
```

### 3. Error Model

Errors are consolidated into a single ADT, mapping tool API errors and parsing issues:

```scala
sealed trait StructuredToolCallError extends Throwable:
  def message: String
  override def getMessage: String = message

object StructuredToolCallError:
  final case class UnknownTool(name: String) extends StructuredToolCallError
  final case class InvalidArguments(errors: List[String]) extends StructuredToolCallError
  final case class ExecutionFailed(cause: Throwable) extends StructuredToolCallError
  final case class ResultParsingFailed(message: String, raw: ujson.Value)
    extends StructuredToolCallError
```

### 4. Functional, Immutable Execution

All APIs return `F[O]` and avoid mutable state. Parsing and execution are pure functions; effect boundaries exist only where tool handlers run.

## Module Structure

```
modules/core/src/main/scala/org/llm4s/toolapi/structured/
  StructuredToolCall.scala
  ToolSchema.scala
  ToolSchemaError.scala
  StructuredToolCallError.scala
```

## Detailed Flow

1. **Receive ToolCall**
   - `toolCall.name` identifies the tool.
   - `toolCall.arguments` contains JSON arguments.

2. **Decode Arguments**
   - Use `ToolSchema[I].decoder` to transform JSON → `I`.

3. **Execute Tool**
   - Wrap `ToolRegistry.execute` in `F`.
   - Convert `ToolCall` → `ToolCallRequest`.

4. **Decode Result**
   - Use `ToolSchema[O].decoder` on the tool result JSON.

5. **Return F[O]**

## Integration Options

### A. Registry Wrapper

```scala
object StructuredToolCall:
  def fromRegistry[F[_]: Async](registry: ToolRegistry): StructuredToolCall[F] = ???
```

- Uses existing tool registry execution logic.
- Minimal overhead, full compatibility with llm4s tool API.

### B. ToolFunction Wrapper

```scala
final case class StructuredToolFunction[I, O](
  name: String,
  description: String,
  schema: ToolSchema[I],
  handler: I => Either[StructuredToolCallError, O]
)
```

- Provides typed interface for building tool functions.
- Can compile down to existing `ToolFunction`.

## Example Usage

```scala
import cats.effect.IO
import org.llm4s.toolapi.*
import org.llm4s.toolapi.structured.*

final case class WeatherRequest(location: String, units: String)
final case class WeatherResult(location: String, temperature: Double, units: String)

given ToolSchema[WeatherRequest] = ToolSchema.instance[WeatherRequest](
  jsonSchema = Schema.`object`[WeatherRequest]("Weather request")
    .withProperty(Schema.property("location", Schema.string("City and country")))
    .withProperty(Schema.property("units", Schema.string("Units")))
    .toJsonSchema(strict = true),
  description = Option("Weather request arguments"),
  decoder = ToolSchemaDecoder.fromJson[WeatherRequest],
  encoder = ToolSchemaEncoder.toJson[WeatherRequest]
)

given ToolSchema[WeatherResult] = ToolSchema.instance[WeatherResult](
  jsonSchema = Schema.`object`[WeatherResult]("Weather result")
    .withProperty(Schema.property("location", Schema.string("City and country")))
    .withProperty(Schema.property("temperature", Schema.number("Temperature")))
    .withProperty(Schema.property("units", Schema.string("Units")))
    .toJsonSchema(strict = true),
  description = Option("Weather result")
  decoder = ToolSchemaDecoder.fromJson[WeatherResult],
  encoder = ToolSchemaEncoder.toJson[WeatherResult]
)

val registry: ToolRegistry = new ToolRegistry(Seq(weatherTool))
val structured: StructuredToolCall[IO] = StructuredToolCall.fromRegistry[IO](registry)

val toolCall: ToolCall = ToolCall(
  id = "call-1",
  name = "get_weather",
  arguments = ujson.Obj("location" -> "Rome", "units" -> "celsius")
)

val result: IO[WeatherResult] = structured.execute[WeatherRequest, WeatherResult](toolCall)
```

## Testing Strategy

1. **Argument decoding tests**
   - Valid JSON → model
   - Missing/invalid fields → `InvalidArguments`

2. **Execution tests**
   - Success result decodes correctly
   - Execution error maps to `ExecutionFailed`

3. **Registry integration tests**
   - `ToolRegistry.execute` wrapped successfully

## Future Enhancements

1. **Streaming tool output parsing**
2. **Schema generation from Smithy or Scala 3 derivation**
3. **Batch tool call execution with structured results**
4. **Better diagnostics for argument shape mismatches**

---

## Decision Summary

- **Use ToolSchema** as the structured equivalent of `Schema[A]` for tool inputs and outputs.
- **Wrap ToolRegistry** to keep compatibility and reduce migration cost.
- **Expose `ToolCall => F[A]`** for composition and reuse.
- **Unified error ADT** for consistent handling and improved diagnostics.
