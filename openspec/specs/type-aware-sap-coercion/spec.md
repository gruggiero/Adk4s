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

### Requirement: JsonishParser is a real tolerant string-to-JsonishValue parser

The system SHALL provide a `JsonishParser.parse(raw: String): JsonishValue` function that parses a raw LLM response string into a `JsonishValue` ADT using a tolerant, quote-state-tracking scanner — NOT a regex-over-string cleaning pipeline. The parser SHALL handle: markdown-fence extraction (```` ```json ... ``` ````), structural balance recovery (unclosed braces/brackets → `CompletionState.Incomplete`), comment stripping (`//` and `/* */`), and in-string-literal tracking that correctly distinguishes apostrophes inside string values from quote delimiters. The parser SHALL NOT mutate the input string before parsing (no regex-based `fixQuotes` that corrupts string values containing apostrophes).

**Given** an LLM response `{"note": "it's fine, isn't it"}` (syntactically valid JSON containing apostrophes in a string value)
**When** `JsonishParser.parse` processes the response
**Then** the result is `JsonishValue.Obj(Map("note" -> JsonishValue.Str("it's fine, isn't it", Complete)), Complete)` — the apostrophes are preserved, NOT corrupted to `"it"s fine, isn"t it"`

**Rationale**: The pre-change `SchemaAlignedParser.fixQuotes` regex runs on every candidate, including already-valid JSON, and corrupts any string value containing two apostrophes. Verified: `{"note": "it's fine, isn't it"}` is rewritten to `{"note": "it"s fine, isn"t it"}` and fails to parse. This is ordinary English LLM prose, not a contrived case. The fix is a real scanner that tracks quote state, not a regex that operates on the raw string.

#### Scenario: Valid JSON with apostrophes in string values

**Given** the response `{"note": "it's fine, isn't it"}`
**When** `JsonishParser.parse` processes it
**Then** the result is a `JsonishValue.Obj` with `note` = `Str("it's fine, isn't it", Complete)` — apostrophes preserved

#### Scenario: Markdown-wrapped JSON

**Given** the response ```` ```json\n{"name": "John"}\n``` ````
**When** `JsonishParser.parse` processes it
**Then** the result is `JsonishValue.Markdown(raw, Obj(Map("name" -> Str("John", Complete))), Complete)`

#### Scenario: Truncated JSON

**Given** the response `{"name": "John", "age":`
**When** `JsonishParser.parse` processes it
**Then** the result has `CompletionState.Incomplete` and the `age` field is `Pending`

#### Scenario: Numeric string produces AnyOf

**Given** the response `{"age": "42"}`
**When** `JsonishParser.parse` processes it
**Then** the `age` field is `AnyOf(Vector(Str("42", Complete), Num(42, Complete)), "42")` — both interpretations preserved for the coercer

#### Scenario: Comment stripping

**Given** the response `{"name": "John", /* comment */ "age": 42}`
**When** `JsonishParser.parse` processes it
**Then** the comment is stripped and the result is `Obj(Map("name" -> Str("John", Complete), "age" -> Num(42, Complete)), Complete)`

#### Scenario: Unclosed brace recovery

**Given** the response `{"name": "John"`
**When** `JsonishParser.parse` processes it
**Then** the result has `CompletionState.Incomplete` and the parsed fields are preserved

### Requirement: TypeCoercer.coerce is wired into SchemaAlignedParser as the decode path

The system SHALL wire `TypeCoercer.coerce[A]` into `SchemaAlignedParser.parse[A: Schema]` as the actual decode path, replacing the stubbed `parseAndCoerce = (response, Vector.empty)`. The new decode path SHALL be: `JsonishParser.parse(raw)` → `TypeCoercer.coerce[A](jsonishValue, ParsingContext, schema)` → `Either[ParsingError, BamlValueWithFlags[A]]`. The `SchemaAlignedParser.parse` API signature SHALL be unchanged (backward compatibility, base spec Requirement 6).

**Given** an LLM response and a target `Schema[A]`
**When** `SchemaAlignedParser.parse[A](response)` is called
**Then** the decode path is `JsonishParser.parse` → `TypeCoercer.coerce` (NOT regex-cleaning candidates → ujson parse → hand-walk), and the result is `ParseResult.Success(value)` or `ParseResult.Failure(error)`

**Rationale**: The `TypeCoercer.parseAndCoerce` method is currently a literal pass-through stub: `(response, Vector.empty)`. There is no function anywhere that parses a raw string into `JsonishValue`. `SchemaAlignedParser` never calls the coercer. The base spec's requirements (schema-driven coercion, scoring, union selection) are specified but never exercised. This change completes the wiring.

#### Scenario: String to Int coercion via the wired path

**Given** `JsonishValue.Str("42", Complete)` and target type `Int`
**When** `SchemaAlignedParser.parse[Int]` processes the response `"42"`
**Then** the result is `Success(42)` with the coercion path producing `BamlValueWithFlags(42, Vector(StringToInt), score > 0)`

#### Scenario: Coercion failure produces ParseResult.Failure

