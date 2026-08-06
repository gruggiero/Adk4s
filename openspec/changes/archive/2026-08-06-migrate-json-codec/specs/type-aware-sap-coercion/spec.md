# Spec: Type-Aware SAP Coercion (Delta)

<!-- DELTA spec for the migrate-json-codec change. COMPLETES the
     already-specified but unimplemented `JsonishValue`/`TypeCoercer` design:
     - A real string → `JsonishValue` tolerant parser (replacing SAP's
       regex-cleaning candidate pipeline that causes the apostrophe bug)
     - `TypeCoercer.coerce` wired into `SchemaAlignedParser` as the actual
       decode path (replacing the stubbed `parseAndCoerce = (response, Vector.empty)`)
     - `BamlValueWithFlags`/`ParsingContext`/union-variant selection implemented
     - `JsonFixMiddleware`'s tool-argument repair folded into the same tolerant
       parser (it has the correct quote-state-tracking scanner that SAP's regex
       lacks); `JsonFixMiddleware` itself is deleted
     The base spec's requirements (JsonishValue ADT, schema-driven coercion,
     enum fuzzy matching, scoring, union variant selection, backward
     compatibility) are COMPLETED by this change — they were specified but
     never implemented. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| schema-aligned-parser | SAP's decode path migrates from regex-cleaning to `JsonishValue` tolerant parse + `TypeCoercer`; the `JsonFixMiddleware` repair is absorbed into the tolerant parser | `openspec/concepts/schema-aligned-parser.md` |

The `schema-aligned-parser.md` concept file's Implementation map is updated at apply Step 12 to reflect the new decode path (`JsonishParser.parse` → `TypeCoercer.coerce` → `BamlValueWithFlags[A]`) and the removal of `JsonFixMiddleware`.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `SchemaData[A]` | case class | `org.adk4s.structured.core` |
| `ParseError` | sealed trait | `org.adk4s.structured.core` |
| `ParseResult[+A]` | enum | `org.adk4s.structured.core` |
| `ParserConfig` | case class | `org.adk4s.structured.sap` |
| `StructuredLLMError` | sealed trait | `org.adk4s.structured.core` |
| `JsonishValue` | enum (`Null`, `Bool`, `Num`, `Str`, `Arr`, `Obj`, `Markdown`, `AnyOf`) | `org.adk4s.structured.sap` |
| `CompletionState` | enum (`Pending`, `Incomplete`, `Complete`) | `org.adk4s.structured.sap` |
| `CoercionFlag` | enum (`StringToInt`, `StringToBool`, `StringToFloat`, `IntToFloat`, `FloatToInt`, `SingleToArray`, `ObjectToString`, `StrippedNonAlphaNumeric`, `DefaultFromNoValue`, `CaseInsensitive`) | `org.adk4s.structured.sap` |
| `CoercionScore` | trait | `org.adk4s.structured.sap` |
| `EnumMatching` | object | `org.adk4s.structured.sap` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `JsonishParser` | object (`parse: String => JsonishValue`) | Tolerant string → `JsonishValue` parser — markdown-fence extraction, structural balance recovery, comment stripping, quote-state-tracking in-string-literal scanning (absorbed from `JsonFixMiddleware`); replaces SAP's regex-cleaning candidate pipeline |
| `TypeCoercer.coerce[A]` | typeclass method | `(JsonishValue, ParsingContext, Schema[A]) => Either[ParsingError, BamlValueWithFlags[A]]` — completes the stubbed `parseAndCoerce` |
| `BamlValueWithFlags[A]` | case class | `(value: A, flags: Vector[CoercionFlag], score: CoercionScore)` — already named in the base spec, not yet implemented |
| `ParsingContext` | case class | Path/config/depth context threaded through coercion, per the base spec |
| `ParsingError` (SAP-specific) | sealed trait | Error type for coercion failures; reuses the existing `org.adk4s.structured.core.ParseError` where possible, introduces SAP-specific cases only where needed |

## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: Schema-driven type coercion

The system SHALL coerce `JsonishValue` into typed values using a `TypeCoercer[A]` typeclass derived from smithy4s schemas, performing string→int, string→bool, enum fuzzy matching, union variant selection, and single→array coercion. The coercer SHALL be wired into `SchemaAlignedParser.parse` as the actual decode path (see ADDED Requirement: TypeCoercer.coerce is wired into SchemaAlignedParser). The result SHALL be `Either[ParsingError, BamlValueWithFlags[A]]` where flags record each coercion performed.

