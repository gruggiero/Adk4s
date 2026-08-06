# Spec: Structured Toolcall (Delta)

<!-- DELTA spec for the migrate-json-codec change. MODIFIES:
     - `ToolSchema.derive`'s derivation strategy from hand-rolled `Mirror`-based
       encode/decode (with 4 `asInstanceOf` casts, 6-primitive-type limit, silent
       `case other => ujson.Str(other.toString)` fallback) to a schema-derived
       codec (smithy4s `Schema[A]` + jsoniter), matching `structured-llm`'s
       existing `Schema[A]` design
     - `ToolSchemaError` population: the decoder SHALL produce the specific
       cases (`MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`,
       `DecodingFailed`) instead of a flattened `getMessage` string
     - `ToolSchema[A]`'s `jsonSchema` field stays `ujson.Value` (it is JSON
       Schema metadata for `AdkToolInfo.parameters` → `org.llm4s.toolapi`,
       which is boundary data per the json-value-model spec)
     The `StructuredToolCall` trait, `StructuredToolCallError` ADT,
     `StructuredToolFunction` wrapper, and the example requirements in the
     base spec are UNCHANGED. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| tool | `ToolSchema.derive`'s derivation strategy changes; tool abstraction unchanged | `openspec/concepts/tool.md` |
| tools-node | `ToolsNode` consumes `ToolSchema`-derived codecs; behavior unchanged | `openspec/concepts/tools-node.md` |

No change to the `tool.md` or `tools-node.md` concept files' actions, state, or synchronizations — only the `ToolSchema.derive` implementation and `ToolSchemaError` population change. The `tool.md` Implementation map row for `ToolSchema.derive` is updated at apply Step 12.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ToolSchema[A]` | opaque type | `org.adk4s.core.tools` |
| `ToolSchemaError` | sealed trait (`MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, `DecodingFailed`) | `org.adk4s.core.tools` |
| `StructuredToolCallError` | sealed trait (`UnknownTool`, `InvalidArguments`, `ExecutionFailed`, `ResultParsingFailed`) | `org.adk4s.core.tools` |
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `smithy4s.json.Json` | object (`read[A]`/`writeBlob`) | `smithy4s.json` |
| `smithy4s.Schema[A]` | schema typeclass | `smithy4s` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| schema-derived tool codec (name TBD in design) | derivation (smithy4s `Schema[A]`-based, replacing `ToolInfer`) | JSON Schema + decoder + encoder from one source, supporting nested types/collections/enums beyond the current six primitives |

## MODIFIED Requirements

### Requirement: ToolSchema Typeclass

The system SHALL provide a `ToolSchema[A]` typeclass for defining tool argument and result schemas with JSON encoding and decoding capabilities. The `ToolSchema.derive` method SHALL derive both the JSON Schema (for `AdkToolInfo.parameters`) and the decoder/encoder from a smithy4s `Schema[A]` (or `JsonCodecMaker.make[A]` if a case class has no natural Smithy shape), matching `structured-llm`'s existing `Schema[A]` design so there is one schema system instead of two. The derivation SHALL NOT use `asInstanceOf` (removing the four existing sites in `ToolInfer`/`ToolSchema.derive`), SHALL support nested case classes, collections, and enums (beyond the current six primitives + `Option[primitive]`), and SHALL NOT have a silent `case other => ujson.Str(other.toString)` fallback.

**Given** a case class `WeatherRequest(location: String, unit: String, days: Option[Int], tags: List[String])` with nested types and collections
**When** `ToolSchema.derive[WeatherRequest]` is called
**Then** a `ToolSchema[WeatherRequest]` is derived that decodes JSON with `location`, `unit`, optional `days`, and `tags` array — no `asInstanceOf`, no silent string fallback, and `ToolSchemaError`'s specific cases are populated on failure

**Rationale**: The pre-change `ToolInfer`/`ToolSchema.derive` uses four `asInstanceOf` casts (against the project's "NEVER use asInstanceOf" rule), supports only six primitive types + `Option[primitive]` (no nested case classes, no collections, no enums), has a silent `case other => ujson.Str(other.toString)` fallback that hides type errors, and discards `ToolSchemaError`'s rich cases (`MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, each carrying a `path`) in favor of a flattened `getMessage` string. This is precisely the class of defect the project's own Ring 8 adversarial review exists to catch. The fix unifies the two schema systems (ADK `Schema[A]` + tool codec) on smithy4s.

