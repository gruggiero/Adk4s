# Implementation Tasks

## 1. Core Types

- [x] 1.1 Create `ToolSchema[A]` opaque type with `SchemaData[A]` backing
- [x] 1.2 Implement `ToolSchema` companion with `instance` factory method
- [x] 1.3 Create `ToolSchemaError` sealed trait with decoding and validation cases
- [x] 1.4 Create `StructuredToolCallError` ADT with `UnknownTool`, `InvalidArguments`, `ExecutionFailed`, `ResultParsingFailed`

## 2. StructuredToolCall Trait

- [x] 2.1 Define `StructuredToolCall[F[_]]` trait with `execute`, `executeRaw`, `function`, `extractor` methods
- [x] 2.2 Implement `StructuredToolCall.fromRegistry[F](registry: ToolRegistry)` factory
- [x] 2.3 Implement argument decoding flow using `ToolSchema[I].decoder`
- [x] 2.4 Implement result decoding flow using `ToolSchema[O].decoder`
- [x] 2.5 Implement error mapping from llm4s errors to `StructuredToolCallError`

## 3. Optional: StructuredToolFunction

- [x] 3.1 Create `StructuredToolFunction[I, O]` case class with typed handler
- [x] 3.2 Implement conversion from `StructuredToolFunction` to llm4s `ToolFunction`

## 4. Testing

- [x] 4.1 Write argument decoding tests (valid JSON, missing fields, invalid types)
- [x] 4.2 Write result decoding tests (valid JSON, parsing failures)
- [x] 4.3 Write execution tests (success, handler errors, unknown tools)
- [x] 4.4 Write registry integration tests

## 5. Documentation

- [x] 5.1 Add Scaladoc to public APIs
- [x] 5.2 Add usage examples in test files