**Given** a `JsonishValue` and a target `Schema[A]`
**When** the coercer processes the value
**Then** the result is `Either[ParsingError, BamlValueWithFlags[A]]` where flags record each coercion performed

**Rationale**: (Base spec rationale unchanged — schema-driven coercion enables BAML-level accuracy. This delta adds the wiring commitment and the `BamlValueWithFlags` return type that the base spec named but did not implement.)

#### Scenario: String to Int coercion

**Given** `JsonishValue.Str("42", Complete)` and target type `Int`
**When** the coercer processes the value
**Then** the result is `Right(BamlValueWithFlags(42, Vector(StringToInt), score > 0))`

#### Scenario: String to Boolean coercion

**Given** `JsonishValue.Str("true", Complete)` and target type `Boolean`
**When** the coercer processes the value
**Then** the result is `Right(BamlValueWithFlags(true, Vector(StringToBool), score > 0))`

#### Scenario: Single value to Array coercion

**Given** `JsonishValue.Str("foo", Complete)` and target type `List[String]`
**When** the coercer processes the value
**Then** the result is `Right(BamlValueWithFlags(Vector("foo"), Vector(SingleToArray), score > 0))`

#### Scenario: Coercion failure

**Given** `JsonishValue.Str("abc", Complete)` and target type `Int`
**When** the coercer processes the value
**Then** the result is `Left(ParsingError(...))` indicating type mismatch

### Requirement: Scoring system for parse candidate selection

The system SHALL score each successful coercion and select the parse candidate with the lowest total score when multiple interpretations succeed. This requirement is COMPLETED by this change — the scoring system is implemented as part of `TypeCoercer.coerce` and the candidate-selection fold (see Formal Contracts).

**Given** multiple parse candidates that all coerce successfully to the target type
**When** the scoring system evaluates them
**Then** the candidate with the lowest total score (fewest coercions) is selected

**Rationale**: When the parser produces `AnyOf` with multiple interpretations, the scoring system determines which is most likely correct. (Base spec rationale — this delta completes the implementation.)

#### Scenario: Exact match beats coerced match

**Given** `AnyOf([Str("42", Complete), Num(42, Complete)])` with target `Int`
**When** the coercer evaluates both
**Then** `Num(42)` is selected (score 0) over `Str("42")` (score > 0)

#### Scenario: All candidates fail

**Given** `AnyOf([Str("abc", Complete), Num(42.5, Complete)])` with target `Int`
**When** the coercer evaluates both
**Then** the result is `Left(ParsingError(...))` with aggregated errors

### Requirement: Enum fuzzy matching with escalating strategies

The system SHALL match enum variants using 4 escalating strategies: exact match, punctuation-stripped match, case-insensitive match, and case-insensitive punctuation-stripped match, with increasing score penalties. This requirement is COMPLETED by this change — the `EnumMatching` object is implemented and wired into `TypeCoercer.coerce`.

**Given** an enum with values `["MathScience", "Humanities"]` and input `"math-science"`
**When** the coercer matches the input against enum values
**Then** the match succeeds via punctuation-stripped strategy with a higher score penalty than exact match

**Rationale**: LLMs often produce enum values with different casing or punctuation than the schema defines. (Base spec rationale — this delta completes the implementation.)

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

### Requirement: Union variant selection

The system SHALL select the best union variant by trying each variant's `tryCast` (short-circuit on score 0), then full `coerce` on all variants, picking the lowest score. This requirement is COMPLETED by this change — the union variant selection is implemented in `TypeCoercer.coerce`.

**Given** a `JsonishValue` and a target union type `A | B`
**When** the coercer processes the value
**Then** each variant is tried, and the variant with the lowest coercion score is selected

#### Scenario: Perfect match short-circuits

**Given** union `Int | String` and input `Num(42, Complete)`
**When** the coercer tries `Int` first
**Then** `tryCast` succeeds with score 0 and `String` is not tried

#### Scenario: No perfect match — lowest score wins

**Given** union `Int | String` and input `Str("42", Complete)`
**When** the coercer tries both variants
**Then** `Int` (score from StringToInt) is compared against `String` (score 0), and `String` wins

## Properties (Ring 3)

