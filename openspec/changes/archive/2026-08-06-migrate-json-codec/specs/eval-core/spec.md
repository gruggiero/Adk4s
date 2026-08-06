# Spec: Eval Core (Delta)

<!-- DELTA spec for the migrate-json-codec change. MODIFIES:
     - `TraceEntry.input`/`output` field types from `ujson.Value` to `JsonValue`
     - `EvaluationResult.toJson`/`fromJson` to use `smithy4s.json.Json` instead
       of upickle for the `JsonValue`-typed fields, fixing unnecessary string
       round-trips and the misleading "malformed JSON" error
     - `Dataset.fromJsonl` to use `smithy4s.json.Json.read` for `JsonValue` fields
     The eval harness parallelism, failure-score substitution, maxErrors,
     metric contract, CSV export, and all other requirements in the base spec
     are UNCHANGED. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| eval-harness | `TraceEntry` field types migrate; `EvaluationResult` JSON export/import fixes; harness behavior unchanged | `openspec/concepts/eval-harness.md` |
| metric-contract | Unchanged — metric signature has no `JsonValue`/`ujson` types | `openspec/concepts/metric-contract.md` |

No change to the `eval-harness.md` or `metric-contract.md` concept files' actions, state, or synchronizations — only the `TraceEntry` field types and the `EvaluationResult` export/import implementation change. The `eval-harness.md` Implementation map row for `TraceEntry` is updated at apply Step 12.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `TraceEntry` | case class | `org.adk4s.eval` |
| `EvaluationResult[I, O]` | case class | `org.adk4s.eval` |
| `Dataset` | object (factory) | `org.adk4s.eval` |
| `smithy4s.json.Json` | object (`read[A]`/`writeBlob`) | `smithy4s.json` |
| `smithy4s.Document` | sealed trait (ADT) | `smithy4s` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `JsonValue` | type alias (`= smithy4s.Document`) | Introduced by the json-value-model spec; referenced here as the new `TraceEntry` field type |

## MODIFIED Requirements

### Requirement: TraceEntry field types migrate to JsonValue

The `TraceEntry` case class SHALL carry `input: JsonValue` and `output: JsonValue` (immutable `smithy4s.Document`), NOT `ujson.Value`. The `path: String` field is unchanged. The `Trace.forPredictor` prefix filter is unchanged.

**Given** a `TraceEntry(path = "program.predictor_0", input = DObject(Map("q" -> DString("hello"))), output = DObject(Map("a" -> DString("world"))))`
**When** the entry's fields are inspected
**Then** `input` and `output` are `JsonValue` (immutable `smithy4s.Document`), and the entry is serializable via `smithy4s.json.Json`

**Rationale**: `TraceEntry` is a public type in `org.adk4s.eval` whose `ujson.Value` fields introduce an undeclared transitive dependency (upickle arrives via llm4s, but `adk4s-eval` MUST NOT depend on the llm4s LLM client per the Ring 2 purity rule in `build.sbt`). Migrating to `JsonValue` closes this gap and makes `adk4s-eval` genuinely `ujson`-free in its own types.

#### Scenario: TraceEntry with JsonValue fields

**Given** a predictor invocation with input `{"q": "hello"}` and output `{"a": "world"}`
**When** a `TraceEntry` is constructed
**Then** `input` is `DObject(Map("q" -> DString("hello")))` and `output` is `DObject(Map("a" -> DString("world")))` — both `JsonValue`

#### Scenario: TraceEntry is immutable

**Given** a `TraceEntry` with `input: JsonValue`
**When** code attempts to mutate `input` in place
**Then** compilation fails — `JsonValue` has no in-place `update` method

### Requirement: JSON export round-trip

The JSON export SHALL include a `formatVersion: 1` field and a provided from-JSON reader SHALL re-read what the export wrote. The export/import SHALL use `smithy4s.json.Json` for `JsonValue`-typed fields (`TraceEntry.input`/`output`), eliminating the unnecessary string round-trips (`writeJs(...).render()` on an already-built tree; `read[I](tree.render())` instead of `Value.transform`) present in the pre-change code. The harness itself SHALL be codec-free — `Writer[I]`/`Writer[O]` are required on export only, not on the `Evaluate` call.

