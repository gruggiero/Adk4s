# Spec: Partial Types for Streaming

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `SchemaData[A]` | case class | `org.adk4s.structured.core` |
| `StructuredLLM[F[_]]` | trait | `org.adk4s.structured.core` |
| `Prompt` | case class | `org.adk4s.structured.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `Partial[A]` | typeclass | Makes all fields optional — `partialSchema: Schema[Repr]`, `fromPartial: Repr => A` |
| `streamPartial[A: Partial]` | method | Streams Partial[A] values as tokens arrive, converts to A at end |

## ADDED Requirements

### Requirement: Partial typeclass with optional fields

The system SHALL provide a `Partial[A]` typeclass that produces a loosened version of `A` where all fields are `Option[T]`, derivable from smithy4s schemas.

**Given** a type `A` with fields `name: String, age: Int`
**When** `Partial[A]` is derived
**Then** `partialSchema` produces a `Schema[Repr]` where `Repr` has fields `name: Option[String], age: Option[Int]`

**Rationale**: Partial types enable streaming UIs to show partial data before the full response is received.

#### Scenario: Derive partial for case class

**Given** `case class Person(name: String, age: Int)` with `Schema[Person]`
**When** `Partial[Person]` is derived
**Then** `partialSchema` accepts JSON with any subset of fields present

#### Scenario: fromPartial fills defaults

**Given** a partial `Person.Repr(name=Some("John"), age=None)`
**When** `fromPartial` is called
**Then** the result is `Person("John", 0)` with default for missing `age`

### Requirement: streamPartial API with Partial constraint

The system SHALL provide `streamPartial[A: Partial](prompt: Prompt): Stream[F, Partial[A]#Repr]` that streams partial values during streaming and converts to `A` at the end.

**Given** a `StructuredLLM[F]` and a `Prompt` with a `Partial[A]` available
**When** `streamPartial(prompt)` is called
**Then** the result is an `fs2.Stream[F, Repr]` emitting partial values and ending with a complete `Repr` that `fromPartial` converts to `A`

#### Scenario: Stream partials then complete

**Given** a mock LLM streaming `{"name": "John", "age":` (incomplete)
**When** `streamPartial` is consumed
**Then** the stream emits `Repr(name=Some("John"), age=None)` and later `Repr(name=Some("John"), age=Some(30))`

## Properties (Ring 3)

### Property: fromPartial(partialSchema.decode(encode(a))) equals a

**Invariant**: For any complete value `a: A`, encoding to JSON, decoding as partial, then `fromPartial` produces the original `a`.

**Generator strategy**: `Gen` of `A` via smithy4s-derived generators, classify by field-count.

```
forAll { (a: A) =>
  val json = encode(a)
  val partial = partialSchema.decode(json)
  fromPartial(partial) == a
}
```

### Property: Partial schema accepts any subset of fields

**Invariant**: For any subset of fields of `A`, the partial schema decodes successfully with `None` for missing fields.

**Generator strategy**: `Gen` of field-subsets (random subsets of A's fields), classify by subset-size.

```
forAll { (fields: Set[FieldName]) =>
  val json = buildJsonWithFields(fields)
  partialSchema.decode(json).isRight
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Partial makes all fields optional | Requirement 1 | Hedgehog property | PartialTypesSpec |
| fromPartial fills defaults | Requirement 1 | Scenario test | PartialTypesSpec |
| streamPartial emits partials | Requirement 2 | Scenario test | PartialTypesSpec |
| Round-trip: encode → partial decode → fromPartial | Property 1 | Hedgehog property | PartialTypesSpec |