#### Scenario: Create ToolSchema instance

**Given** a case class type representing tool arguments
**When** `ToolSchema.instance` is called with jsonSchema, description, decoder, and encoder
**Then** a `ToolSchema[A]` instance is created (unchanged from base spec)

#### Scenario: Decode valid JSON to typed value

**Given** a `ToolSchema[A]` instance and valid JSON matching the schema
**When** the decoder is applied to the JSON
**Then** a `Right[A]` containing the decoded value is returned (unchanged from base spec)

#### Scenario: Decode invalid JSON returns specific ToolSchemaError

**Given** a `ToolSchema[A]` instance and JSON with a missing required field "location"
**When** the decoder is applied to the JSON
**Then** a `Left[ToolSchemaError.MissingRequiredField]` is returned carrying the field path `["location"]` — NOT a flattened `getMessage` string

#### Scenario: Type mismatch produces ToolSchemaError.TypeMismatch

**Given** JSON with a string value `"hot"` where `Int` is expected for field `temperature`
**When** the decoder is applied
**Then** a `Left[ToolSchemaError.TypeMismatch]` is returned carrying the field path `["temperature"]`, the expected type `Int`, and the actual value

#### Scenario: Invalid enum value produces ToolSchemaError.InvalidEnumValue

**Given** JSON with a value `"Pending"` not in the allowed enum set `["Active", "Inactive"]`
**When** the decoder is applied
**Then** a `Left[ToolSchemaError.InvalidEnumValue]` is returned carrying the field path, the invalid value `"Pending"`, and the allowed values

#### Scenario: Encode typed value to JSON

**Given** a `ToolSchema[A]` instance and a typed value
**When** the encoder is applied
**Then** a `ujson.Value` representing the JSON is returned (unchanged — `jsonSchema` and encoder output stay `ujson.Value` because they feed `AdkToolInfo.parameters` → `org.llm4s.toolapi`, which is boundary data)

#### Scenario: Access JSON schema definition

**Given** a `ToolSchema[A]` instance
**When** `jsonSchema` is accessed
**Then** the JSON schema definition is returned as `ujson.Value` (unchanged — boundary data for `AdkToolInfo.parameters`)

#### Scenario: Derive schema for nested case class with collections

**Given** a case class `Order(items: List[Item], total: BigDecimal)` where `Item` is a nested case class
**When** `ToolSchema.derive[Order]` is called
**Then** the derived schema decodes JSON with a `items` array of nested `Item` objects and a `total` `BigDecimal` — no `asInstanceOf`, no silent fallback, no six-primitive limit

#### Scenario: No silent string fallback

**Given** a JSON value of an unexpected type for a field
**When** the decoder is applied
**Then** a `Left[ToolSchemaError]` is returned (specific case) — NOT a `Right` with the value silently converted via `other.toString`

### Requirement: ToolSchemaError