### Property: Apostrophe round-trip — balanced apostrophes preserved

**Invariant**: For any JSON string value containing balanced apostrophes (e.g., `"it's fine, isn't it"`), `JsonishParser.parse` preserves the apostrophes unchanged — no regex corruption.

**Generator strategy**: `genStringWithApostrophes` (constructive — `Gen.string(Gen.frequency1(Gen.constant("'"), Gen.char))` filtered to strings containing at least 2 apostrophes; embed in a JSON object `{"note": "<string>"}`; classify by apostrophe-count bucket: 2-3, 4-10, 10+). This is the regression test for the live `fixQuotes` bug.

```
forAll { (s: String) =>
  val json = s"""{"note": "$s"}"""
  val parsed = JsonishParser.parse(json)
  parsed == JsonishValue.Obj(Map("note" -> JsonishValue.Str(s, Complete)), Complete)
}
```

### Property: Score ordering — lower score is always preferred

**Invariant**: For any two successful coercions `a` and `b` of the same `JsonishValue`, if `a.score < b.score`, then `a` is selected.

**Generator strategy**: `genAnyOf` (constructive — `Gen` of `JsonishValue.AnyOf` containing two known-coercible values, e.g., `Str("42", Complete)` and `Num(42, Complete)`; classify by score-difference: 0, small, large).

```
forAll { (v: JsonishValue.AnyOf, target: Schema[Int]) =>
  val results = v.choices.map(c => TypeCoercer.coerce(Some(c), ParsingContext.empty, target))
  val successful = results.collect { case Right(r) => r }
  if successful.size > 1 then successful.minBy(_.score) == selectedResult
}
```

### Property: Coercion preserves semantic value

**Invariant**: For any string `s` representing a valid integer `n`, coercing `JsonishValue.Str(s, Complete)` to `Int` yields `n`.

**Generator strategy**: `Gen.int(Range.linear(-10000, 10000))` mapped to string, classify by negative/zero/positive.

```
forAll { (n: Int) =>
  val strVal = JsonishValue.Str(n.toString, Complete)
  TypeCoercer.coerce(Some(strVal), ParsingContext.empty, Schema[Int]) == Right(BamlValueWithFlags(n, Vector(StringToInt), _))
}
```

### Property: Existing parsing tests pass unchanged

**Invariant**: For all 21 existing `SchemaSamplesParsingSuite` test inputs, the new `JsonishParser` → `TypeCoercer` decode path produces equivalent results to the pre-change `SchemaAlignedParser`.

**Generator strategy**: Fixed fixture set (21 existing test samples), no random generation.

```
forAll { (sample: SchemaSample) =>
  parseWithNewSAP(sample.input, sample.schema) == parseWithOldSAP(sample.input, sample.schema)
}
```

### Property: JsonishParser is total on all strings (no crash)

**Invariant**: For any string (valid JSON, invalid JSON, empty, non-UTF8-replaced, etc.), `JsonishParser.parse` returns a `JsonishValue` (possibly with `CompletionState.Incomplete`) or raises a typed `ParsingError` — it SHALL NOT crash with an uncaught exception or infinite-loop.

**Generator strategy**: `Gen.string(Gen.char, Range.linear(0, 200))` — arbitrary strings including non-JSON; classify by length bucket and by starts-with-`{` vs starts-with-`[` vs other.