**Given** an `EvaluationResult[I, O]` with `Writer[I]` and `Writer[O]` in scope
**When** `result.toJson` is called and then `EvaluationResult.fromJson` reads the JSON
**Then** the round-tripped result equals the original (value equality on score, rows, outcomes, and feedback), and the `TraceEntry.input`/`output` fields are `JsonValue` throughout

**Rationale**: The pre-change code builds a `ujson.Value` tree for `TraceEntry` fields, then calls `writeJs(...).render()` to serialize it — an unnecessary string round-trip on an already-built tree. It also calls `read[I](tree.render())` instead of `Value.transform` for the reverse direction. These are wasteful and obscure the actual codec boundary. Using `smithy4s.json.Json` directly eliminates the round-trips and makes the codec boundary explicit.

#### Scenario: Round-trip with feedback and failures

**Given** a result with 3 succeeded rows and 1 failed row, some with feedback, and `TraceEntry` fields as `JsonValue`
**When** `toJson` then `fromJson` round-trips
**Then** the re-read result has the same score, the same number of rows, the same outcomes (Succeeded/Failed), the same feedback strings, and the `TraceEntry.input`/`output` fields are `JsonValue` (not `ujson.Value`)

#### Scenario: formatVersion present

**Given** any `EvaluationResult`
**When** `toJson` is called
**Then** the JSON object has a `"formatVersion": 1` field at the top level (unchanged from base spec)

#### Scenario: No unnecessary string round-trip

**Given** a `TraceEntry` with `input: JsonValue`
**When** `toJson` serializes it
**Then** the serialization uses `smithy4s.json.Json` directly on the `JsonValue` — no intermediate `ujson.Value` tree is built and no `.render()` call occurs on an already-built tree

### Requirement: Dataset.fromJsonl error reporting

The dataset reader SHALL read a JSONL file (one JSON object per line) into a `Vector[Example[I, O]]` using caller-supplied readers for `I` and `O`. For `JsonValue`-typed fields, the reader SHALL use `smithy4s.json.Json.read`. Malformed lines SHALL raise a descriptive error naming the line number AND the actual cause (schema mismatch vs. JSON syntax error), NOT a generic "malformed JSON" message.

**Given** a JSONL file with 20 valid lines and 1 line at position 15 that is valid JSON but does not match the expected schema
**When** `Dataset.fromJsonl[I, O](path)` is called
**Then** the reader raises an error naming line 15 AND identifying the cause as a schema mismatch (not "malformed JSON")

**Rationale**: The pre-change code reports "malformed JSON" for any parse failure, including schema mismatches — misleading when the line is syntactically valid JSON that simply doesn't match the expected shape. Distinguishing the two causes is a debugging aid for CI dataset maintainers.

#### Scenario: Valid JSONL

**Given** a 10-line JSONL file where each line is `{"input": ..., "gold": ...}`
**When** `Dataset.fromJsonl[I, O](path)` is called with matching readers
**Then** the result is a `Vector` of 10 `Example` values

#### Scenario: Malformed JSON at position 15

**Given** a 20-line JSONL file where line 15 is not valid JSON
**When** `Dataset.fromJsonl[I, O](path)` is called
**Then** the reader raises an error whose message names line 15 as the malformed line and identifies the cause as a JSON syntax error

#### Scenario: Schema mismatch at position 15

**Given** a 20-line JSONL file where line 15 is valid JSON but missing a required field
**When** `Dataset.fromJsonl[I, O](path)` is called
**Then** the reader raises an error whose message names line 15 and identifies the cause as a schema mismatch (not "malformed JSON")

#### Scenario: Empty JSONL

**Given** an empty file
**When** `Dataset.fromJsonl` is called
**Then** the result is `Vector.empty` — no error

## Properties (Ring 3)

### Property: TraceEntry round-trips through JsonValue

**Invariant**: For every `TraceEntry` with `input: JsonValue` and `output: JsonValue`, serializing via `smithy4s.json.Json` and deserializing yields an equal `TraceEntry`.

**Generator strategy**: `genTraceEntry` (constructive — `genString` for path, `genJsonValue` for input and output; classify by input/output variant). `genJsonValue` from the json-value-model spec covers all `Document` variants.

```
forAll { (entry: TraceEntry) =>
  val json = smithy4s.json.Json.writeBlob(entry)
  val roundTripped = smithy4s.json.Json.read[TraceEntry](json)
  roundTripped == entry
}
```