**Given** `JsonishValue.Str("abc", Complete)` and target type `Int`
**When** `SchemaAlignedParser.parse[Int]` processes the response `"abc"`
**Then** the result is `Failure(ParseError(...))` indicating type mismatch

#### Scenario: Backward compatibility — existing SAP tests pass

**Given** any of the 21 existing `SchemaSamplesParsingSuite` test cases
**When** parsed with the new `JsonishParser` → `TypeCoercer` decode path
**Then** the result matches the previous output (Success with same value, or Failure with equivalent error) — base spec Requirement 6 holds

### Requirement: JsonFixMiddleware absorbed into JsonishParser and deleted

The system SHALL absorb `JsonFixMiddleware`'s quote-state-tracking scanner into `JsonishParser`'s in-string-literal tracking (it is the one piece of the two duplicate repairers that is actually correct), and SHALL delete `JsonFixMiddleware` itself. `ToolsNode`'s argument-repair path SHALL call the shared `JsonishParser` for tool-argument repair instead of `JsonFixMiddleware`.

**Given** a tool-argument JSON string with unbalanced quotes that `JsonFixMiddleware` previously repaired
**When** `ToolsNode` repairs the argument using `JsonishParser.parse`
**Then** the repair succeeds with the same result `JsonFixMiddleware` would have produced, and `JsonFixMiddleware` no longer exists in the codebase

**Rationale**: `JsonFixMiddleware` and `SchemaAlignedParser.fixQuotes` are two duplicate JSON-repair implementations. `JsonFixMiddleware`'s quote-state-tracking scanner is correct; SAP's regex is not (it causes the apostrophe bug). Folding the correct scanner into `JsonishParser` and deleting both duplicate repairers eliminates the bug at its source and removes the maintenance burden of two repairers.

#### Scenario: Tool argument repair via JsonishParser

**Given** a tool argument `{"location": "New York, NY"}` with a trailing comma that `JsonFixMiddleware` would have fixed
**When** `ToolsNode` repairs it via `JsonishParser.parse`
**Then** the repair succeeds and the parsed value is `Obj(Map("location" -> Str("New York, NY", Complete)), Complete)`

#### Scenario: JsonFixMiddleware is deleted

**Given** the codebase after this change
**When** a search for `JsonFixMiddleware` is performed
**Then** no file named `JsonFixMiddleware.scala` exists, and no import of `JsonFixMiddleware` appears in any source file

### Requirement: BamlValueWithFlags and ParsingContext implemented

The system SHALL implement `BamlValueWithFlags[A]` as `case class BamlValueWithFlags[A](value: A, flags: Vector[CoercionFlag], score: CoercionScore)` and `ParsingContext` as a case class carrying path, config, and depth context threaded through coercion, per the base spec's Concepts Introduced table. These are already named in the base spec but never implemented.

**Given** a successful coercion of `JsonishValue.Str("42", Complete)` to `Int`
**When** `TypeCoercer.coerce[Int](strVal, ParsingContext.empty, Schema[Int])` is called
**Then** the result is `Right(BamlValueWithFlags(42, Vector(CoercionFlag.StringToInt), score))` where `score > CoercionScore.zero`

**Rationale**: The base spec commits to these types but they do not exist in the codebase. They are the return type of `TypeCoercer.coerce` and the context threaded through coercion — without them, the coercer cannot be wired into SAP.

#### Scenario: BamlValueWithFlags carries flags and score

**Given** a coercion that performed `StringToInt`
**When** the `BamlValueWithFlags` is inspected
**Then** `flags` contains `CoercionFlag.StringToInt` and `score > CoercionScore.zero`

#### Scenario: ParsingContext tracks depth

**Given** a nested object coercion at depth 3
**When** `TypeCoercer.coerce` recurses into a nested field
**Then** the `ParsingContext.depth` is incremented for the recursive call

### Requirement: Schema-driven type coercion

The system SHALL coerce `JsonishValue` into typed values using a `TypeCoercer[A]` typeclass derived from smithy4s schemas, performing string→int, string→bool, enum fuzzy matching, union variant selection, and single→array coercion. The coercer SHALL be wired into `SchemaAlignedParser.parse` as the actual decode path (see Requirement: TypeCoercer.coerce is wired into SchemaAlignedParser). The result SHALL be `Either[ParsingError, BamlValueWithFlags[A]]` where flags record each coercion performed.

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

The system SHALL match enum variants using 4 escalating strategies: exact match, punctuation-stripped match, case-insensitive match, and case-insensitive punctuation-stripped match, with increasing score penalties. This requirement is COMPLETED — the `EnumMatching` object is implemented and wired into `TypeCoercer.coerce`.

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

The system SHALL score each successful coercion and select the parse candidate with the lowest total score when multiple interpretations succeed. This requirement is COMPLETED — the scoring system is implemented as part of `TypeCoercer.coerce` and the candidate-selection fold.

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

The system SHALL select the best union variant by trying each variant's `tryCast` (short-circuit on score 0), then full `coerce` on all variants, picking the lowest score. This requirement is COMPLETED — the union variant selection is implemented in `TypeCoercer.coerce`.

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