```
forAll { (s: String) =>
  Try(JsonishParser.parse(s)) match {
    case Success(jv: JsonishValue) => true
    case Failure(e: ParsingError) => true
    case Failure(other) => false // uncaught exception — bug
  }
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `JsonFixMiddleware` import or reference | The class is deleted; repair is via `JsonishParser` | `assertDoesNotCompile("import org.adk4s.core.tools.JsonFixMiddleware")` |
| `SchemaAlignedParser.fixQuotes` call | The regex-cleaning method is removed; the tolerant parser replaces it | `assertDoesNotCompile("SchemaAlignedParser.fixQuotes(...)")` |
| `TypeCoercer.parseAndCoerce` returning `(response, Vector.empty)` | The stub is replaced with a real implementation | `assertDoesNotCompile("TypeCoercer.parseAndCoerce = (r, Vector.empty)")` (stub pattern) |

## Formal Contracts (Ring 6)

The `TypeCoercer` candidate-selection fold ("given multiple successful coercions, the one with the lowest `CoercionScore` wins") and the enum-fuzzy-matching escalation (exact → punctuation-stripped → case-insensitive → both, each a monotonically increasing penalty) are pure kernels expressible in PureScala independent of `smithy4s.Schema`/`Mirror`/`IO`. They follow the VERIFIED-MIRROR pattern already used by `adk4s-optimize`'s `PredictorKernel`.

### Contract: Candidate selection fold

**Precondition** (`require`): `candidates.nonEmpty` — at least one coercion result exists.
**Postcondition** (`ensuring`): the result is the `BamlValueWithFlags` with the minimum `score` among all `Right` results; if no `Right` results, the result is a `Left` aggregating all errors.

```scala
def selectBest[A](candidates: Vector[Either[ParsingError, BamlValueWithFlags[A]]]): Either[ParsingError, BamlValueWithFlags[A]] = {
  require(candidates.nonEmpty)
  val successful = candidates.collect { case Right(r) => r }
  if successful.isEmpty then Left(aggregateErrors(candidates.collect { case Left(e) => e }))
  else Right(successful.minBy(_.score))
}.ensuring(result => result match {
  case Right(r) => successful.nonEmpty && r.score == successful.map(_.score).min
  case Left(_) => successful.isEmpty
})
```

### Contract: Enum fuzzy-matching escalation is monotonic

**Precondition** (`require`): `enumValues.nonEmpty` and `input.nonEmpty`.
**Postcondition** (`ensuring`): the penalty is monotonically increasing across strategies: `exactPenalty < strippedPenalty < caseInsensitivePenalty < bothPenalty` (each strategy's penalty is strictly greater than the previous, or the strategy fails).

```scala
def enumMatchPenalty(input: String, enumValues: Vector[String]): Option[(String, CoercionScore)] = {
  require(enumValues.nonEmpty && input.nonEmpty)
  // exact → punctuation-stripped → case-insensitive → both
  // each step's penalty > previous step's penalty
}.ensuring(result => result.forall { case (_, score) => score >= CoercionScore.zero })
```

**Bridge property test**: `TypeCoercerSelectionBridgeSpec` runs shipped `TypeCoercer.selectBest` and the PureScala `selectBest` model on the same generated `Vector[Either[ParsingError, BamlValueWithFlags[Int]]]` inputs and asserts equal results. Lives in `adk4s-orchestration/test` or `structured-llm/test` (the owning module of `TypeCoercer`), with the owning module taking `verified % Test` as a dependency (precondition for the bridge test, per the capability profile).

**Delegated to Ring 3**: the `forall/exists` VC that "for all `AnyOf` with ≥2 successful coercions, the selected result has the minimum score" diverges in the solver for large candidate sets — covered by the Hedgehog property `Score ordering — lower score is always preferred` above.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| JsonishParser preserves apostrophes | Requirement: JsonishParser is a real tolerant string-to-JsonishValue parser + Scenario: Valid JSON with apostrophes in string values + Property: Apostrophe round-trip | Hedgehog property (regression test for fixQuotes bug) | JsonishParserSpec |
| JsonishParser handles markdown fences | Requirement: JsonishParser is a real tolerant string-to-JsonishValue parser + Scenario: Markdown-wrapped JSON | scenario test | JsonishParserSpec |
| JsonishParser handles truncation | Requirement: JsonishParser is a real tolerant string-to-JsonishValue parser + Scenario: Truncated JSON | scenario test | JsonishParserSpec |
| JsonishParser produces AnyOf for ambiguous values | Requirement: JsonishParser is a real tolerant string-to-JsonishValue parser + Scenario: Numeric string produces AnyOf | scenario test | JsonishParserSpec |
| JsonishParser strips comments | Requirement: JsonishParser is a real tolerant string-to-JsonishValue parser + Scenario: Comment stripping | scenario test | JsonishParserSpec |
| JsonishParser recovers unclosed braces | Requirement: JsonishParser is a real tolerant string-to-JsonishValue parser + Scenario: Unclosed brace recovery | scenario test | JsonishParserSpec |
| JsonishParser is total (no crash) | Property: JsonishParser is total on all strings | Hedgehog property | JsonishParserSpec |
| TypeCoercer wired into SAP | Requirement: TypeCoercer.coerce is wired into SchemaAlignedParser + Scenario: String to Int coercion via the wired path | scenario test + adversarial review (audit SAP.parse decode path) | SchemaAlignedParserSpec |
| TypeCoercer coercion failure produces error | Requirement: Schema-driven type coercion + Scenario: Coercion failure | scenario test | TypeCoercerSpec |
| Backward compatibility — 21 existing tests pass | Requirement: TypeCoercer.coerce is wired into SchemaAlignedParser + Scenario: Backward compatibility + Property: Existing parsing tests pass unchanged | existing test suite (regression gate) | SchemaSamplesParsingSuite |
| JsonFixMiddleware absorbed and deleted | Requirement: JsonFixMiddleware absorbed into JsonishParser and deleted + Scenario: JsonFixMiddleware is deleted | compile-negative test + adversarial review (grep for JsonFixMiddleware) | compile-negative + adversarial review |
| ToolsNode uses JsonishParser for repair | Requirement: JsonFixMiddleware absorbed into JsonishParser and deleted + Scenario: Tool argument repair via JsonishParser | scenario test | ToolsNodeSpec |
| BamlValueWithFlags implemented | Requirement: BamlValueWithFlags and ParsingContext implemented + Scenario: BamlValueWithFlags carries flags and score | type system (case class) + scenario test | TypeCoercerSpec |
| ParsingContext tracks depth | Requirement: BamlValueWithFlags and ParsingContext implemented + Scenario: ParsingContext tracks depth | scenario test | TypeCoercerSpec |
| Score ordering — lowest wins | Requirement: Scoring system for parse candidate selection + Property: Score ordering — lower score is always preferred | Hedgehog property | ScoringSpec |
| Coercion preserves semantic value | Requirement: Schema-driven type coercion + Property: Coercion preserves semantic value | Hedgehog property | TypeCoercerSpec |
| Candidate selection fold is correct | Requirement: Scoring system for parse candidate selection + Compile-Negative: Candidate selection fold contract | Stainless verification (VERIFIED-MIRROR) + bridge property test | TypeCoercerSelectionBridgeSpec |
| Enum escalation is monotonic | Requirement: Enum fuzzy matching with escalating strategies + Compile-Negative: Enum escalation monotonicity contract | Stainless verification (VERIFIED-MIRROR) + bridge property test | EnumMatchingBridgeSpec |
| Enum fuzzy matching strategies escalate | Requirement: Enum fuzzy matching with escalating strategies + Scenario: Exact match — score 0 | scenario test | EnumMatchingSpec |
| Union variant selection — tryCast short-circuit | Requirement: Union variant selection + Scenario: Perfect match short-circuits | scenario test | UnionCoercionSpec |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `JsonishParser` | object | `structured-llm/src/main/scala/org/adk4s/structured/sap/JsonishParser.scala` | `parse: String => JsonishValue` — tolerant scanner, quote-state-tracking (absorbed from `JsonFixMiddleware`) |
| `TypeCoercer.coerce` | typeclass method | `structured-llm/src/main/scala/org/adk4s/structured/sap/TypeCoercer.scala` | completes the stubbed `parseAndCoerce`; `(JsonishValue, ParsingContext, Schema[A]) => Either[ParsingError, BamlValueWithFlags[A]]` |
| `BamlValueWithFlags[A]` | case class | `structured-llm/src/main/scala/org/adk4s/structured/sap/BamlValueWithFlags.scala` | `(value: A, flags: Vector[CoercionFlag], score: CoercionScore)` |
| `ParsingContext` | case class | `structured-llm/src/main/scala/org/adk4s/structured/sap/ParsingContext.scala` | path/config/depth context |
| `SchemaAlignedParser.parse` | method (modified) | `structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala` | decode path: `JsonishParser.parse` → `TypeCoercer.coerce`; remove `fixQuotes` regex pipeline |
| `JsonFixMiddleware` | DELETED | `adk4s-core/src/main/scala/org/adk4s/core/tools/JsonFixMiddleware.scala` | removed; `ToolsNode` calls `JsonishParser` for repair |
| `ToolsNode` argument repair | modified | `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala` | call `JsonishParser.parse` instead of `JsonFixMiddleware` |
| Ring 6 mirror | PureScala model | `verified/src/main/scala/org/adk4s/verified/TypeCoercerKernel.scala` | `selectBest` fold + `enumMatchPenalty` escalation; bridge test in owning module |
