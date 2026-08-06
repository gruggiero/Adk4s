# Spec: Dynamic Type Builder (Delta)

<!-- DELTA spec for the migrate-json-codec change. MODIFIES the
     `DynamicTypeBuilder.DynamicValue.parse` internal implementation from
     ujson-then-hand-walk conversion to a direct `smithy4s.json.Json.read[Document]`
     parse. The `DynamicValue` signature and behavior are UNCHANGED — this is
     a pure internal refactor (Minimal typed contract per the proposal). -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| (no behavioral concept file exists for dynamic-type-builder) | — | — |

This spec touches no behavioral concept in the registry — `DynamicTypeBuilder` is a utility, not a behavioral unit. No concept file is created or modified.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `SchemaData[A]` | case class | `org.adk4s.structured.core` |
| `smithy4s.Document` | sealed trait (ADT) | `smithy4s` |
| `smithy4s.json.Json` | object (`read[A]`/`writeBlob`) | `smithy4s.json` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none) | — | This spec introduces no new concepts — it is a pure internal implementation swap |

## MODIFIED Requirements

### Requirement: DynamicValue.parse uses smithy4s.json.Json directly

The `DynamicTypeBuilder.DynamicValue.parse` method SHALL parse a raw JSON string into a `smithy4s.Document` (the underlying type of `JsonValue`) using `smithy4s.json.Json.read[Document]` directly, NOT via a ujson-then-hand-walk conversion. The method's signature (`String => DynamicValue` or equivalent) and observable behavior SHALL be unchanged — for every input string, the parsed result SHALL equal the pre-change result.

**Given** a JSON string `{"name": "John", "age": 42}`
**When** `DynamicValue.parse` is called
**Then** the result is a `DynamicValue` backed by `DObject(Map("name" -> DString("John"), "age" -> DNumber(42)))`, equal to the pre-change result for the same input

**Rationale**: The pre-change code parses via `ujson.read`, then hand-walks the `ujson.Value` tree to build a `smithy4s.Document` via `ujsonValueToDocument`. This is an unnecessary double-parse: `smithy4s.json.Json.read[Document]` parses directly into `Document` using the jsoniter-scala backend (already a declared dependency). Removing the ujson step makes `structured-llm` entirely `ujson`-free (its only other reference is a docstring), which cleans up `adk4s-eval`/`adk4s-optimize`'s Ring 2 purity story once the other specs in this change land.

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

### Property: DynamicValue.parse output equals pre-change output

**Invariant**: For every valid JSON string, the post-change `DynamicValue.parse` result equals the pre-change result (the underlying `Document` is the same).

**Generator strategy**: `genJsonString` (constructive — generates JSON strings via `genJsonValue` then `smithy4s.json.Json.writeBlob`; covers objects, arrays, numbers (Long and Double), strings, booleans, null; classify by top-level JSON type). The pre-change fixture is captured BEFORE any code change by running the current ujson-based `parse` on the same strings.

```
forAll { (jsonString: String) =>
  val newResult = DynamicValue.parse(jsonString)
  val oldResult = preChangeParse(jsonString)
  newResult == oldResult
}
```

### Property: Long precision preserved in parse

**Invariant**: For every JSON string containing an integer literal with magnitude > 2^53, `DynamicValue.parse` preserves the exact `Long` value (no `Double` truncation).

**Generator strategy**: `genLargeLongJson` (constructive — `genLargeLong` from the json-value-model spec, mapped to a JSON string `n.toString`; classify by magnitude bucket).

```
forAll { (n: Long) =>
  val jsonString = n.toString
  val parsed = DynamicValue.parse(jsonString)
  parsed.underlying == smithy4s.Document.DNumber(n)
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| DynamicValue.parse uses smithy4s.json.Json | Requirement: DynamicValue.parse uses smithy4s.json.Json directly | adversarial review (audit for ujson import removal) | adversarial review |
| Parse output equals pre-change | Requirement: DynamicValue.parse uses smithy4s.json.Json directly + Property: DynamicValue.parse output equals pre-change output | Hedgehog property (fixture-based) | DynamicTypeBuilderSpec |
| Long precision preserved | Requirement: DynamicValue.parse uses smithy4s.json.Json directly + Scenario: Long precision preserved + Property: Long precision preserved in parse | Hedgehog property | DynamicTypeBuilderSpec |
| Null parse produces DNull | Requirement: DynamicValue.parse uses smithy4s.json.Json directly + Scenario: Null parse | scenario test | DynamicTypeBuilderSpec |
| Invalid JSON raises error | Requirement: DynamicValue.parse uses smithy4s.json.Json directly + Scenario: Invalid JSON raises an error | scenario test | DynamicTypeBuilderSpec |
| structured-llm is ujson-free after this change | Requirement: DynamicValue.parse uses smithy4s.json.Json directly | adversarial review (grep for ujson in structured-llm main sources) | adversarial review |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `DynamicValue.parse` | method | `structured-llm/src/main/scala/org/adk4s/structured/sap/DynamicTypeBuilder.scala` | replace ujson-then-hand-walk with `smithy4s.json.Json.read[Document]`; `ujsonValueToDocument` becomes the seed for `JsonValueCodec.fromUjson` (json-value-model spec) |