### Property: EvaluationResult JSON round-trip with JsonValue fields

**Invariant**: `EvaluationResult.fromJson(result.toJson) == Right(result)` for all results with matching writers/readers in scope, where `TraceEntry.input`/`output` are `JsonValue`.

**Generator strategy**: `genEvaluationResult` (constructive — generates score, rows with mixed outcomes, feedback, and `TraceEntry` with `genJsonValue` fields; uses `String` for I/O; classify by outcome mix: all-succeeded, all-failed, mixed). Reuses the base spec's `genEvaluationResult` shape, adapted for `JsonValue` fields.

```
forAll { (result: EvaluationResult[String, String]) =>
  EvaluationResult.fromJson[String, String](result.toJson) == Right(result)
}
```

### Property: Wire-format compatibility with pre-change exports

**Invariant**: For every `EvaluationResult` that has a pre-change `ujson.Value`-based equivalent, the JSON text produced by the new `toJson` equals the JSON text the pre-change `toJson` produced for the equivalent (whitespace aside).

**Generator strategy**: `genEvaluationResult` (as above); the pre-change fixture is captured BEFORE any code change by running the current `toJson` on a representative set of results. The property compares new-`toJson` output against the captured fixtures.

```
forAll { (result: EvaluationResult[String, String]) =>
  val newJson = result.toJson
  val oldJson = preChangeFixture(result.equivalentUjsonResult)
  normalizeWhitespace(newJson) == normalizeWhitespace(oldJson)
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| TraceEntry.input is JsonValue | Requirement: TraceEntry field types migrate to JsonValue + Scenario: TraceEntry with JsonValue fields | type system (field type) + adversarial review | TraceEntryTypeContract |
| TraceEntry.output is JsonValue | Requirement: TraceEntry field types migrate to JsonValue + Scenario: TraceEntry with JsonValue fields | type system (field type) + adversarial review | TraceEntryTypeContract |
| TraceEntry is immutable | Requirement: TraceEntry field types migrate to JsonValue + Scenario: TraceEntry is immutable | compile-negative test | TraceEntryTypeContract |
| TraceEntry round-trips | Requirement: TraceEntry field types migrate to JsonValue + Property: TraceEntry round-trips through JsonValue | Hedgehog property | TraceEntrySpec |
| JSON export round-trips with JsonValue fields | Requirement: JSON export round-trip + Property: EvaluationResult JSON round-trip with JsonValue fields | Hedgehog property | EvaluateSpec |
| No unnecessary string round-trip | Requirement: JSON export round-trip + Scenario: No unnecessary string round-trip | adversarial review (audit for `.render()` on built trees) | adversarial review |
| Wire-format compatibility | Requirement: JSON export round-trip + Property: Wire-format compatibility with pre-change exports | Hedgehog property (fixture-based) | EvalWireCompatSpec |
| formatVersion present | Requirement: JSON export round-trip + Scenario: formatVersion present | scenario test (unchanged from base spec) | EvaluateSpec |
| Dataset.fromJsonl reports malformed JSON | Requirement: Dataset.fromJsonl error reporting + Scenario: Malformed JSON at position 15 | scenario test (line number + cause in error) | DatasetSpec |
| Dataset.fromJsonl reports schema mismatch | Requirement: Dataset.fromJsonl error reporting + Scenario: Schema mismatch at position 15 | scenario test (line number + cause distinction) | DatasetSpec |
| Dataset.fromJsonl empty file | Requirement: Dataset.fromJsonl error reporting + Scenario: Empty JSONL | scenario test | DatasetSpec |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `TraceEntry` | case class | `adk4s-eval/src/main/scala/org/adk4s/eval/TraceEntry.scala` | `input: ujson.Value, output: ujson.Value` → `input: JsonValue, output: JsonValue` |
| `EvaluationResult.toJson`/`fromJson` | methods | `adk4s-eval/src/main/scala/org/adk4s/eval/EvaluationResult.scala` | use `smithy4s.json.Json` for `JsonValue` fields; eliminate `writeJs(...).render()` round-trip |
| `Dataset.fromJsonl` | method | `adk4s-eval/src/main/scala/org/adk4s/eval/Dataset.scala` | use `smithy4s.json.Json.read` for `JsonValue` fields; distinguish JSON syntax error vs. schema mismatch in error message |
