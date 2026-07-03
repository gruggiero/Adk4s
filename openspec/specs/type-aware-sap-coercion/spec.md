# Spec: Type-Aware SAP Coercion

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `SchemaData[A]` | case class | `org.adk4s.structured.core` |
| `ParseError` | sealed trait | `org.adk4s.structured.core` |
| `ParseResult[+A]` | enum | `org.adk4s.structured.core` |
| `ParserConfig` | case class | `org.adk4s.structured.sap` |
| `StructuredLLMError` | sealed trait | `org.adk4s.structured.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `JsonishValue` | sealed trait (ADT) | Intermediate parse value with ambiguity support via AnyOf |
| `CompletionState` | enum | Pending / Incomplete / Complete — tracks parse completeness |
| `TypeCoercer[A]` | typeclass | Schema-driven coercion from JsonishValue to typed A |
| `CoercionScore` | trait | Scoring — lower score = fewer coercions = better parse |
| `CoercionFlag` | sealed trait | Recovery actions taken during coercion (ObjectToString, StrippedNonAlphaNumeric, DefaultFromNoValue, etc.) |
| `BamlValueWithFlags[A]` | case class | Coerced value + flags + score |
| `ParsingContext` | case class | Context for coercion (path, config, depth) |

## ADDED Requirements

### Requirement: JsonishValue ADT with ambiguity support

The system SHALL parse LLM responses into a `JsonishValue` ADT that supports ambiguous interpretations via an `AnyOf` variant, enabling the coercer to select the best interpretation against the target schema.

**Given** an LLM response string that could be interpreted multiple ways (e.g., `"42"` as string or number)
**When** the jsonish parser processes the response
**Then** the result is a `JsonishValue.AnyOf` containing all plausible interpretations

**Rationale**: BAML's core innovation is representing parse ambiguity explicitly, then resolving it using the target schema.

#### Scenario: Numeric string produces AnyOf

**Given** the response `{"age": "42"}`
**When** parsed into JsonishValue with target schema `Int`
**Then** the `age` field is `AnyOf([Str("42"), Num(42)], "42")` and the coercer selects `Num(42)`

#### Scenario: Markdown-wrapped JSON

**Given** the response ```` ```json\n{"name": "John"}\n``` ````
**When** parsed into JsonishValue
**Then** the result is `Markdown(raw, Obj(...), Complete)`

#### Scenario: Truncated JSON

**Given** the response `{"name": "John", "age":`
**When** parsed into JsonishValue
**Then** the result has `CompletionState.Incomplete` and the `age` field is `Pending`

### Requirement: Schema-driven type coercion

The system SHALL coerce `JsonishValue` into typed values using a `TypeCoercer[A]` typeclass derived from smithy4s schemas, performing string→int, string→bool, enum fuzzy matching, union variant selection, and single→array coercion.

**Given** a `JsonishValue` and a target `Schema[A]`
**When** the coercer processes the value
**Then** the result is `Either[ParsingError, BamlValueWithFlags[A]]` where flags record each coercion performed

**Rationale**: Schema-driven coercion is what enables BAML to achieve state-of-the-art accuracy on the Berkeley Function Calling Leaderboard.

#### Scenario: String to Int coercion

**Given** `JsonishValue.Str("42")` and target type `Int`
**When** the coercer processes the value
**Then** the result is `Right(BamlValueWithFlags(42, Vector(StringToInt)))` with score > 0

#### Scenario: String to Boolean coercion

**Given** `JsonishValue.Str("true")` and target type `Boolean`
**When** the coercer processes the value
**Then** the result is `Right(BamlValueWithFlags(true, Vector(StringToBool)))` with score > 0

#### Scenario: Single value to Array coercion

**Given** `JsonishValue.Str("foo")` and target type `List[String]`
**When** the coercer processes the value
**Then** the result is `Right(BamlValueWithFlags(Vector("foo"), Vector(SingleToArray)))` with score > 0

#### Scenario: Coercion failure

**Given** `JsonishValue.Str("abc")` and target type `Int`
**When** the coercer processes the value
**Then** the result is `Left(ParsingError(...))` indicating type mismatch

### Requirement: Enum fuzzy matching with escalating strategies

The system SHALL match enum variants using 4 escalating strategies: exact match, punctuation-stripped match, case-insensitive match, and case-insensitive punctuation-stripped match, with increasing score penalties.

**Given** an enum with values `["MathScience", "Humanities"]` and input `"math-science"`
**When** the coercer matches the input against enum values
**Then** the match succeeds via punctuation-stripped strategy with a higher score penalty than exact match

**Rationale**: LLMs often produce enum values with different casing or punctuation than the schema defines.

#### Scenario: Exact match — score 0

**Given** enum values `["Active", "Inactive"]` and input `"Active"`
**When** the coercer matches
**Then** the result is `Right("Active")` with no fuzzy-match flags

#### Scenario: Case-insensitive match

**Given** enum values `["Active", "Inactive"]` and input `"active"`
**When** the coercer matches
**Then** the result is `Right("Active")` with `CaseInsensitive` flag

#### Scenario: No match — error

**Given** enum values `["Active", "Inactive"]` and input `"Pending"`
**When** the coercer matches
**Then** the result is `Left(ParsingError(...))` indicating unexpected enum value

### Requirement: Scoring system for parse candidate selection

The system SHALL score each successful coercion and select the parse candidate with the lowest total score when multiple interpretations succeed.

**Given** multiple parse candidates that all coerce successfully to the target type
**When** the scoring system evaluates them
**Then** the candidate with the lowest total score (fewest coercions) is selected

**Rationale**: When the parser produces `AnyOf` with multiple interpretations, the scoring system determines which is most likely correct.

#### Scenario: Exact match beats coerced match

**Given** `AnyOf([Str("42"), Num(42)])` with target `Int`
**When** the coercer evaluates both
**Then** `Num(42)` is selected (score 0) over `Str("42")` (score > 0)

#### Scenario: All candidates fail

**Given** `AnyOf([Str("abc"), Num(42.5)])` with target `Int`
**When** the coercer evaluates both
**Then** the result is `Left(ParsingError(...))` with aggregated errors

### Requirement: Union variant selection

The system SHALL select the best union variant by trying each variant's `tryCast` (short-circuit on score 0), then full `coerce` on all variants, picking the lowest score.

**Given** a `JsonishValue` and a target union type `A | B`
**When** the coercer processes the value
**Then** each variant is tried, and the variant with the lowest coercion score is selected

#### Scenario: Perfect match short-circuits

**Given** union `Int | String` and input `Num(42)`
**When** the coercer tries `Int` first
**Then** `tryCast` succeeds with score 0 and `String` is not tried

#### Scenario: No perfect match — lowest score wins

**Given** union `Int | String` and input `Str("42")`
**When** the coercer tries both variants
**Then** `Int` (score from StringToInt) is compared against `String` (score 0), and `String` wins

### Requirement: Backward compatibility with existing SAP

The system SHALL maintain backward compatibility with the existing `SchemaAlignedParser.parse[A: Schema]` API, with all 21 existing `SchemaSamplesParsingSuite` tests passing unchanged.

**Given** any test case from `SchemaSamplesParsingSuite`
**When** parsed with the new type-aware SAP
**Then** the result matches the previous output (Success with same value, or Failure with equivalent error)

**Rationale**: The parser rewrite must not regress existing parsing accuracy.

## Properties (Ring 3)

### Property: Score ordering — lower score is always preferred

**Invariant**: For any two successful coercions `a` and `b` of the same JsonishValue, if `a.score < b.score`, then `a` is selected.

**Generator strategy**: `Gen` of JsonishValue with AnyOf containing two known-coercible values, classify by score-difference.

```
forAll { (v: JsonishValue.AnyOf, target: Schema[Int]) =>
  val results = v.choices.map(c => coercer.coerce(Some(c), ctx, target))
  val successful = results.collect { case Right(r) => r }
  if successful.size > 1 then successful.minBy(_.score) == selectedResult
}
```

### Property: Coercion preserves semantic value

**Invariant**: For any string `s` representing a valid integer `n`, coercing `JsonishValue.Str(s)` to `Int` yields `n`.

**Generator strategy**: `Gen.int(Range.linear(-10000, 10000))` mapped to string, classify by negative/zero/positive.

```
forAll { (n: Int) =>
  val strVal = JsonishValue.Str(n.toString, Complete)
  coercer.coerce(Some(strVal), ctx, Schema[Int]) == Right(BamlValueWithFlags(n, Vector(StringToInt)))
}
```

### Property: Existing parsing tests pass unchanged

**Invariant**: For all 21 existing `SchemaSamplesParsingSuite` test inputs, the new parser produces equivalent results.

**Generator strategy**: Fixed fixture set (21 existing test samples), no random generation.

```
forAll { (sample: SchemaSample) =>
  parseWithNewSAP(sample.input, sample.schema) == parseWithOldSAP(sample.input, sample.schema)
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| JsonishValue ADT is exhaustive | type definition | type system (sealed trait) | TypeCoercionSpec |
| AnyOf preserves all interpretations | Requirement 1 | Hedgehog property | TypeCoercionSpec |
| String→Int coercion correctness | Requirement 2 | Hedgehog property | TypeCoercionSpec |
| Enum fuzzy matching strategies escalate | Requirement 3 | Scenario test | EnumMatchingSpec |
| Score ordering — lowest wins | Requirement 4 | Hedgehog property | ScoringSpec |
| Union variant selection — tryCast short-circuit | Requirement 5 | Scenario test | UnionCoercionSpec |
| Backward compatibility | Requirement 6 | Existing test suite (21 tests) | SchemaSamplesParsingSuite |
| Coercion failure produces error | Requirement 2 error path | Scenario test | TypeCoercionSpec |
