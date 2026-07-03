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
