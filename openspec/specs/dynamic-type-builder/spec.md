# Spec: Dynamic Type Builder

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `SchemaData[A]` | case class | `org.adk4s.structured.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `SchemaBuilder` | class | Runtime schema construction — string, int, float, bool, list, union, map, addClass, addEnum |
| `DynamicRecord` | type | Result type for dynamic class schemas — Map-backed record |
| `DynamicEnum` | type | Result type for dynamic enum schemas |

## ADDED Requirements

### Requirement: Runtime schema construction

The system SHALL provide a `SchemaBuilder` that constructs `Schema` instances at runtime for dynamic types not known at compile time.

**Given** a `SchemaBuilder` instance
**When** `builder.addClass("User", "name" -> builder.string(), "age" -> builder.int())` is called
**Then** the result is a `Schema[DynamicRecord]` that can parse JSON objects with `name` and `age` fields

**Rationale**: Runtime-configurable agents need schemas that can be modified without recompilation.

#### Scenario: Build class schema

**Given** a `SchemaBuilder`
**When** `builder.addClass("User", "name" -> builder.string(), "active" -> builder.bool())`
**Then** the resulting schema parses `{"name": "John", "active": true}` into a `DynamicRecord`

#### Scenario: Build enum schema

**Given** a `SchemaBuilder`
**When** `builder.addEnum("Status", "Active", "Inactive", "Pending")`
**Then** the resulting schema parses `"Active"` into a `DynamicEnum`

#### Scenario: Build list schema

**Given** a `SchemaBuilder` with a `builder.string()` element schema
**When** `builder.list(elementSchema)`
**Then** the resulting schema parses `["a", "b", "c"]` into `Vector[String]`

### Requirement: DynamicValue.parse uses smithy4s.json.Json directly

The `DynamicTypeBuilder.DynamicValue.parse` method SHALL parse a raw JSON string into a `smithy4s.Document` (the underlying type of `JsonValue`) using `smithy4s.json.Json.read[Document]` directly, NOT via a ujson-then-hand-walk conversion. The method's signature (`String => DynamicValue` or equivalent) and observable behavior SHALL be unchanged — for every input string, the parsed result SHALL equal the pre-change result.

**Given** a JSON string `{"name": "John", "age": 42}`
**When** `DynamicValue.parse` is called
**Then** the result is a `DynamicValue` backed by `DObject(Map("name" -> DString("John"), "age" -> DNumber(42)))`, equal to the pre-change result for the same input

**Rationale**: The pre-change code parses via `ujson.read`, then hand-walks the `ujson.Value` tree to build a `smithy4s.Document` via `ujsonValueToDocument`. This is an unnecessary double-parse: `smithy4s.json.Json.read[Document]` parses directly into `Document` using the jsoniter-scala backend (already a declared dependency). Removing the ujson step makes `structured-llm` entirely `ujson`-free.

#### Scenario: Object parse

**Given** the JSON string `{"name": "John", "age": 42}`
**When** `DynamicValue.parse` is called
**Then** the result is a `DynamicValue` with `DObject(Map("name" -> DString("John"), "age" -> DNumber(42)))`

#### Scenario: Array parse

**Given** the JSON string `[1, 2, 3]`
**When** `DynamicValue.parse` is called
**Then** the result is a `DynamicValue` with `DArray(Vector(DNumber(1), DNumber(2), DNumber(3)))`

#### Scenario: Long precision preserved

**Given** the JSON string `{"id": 9007199254740993}` (2^53 + 1)
**When** `DynamicValue.parse` is called
**Then** the result has `DNumber(9007199254740993L)` — exact Long, no `Double` truncation (contrast: the pre-change ujson path would truncate via `ujson.Num(Double)`)

#### Scenario: Null parse

**Given** the JSON string `null`
**When** `DynamicValue.parse` is called
**Then** the result is a `DynamicValue` with `DNull`

#### Scenario: Invalid JSON raises an error

**Given** the JSON string `{not valid json`
**When** `DynamicValue.parse` is called
**Then** a parse error is raised (the error type matches the pre-change behavior — `smithy4s.json.Json.read` raises on invalid JSON)

## Properties (Ring 3)

### Property: Dynamic schema round-trips JSON

**Invariant**: For any schema built via `SchemaBuilder`, encoding a value to JSON and decoding it back produces the same value.

**Generator strategy**: `Gen` of SchemaBuilder configurations (random class/enum/list/union compositions), `Gen` of matching JSON values, classify by type-kind.

```
forAll { (config: SchemaBuilderConfig) =>
  val schema = buildSchema(config)
  val value = generateValueFor(config)
  val json = encode(value, schema)
  schema.decode(json) == Right(value)
}
```

### Property: Dynamic class schema rejects missing required fields

**Invariant**: For a class schema with required fields, decoding JSON missing a required field produces an error.

**Generator strategy**: `Gen` of class configs with required fields, `Gen` of JSON missing one field, classify by missing-field.

```
forAll { (config: ClassConfig, missingField: String) =>
  val schema = buildClassSchema(config)
  val json = buildJsonMissingField(config, missingField)
  schema.decode(json).isLeft
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Runtime schema construction | Requirement 1 | Scenario test | DynamicTypeBuilderSpec |
| Dynamic schema round-trips | Property 1 | Hedgehog property | DynamicTypeBuilderSpec |
| Missing required field rejected | Property 2 | Hedgehog property | DynamicTypeBuilderSpec |