The system SHALL provide error types for schema-level validation and decoding failures. The decoder SHALL populate the specific cases (`MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, `DecodingFailed`) with field paths and contextual details, NOT a flattened `getMessage` string. Every match over `ToolSchemaError` SHALL be exhaustive — no catch-all arm is permitted (enforced by the project's exhaustiveness escalation).

**Given** JSON missing a required field defined in the schema
**When** decoding is attempted
**Then** a `ToolSchemaError.MissingRequiredField` is returned carrying the field path

**Rationale**: `ToolSchemaError`'s sealed hierarchy already defines `MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, and `DecodingFailed` (verified in the concept inventory — 4 variants). The pre-change decoder discards these in favor of a flattened `getMessage` string, hiding the structured error information that callers and debuggers need. Populating the specific cases makes the error algebra useful and enables callers to pattern-match on the failure kind.

#### Scenario: Missing required field error

**Given** JSON missing a required field "location" defined in the schema
**When** decoding is attempted
**Then** a `ToolSchemaError.MissingRequiredField(path = List("location"))` is returned

#### Scenario: Type mismatch error

**Given** JSON with a string value where number is expected at field "temperature"
**When** decoding is attempted
**Then** a `ToolSchemaError.TypeMismatch(path = List("temperature"), expected = "Int", actual = "String")` is returned

#### Scenario: Invalid enum value error

**Given** JSON with a value not in the allowed enum set at field "status"
**When** decoding is attempted
**Then** a `ToolSchemaError.InvalidEnumValue(path = List("status"), value = "Pending", allowed = List("Active", "Inactive"))` is returned

#### Scenario: Decoding failure (catch-all for smithy4s decode errors)

**Given** a JSON value that fails smithy4s decoding for a reason not covered by the above cases
**When** decoding is attempted
**Then** a `ToolSchemaError.DecodingFailed(path, message)` is returned carrying the smithy4s error message

## Properties (Ring 3)

### Property: ToolSchema.derive round-trips all supported shapes

**Invariant**: For every case class `A` with supported field types (primitives, nested case classes, `Option`, `List`, `Vector`, enums, `BigDecimal`), `ToolSchema.derive[A]` produces a codec where `decode(encode(a)) == Right(a)`.

**Generator strategy**: `genToolSchemaCaseClass` (constructive — generates case class shapes with a mix of field types: `String`, `Int`, `Double`, `Boolean`, `Option[String]`, `List[String]`, nested case class, enum; classify by field-type mix). Uses reflection or a fixed set of representative case classes (the latter is more practical for Scala 3 derivation testing).

```
forAll { (a: A) =>
  val schema = ToolSchema.derive[A]
  val json = schema.encode(a)
  schema.decode(json) == Right(a)
}
```

### Property: ToolSchemaError specific cases are populated

**Invariant**: For every decode failure, the returned `ToolSchemaError` is one of the four specific cases (`MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, `DecodingFailed`) carrying a field path — NOT a flattened message.

**Generator strategy**: `genInvalidJson` (constructive — generates JSON that fails decoding for a specific reason: missing required field, type mismatch, invalid enum value, other decode failure; classify by failure kind).

```
forAll { (invalidJson: ujson.Value, schema: ToolSchema[A]) =>
  schema.decode(invalidJson) match {
    case Left(e: ToolSchemaError) =>
      e.path.nonEmpty && // every specific case carries a path
      Set("MissingRequiredField", "TypeMismatch", "InvalidEnumValue", "DecodingFailed").contains(e.productPrefix)
    case Right(_) => false // expected a failure
  }
}
```

### Property: No asInstanceOf in ToolSchema.derive

**Invariant**: The `ToolSchema.derive` implementation contains no `asInstanceOf` calls.

**Generator strategy**: This is a static-analysis property, not a runtime property — verified by WartRemover (`AsInstanceOf` wart is ACTIVE per the capability profile) and adversarial review. Listed here for completeness.

```
// Verified by: sbt adk4s-core/compile (WartRemover AsInstanceOf wart)
// and: grep -r "asInstanceOf" ToolSchema.scala ToolInfer.scala (zero results)
```

### Property: No silent string fallback

**Invariant**: For every JSON value of an unexpected type, the decoder returns a `Left[ToolSchemaError]` — NOT a `Right` with the value silently converted via `other.toString`.

**Generator strategy**: `genUnexpectedTypeJson` (constructive — generates JSON where a field has a type that does not match the schema, e.g., a string where an Int is expected; classify by expected-vs-actual type pair).

```
forAll { (json: ujson.Value, schema: ToolSchema[A]) =>
  schema.decode(json) match {
    case Right(value) =>
      // verify the value was not silently stringified — check against the JSON
      !json.toString.contains(value.toString) || schema.encode(value) == json
    case Left(_) => true // a Left is the correct behavior for unexpected types
  }
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `asInstanceOf` in `ToolSchema.derive` or `ToolInfer` | The project's "NEVER use asInstanceOf" rule; WartRemover `AsInstanceOf` wart is ACTIVE | WartRemover compile error + `assertDoesNotCompile("val x = y.asInstanceOf[Z]")` in the derivation's scope |
| `case other => ujson.Str(other.toString)` in the decoder | The silent string fallback hides type errors | adversarial review + compile-negative pattern test |
| Non-exhaustive match over `ToolSchemaError` | Exhaustiveness escalation is active; the 4-variant sealed trait requires all cases | `assertDoesNotCompile("e match { case MissingRequiredField(_) => () }")` (missing 3 variants) |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| ToolSchema.derive uses smithy4s Schema[A] | Requirement: ToolSchema Typeclass + Scenario: Derive schema for nested case class with collections | adversarial review (audit derivation source) | adversarial review |
| No asInstanceOf in derivation | Requirement: ToolSchema Typeclass + Property: No asInstanceOf in ToolSchema.derive | WartRemover (AsInstanceOf wart) + compile-negative test | WartRemover + ToolSchemaTypeContract |
| Nested case classes supported | Requirement: ToolSchema Typeclass + Scenario: Derive schema for nested case class with collections | Hedgehog property (round-trip) + scenario test | ToolSchemaSpec |
| Collections supported | Requirement: ToolSchema Typeclass + Scenario: Derive schema for nested case class with collections | Hedgehog property (round-trip) | ToolSchemaSpec |
| Enums supported | Requirement: ToolSchema Typeclass | Hedgehog property (round-trip) | ToolSchemaSpec |
| No silent string fallback | Requirement: ToolSchema Typeclass + Scenario: No silent string fallback + Property: No silent string fallback | Hedgehog property + adversarial review | ToolSchemaSpec |
| MissingRequiredField populated with path | Requirement: ToolSchemaError + Scenario: Missing required field error | scenario test + Hedgehog property (specific cases populated) | ToolSchemaErrorSpec |
| TypeMismatch populated with path and types | Requirement: ToolSchemaError + Scenario: Type mismatch error | scenario test | ToolSchemaErrorSpec |
| InvalidEnumValue populated with path and allowed values | Requirement: ToolSchemaError + Scenario: Invalid enum value error | scenario test | ToolSchemaErrorSpec |
| DecodingFailed populated for other errors | Requirement: ToolSchemaError + Scenario: Decoding failure | scenario test | ToolSchemaErrorSpec |
| Exhaustive match over ToolSchemaError | Compile-Negative: non-exhaustive match over ToolSchemaError | compile-negative test | ToolSchemaTypeContract |
| jsonSchema stays ujson.Value (boundary) | Requirement: ToolSchema Typeclass + Scenario: Access JSON schema definition | type system (field type) + adversarial review (confirm boundary allowlist) | adversarial review |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `ToolSchema.derive` | method (modified) | `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolSchema.scala` | replace hand-rolled `Mirror`-based encode/decode with smithy4s `Schema[A]` + jsoniter; remove 4 `asInstanceOf` sites |
| `ToolInfer` | object (DELETED or refactored) | `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolInfer.scala` | hand-rolled derivation replaced by smithy4s-based derivation; file removed or its logic moved into `ToolSchema.derive` |
| `ToolSchemaError` decoder population | modified | `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolSchema.scala` | decoder produces `MissingRequiredField`/`TypeMismatch`/`InvalidEnumValue`/`DecodingFailed` with paths, not flattened messages |
| `ToolSchema.jsonSchema` | field (unchanged type) | `ToolSchema.scala` | stays `ujson.Value` — boundary data for `AdkToolInfo.parameters` → `org.llm4s.toolapi` |
