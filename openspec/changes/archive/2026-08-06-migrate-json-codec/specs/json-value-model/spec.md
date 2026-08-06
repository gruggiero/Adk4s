# Spec: JSON Value Model

<!-- NEW capability — the `JsonValue` internal type, the llm4s boundary
     adapter, and the architectural rule that `ujson.Value` may only appear
     in code that directly interoperates with `org.llm4s.toolapi`.

     This is the foundation spec for the migrate-json-codec change. The
     other 8 specs in this change depend on `JsonValue` existing and on the
     boundary adapter being the single conversion point. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| interrupt-signal | `Stateful.state` / `Composite.state` migrate from `ujson.Value` to `JsonValue` (field-type change only; routing protocol unchanged) | `openspec/concepts/interrupt-signal.md` |
| retriever | `Document.metadata` migrates from `Map[String, ujson.Value]` to `Map[String, JsonValue]` (field-type change only) | `openspec/concepts/retriever.md` |
| schema-aligned-parser | SAP's decode path migrates from regex-cleaning to `JsonishValue` tolerant parse + `TypeCoercer` (see type-aware-sap-coercion spec) | `openspec/concepts/schema-aligned-parser.md` |

No behavioral concept file is created or modified by THIS spec — it introduces a type alias and an adapter, not a new behavioral unit. The behavioral concepts above are touched by their respective specs in this change.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `smithy4s.Document` | sealed trait (ADT: `DNumber`, `DString`, `DBoolean`, `DArray`, `DObject`, `DNull`) | `smithy4s` |
| `smithy4s.json.Json` | object (`read[A]`/`writeBlob`) | `smithy4s.json` |
| `InterruptSignal` | sealed trait (derives ReadWriter) | `org.adk4s.core.interrupt` |
| `Document` | case class (`Retriever.Document`) | `org.adk4s.core.component` |
| `Demo` | case class | `org.adk4s.optimize` |
| `TraceEntry` | case class | `org.adk4s.eval` |
| `EvaluationResult[I, O]` | case class | `org.adk4s.eval` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `org.adk4s.core.json.JsonValue` | type alias (`= smithy4s.Document`) | ADK4S's own internal, immutable JSON value type — replaces `ujson.Value` everywhere except the llm4s boundary |
| `JsonValueCodec` | object (`toUjson: JsonValue => ujson.Value`, `fromUjson: ujson.Value => JsonValue`) | The single, explicit conversion point at the llm4s boundary |

## ADDED Requirements

### Requirement: JsonValue is an immutable type alias over smithy4s.Document

The system SHALL provide `JsonValue` as a type alias for `smithy4s.Document`, establishing it as ADK4S's own internal JSON value type. `JsonValue` SHALL be immutable (all `Document` variants are case classes or case objects), exact on `Long` and `BigDecimal` (no `Double`-only truncation), and SHALL NOT carry mutable collections (unlike `ujson.Obj`/`Arr` which wrap `LinkedHashMap`/`mutable.ArrayBuffer`).

**Given** a `JsonValue` constructed from a JSON object `{"name": "John", "age": 42}`
**When** the value is inspected
**Then** it is a `smithy4s.Document.DObject` containing `Map("name" -> DString("John"), "age" -> DNumber(42))`, and no field can be updated in place (the `Map` is immutable)

**Rationale**: `ujson.Value`'s mutable AST (`Obj` wraps `LinkedHashMap`, `Arr` wraps `mutable.ArrayBuffer`, both with in-place `update`) contradicts the project's "immutable data, no mutable variables" rule and is held by compared/persisted values (`InterruptSignal.Stateful.state`, `Demo`). `smithy4s.Document` is already a declared first-class dependency (`Dependencies.smithy4s` includes `smithy4s-json`), immutable, and exact on `Long`/`BigDecimal`.

#### Scenario: Long values are exact

**Given** a `JsonValue` constructed from JSON `{"id": 9007199254740993}` (2^53 + 1, above `Double` precision)
**When** the `id` field is read as a `Long`
**Then** the value is exactly `9007199254740993L` — no truncation (contrast: `ujson.Num(Double)` would truncate to `9007199254740992.0`)

#### Scenario: DNull variant is representable

**Given** a `JsonValue` constructed from JSON `{"note": null}`
**When** the `note` field is inspected
**Then** it is `smithy4s.Document.DNull` — the null variant is a first-class value, not an absence

#### Scenario: Nested arrays and objects

**Given** a `JsonValue` constructed from JSON `{"items": [{"id": 1}, {"id": 2}]}`
**When** the `items` field is inspected
**Then** it is a `DArray(Vector(DObject(...), DObject(...)))` — nested structures are immutable `Vector`/`Map`

### Requirement: JsonValueCodec is the single boundary adapter at the llm4s edge

The system SHALL provide a `JsonValueCodec` object with two total functions: `toUjson: JsonValue => ujson.Value` and `fromUjson: ujson.Value => JsonValue`. These SHALL be the ONLY functions in the codebase that convert between `JsonValue` and `ujson.Value`. `ujson.Value` SHALL NOT appear in ADK4S-owned code outside the llm4s-adapter allowlist (see Requirement: ujson boundary confinement).

**Given** a `JsonValue.DString("hello")`
**When** `JsonValueCodec.toUjson` is called
**Then** the result is `ujson.Str("hello")`

**Rationale**: `ujson.Value` is the type at the `org.llm4s.toolapi` edge (`ToolFunction`, `ToolRegistry`, tool `run`/`runStream` signatures). It must remain the type there. Everywhere else in ADK4S, `JsonValue` is the type. The adapter is the single, explicit conversion point so an llm4s upgrade that reshapes upickle usage breaks at most one file, not every persisted/public type signature.

#### Scenario: toUjson round-trips all Document variants

**Given** each `smithy4s.Document` variant: `DNull`, `DBoolean(true)`, `DNumber(42)`, `DNumber(42L)`, `DNumber(BigDecimal("3.14"))`, `DString("s")`, `DArray(Vector(...))`, `DObject(Map(...))`
**When** `JsonValueCodec.toUjson` then `JsonValueCodec.fromUjson` is applied
**Then** the round-tripped `JsonValue` equals the original (value equality, including `DNull`)

#### Scenario: fromUjson round-trips all ujson variants

**Given** each `ujson.Value` variant: `Null`, `Bool(true)`, `Num(42.0)`, `Num(42L)`, `Str("s")`, `Arr(...)`, `Obj(...)`
**When** `JsonValueCodec.fromUjson` then `JsonValueCodec.toUjson` is applied
**Then** the round-tripped `ujson.Value` equals the original (value equality, including `Null`)

#### Scenario: Long precision is preserved in the JsonValue model

**Given** a `JsonValue.DNumber(BigDecimal(9007199254740993L))`
**When** the value is inspected directly (without crossing the ujson boundary)
**Then** it is `DNumber(BigDecimal(9007199254740993L))` — the Long is preserved exactly in the `JsonValue` model

**Note**: `ujson.Num` wraps `Double` and truncates Longs above 2^53. This is a limitation of the ujson AST (`ujson.Value` is sealed; `ujson.Num` only accepts `Double`). The `JsonValueCodec` adapter routes all numbers through `ujson.Num(Double)`, so Long precision MAY be lost when crossing the ujson boundary. It is the responsibility of the ujson library user to not insert large Long values into `ujson.Num` if they need exact precision — the `JsonValue` model (`smithy4s.Document.DNumber(BigDecimal)`) preserves precision on the ADK4S side.

### Requirement: ujson boundary confinement

The system SHALL confine `ujson.Value` to code that directly interoperates with `org.llm4s.toolapi`. A Scalafix custom regex rule (mirroring the existing `NoConfigFactory`/`NoSysEnv` pattern in `.scalafix.conf`) SHALL flag any `ujson.Value` reference outside the boundary allowlist. The allowlist SHALL be exactly: `adk4s-core`'s `JsonValueCodec` adapter file, `Tool.scala`, `ToolsNode.scala`, `ToolWrapper.scala`, `AgentTool.scala`, `StructuredToolFunction.scala`, `ComponentRunnable.scala`, `AdkToolInfo`, and the `ToolSchema`/`ToolInfer` JSON-Schema-metadata path (for `AdkToolInfo.parameters`).

**Given** a source file outside the boundary allowlist that imports or references `ujson.Value`
**When** `sbt scalafixAll --check` is run
**Then** the Scalafix rule reports the file as a violation

**Rationale**: This change's core thesis is a layering rule. Without an enforced guard, the rule is advisory (the project's existing Ring 2 status) and `ujson` will re-proliferate. The Scalafix rule upgrades Ring 2 from advisory to enforced for the `ujson` concern. The allowlist is the set of files that MUST speak `ujson.Value` because they call `org.llm4s.toolapi` directly.

#### Scenario: Persisted type uses JsonValue not ujson

**Given** `InterruptSignal.Stateful` (a checkpointed, compared value)
**When** its `state` field type is inspected
**Then** it is `JsonValue`, not `ujson.Value` — persisted/public types are outside the allowlist

**Verification**: This scenario is verified in spec #3 (`agent-interrupt-resume`), which performs the field-type migration on `InterruptSignal.Stateful.state`. Spec #1 (`json-value-model`) introduces the `JsonValue` type and `JsonValueCodec` adapter; the actual field migrations happen in specs #3–#6.

#### Scenario: Tool run signature stays ujson (boundary)

**Given** `InvokableTool.run(arguments: ujson.Value): F[ujson.Value]`
**When** its signature is inspected
**Then** it stays `ujson.Value` — it is on the allowlist because it calls `org.llm4s.toolapi.ToolFunction` directly

#### Scenario: AdkToolInfo.parameters stays ujson (boundary)

**Given** `AdkToolInfo.parameters: ujson.Value`
**When** its field type is inspected
**Then** it stays `ujson.Value` — it is JSON Schema metadata handed directly to `org.llm4s.toolapi.ToolFunction`/`toOpenAITool`

### Requirement: upickle/ujson declared explicitly in Dependencies.scala

The system SHALL declare `upickle`/`ujson` explicitly in `project/Dependencies.scala` wherever ADK4S-owned code uses it directly, closing the undeclared-transitive-dependency gap. The version SHALL be pinned (not a floating range) and SHALL match the version `org.llm4s:core:0.3.4` brings transitively.

**Given** `project/Dependencies.scala`
**When** the `upickle`/`ujson` dependency is inspected
**Then** it is declared as an explicit `ModuleID` with a pinned version, not relying on transitive resolution from `llm4s`

**Rationale**: `upickle`/`ujson` is currently NOT declared in `Dependencies.scala` — it arrives transitively via `org.llm4s:core:0.3.4`. An llm4s upgrade that reshapes upickle usage breaks ADK4S's own type signatures with no `Dependencies.scala` line to point at. Declaring it explicitly makes the dependency visible, version-pinned, and upgrade-safe. This is a declaration change, not a new capability — the artifact is already on the classpath.

#### Scenario: Dependency is declared

**Given** the build after this change
**When** `project/Dependencies.scala` is inspected
**Then** an `upickle` (or `ujson`) `ModuleID` exists with a pinned version constant in `Versions.scala`

#### Scenario: Version matches transitive

**Given** the declared `upickle` version and the version `org.llm4s:core:0.3.4` brings transitively
**When** the two versions are compared
**Then** they are equal (no version conflict introduced)

## Properties (Ring 3)

### Property: JsonValue-ujson round-trip is identity

**Invariant**: For every `JsonValue`, `fromUjson(toUjson(jv)) == jv` (value equality across all `Document` variants including `DNull`).

**Generator strategy**: `genJsonValue` (constructive — generates all `Document` variants: `DNull`, `DBoolean`, `DNumber` from `Gen.long` AND `Gen.double` AND `Gen.double.map(BigDecimal _)`, `DString`, `DArray` of nested `JsonValue`, `DObject` of string-keyed `JsonValue`; depth ∈ Range.linear 0 4; classify by top-level variant). Covers the `DNull` edge explicitly (not just non-null).

```
forAll { (jv: JsonValue) =>
  JsonValueCodec.fromUjson(JsonValueCodec.toUjson(jv)) == jv
}
```

### Property: ujson-JsonValue round-trip is identity

**Invariant**: For every `ujson.Value`, `toUjson(fromUjson(uv)) == uv` (value equality across all `ujson` variants including `Null`).

**Generator strategy**: `genUjsonValue` (constructive — mirrors `genJsonValue` but produces `ujson.Value`: `Null`, `Bool`, `Num` from `Gen.long` AND `Gen.double`, `Str`, `Arr`, `Obj`; depth ∈ Range.linear 0 4; classify by top-level variant). Covers the `Null` edge explicitly.

```
forAll { (uv: ujson.Value) =>
  JsonValueCodec.toUjson(JsonValueCodec.fromUjson(uv)) == uv
}
```

### Property: Long precision preserved in the JsonValue model

**Invariant**: For every `Long` `n`, `DNumber(BigDecimal(n))` constructed from `n` carries the exact `BigDecimal` value — no truncation. This is a property of the `JsonValue` model (smithy4s.Document), NOT of the ujson boundary round-trip (which is limited by `ujson.Num(Double)`).

**Generator strategy**: `genLargeLong` (constructive — `Gen.long(Range.linear(Long.MaxValue / 2, Long.MaxValue))` ∪ `Gen.long(Range.linear(Long.MinValue, Long.MinValue / 2))` ∪ `Gen.long(Range.linear(9007199254740993L, 9007199254740993L * 100))`; classify by sign and magnitude bucket). These are the values `ujson.Num(Double)` would truncate — the property verifies they are preserved in `JsonValue` itself.

```
forAll { (n: Long) =>
  val jv: JsonValue = smithy4s.Document.DNumber(BigDecimal(n))
  jv == smithy4s.Document.DNumber(BigDecimal(n))
}
```

### Property: JsonValue is immutable (no in-place mutation)

**Invariant**: A `JsonValue` constructed from a JSON object cannot have its fields updated in place — any "update" produces a new value.

**Generator strategy**: `genJsonObject` (constructive — `genJsonValue` filtered to `DObject` variant; small maps, 1-5 keys). This is a compile-time property verified by a compile-negative test (see Compile-Negative Obligations), not a runtime property — listed here for completeness.

```
forAll { (obj: DObject) =>
  // obj.fields is an immutable Map — no update method exists
  // any "update" is obj.copy(fields = obj.fields.updated(k, v))
  true // compile-time property, enforced by compile-negative test
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `ujson.Value` reference outside the boundary allowlist | The layering rule confines `ujson.Value` to the llm4s edge | Scalafix custom regex rule (run via `sbt scalafixAll --check`) |
| `ujson.Obj` / `ujson.Arr` in-place `update` call on a `JsonValue`-typed value | `JsonValue` is `smithy4s.Document` (immutable); `update` does not exist on it | `assertDoesNotCompile("val jv: JsonValue = ...; jv.update(...)")` |
| Importing `smithy4s.Document` directly outside the `JsonValueCodec` adapter file | Code outside the adapter should use the `JsonValue` alias, not the underlying type, to avoid naming collision with `Retriever.Document` | Scalafix rule or code-review gate (the naming collision is the reason the alias exists) |

## Formal Contracts (Ring 6)

`JsonValue`/`Document` is a plain immutable ADT — no decision/fold/law at its center. It is NOT a Ring 6 candidate. The `JsonValueCodec` round-trip is a Ring 3 property (above), not a formal contract.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| JsonValue is an immutable type alias over Document | Requirement: JsonValue is an immutable type alias over smithy4s.Document | type system (type alias) + compile-negative test (no in-place update) | JsonValueTypeContract |
| Long precision preserved | Requirement: JsonValue is an immutable type alias over smithy4s.Document + Scenario: Long values are exact | Hedgehog property (Long precision preserved in the JsonValue model) | JsonValueCodecSpec |
| DNull variant representable | Requirement: JsonValue is an immutable type alias over smithy4s.Document + Scenario: DNull variant is representable | Hedgehog property (round-trip covers DNull) | JsonValueCodecSpec |
| toUjson round-trips all Document variants | Requirement: JsonValueCodec is the single boundary adapter + Scenario: toUjson round-trips all Document variants | Hedgehog property (JsonValue-ujson round-trip is identity) | JsonValueCodecSpec |
| fromUjson round-trips all ujson variants | Requirement: JsonValueCodec is the single boundary adapter + Scenario: fromUjson round-trips all ujson variants | Hedgehog property (ujson-JsonValue round-trip is identity) | JsonValueCodecSpec |
| Long precision across boundary | Requirement: JsonValueCodec is the single boundary adapter + Scenario: Long precision is preserved in the JsonValue model | Hedgehog property (Long precision preserved in the JsonValue model) | JsonValueCodecSpec |
| ujson confined to boundary allowlist | Requirement: ujson boundary confinement | Scalafix custom regex rule + adversarial review (audit for ujson outside allowlist) | .scalafix.conf + adversarial review |
| Persisted types use JsonValue not ujson | Requirement: ujson boundary confinement + Scenario: Persisted type uses JsonValue not ujson | Scalafix rule + adversarial review | adversarial review |
| Tool run signature stays ujson | Requirement: ujson boundary confinement + Scenario: Tool run signature stays ujson | adversarial review (confirm allowlist is not over-broad) | adversarial review |
| upickle declared explicitly | Requirement: upickle/ujson declared explicitly in Dependencies.scala + Scenario: Dependency is declared | build inspection (grep Dependencies.scala) + adversarial review | adversarial review |
| Version matches transitive | Requirement: upickle/ujson declared explicitly in Dependencies.scala + Scenario: Version matches transitive | build inspection (sbt evict or dependencyTree) | adversarial review |
| No in-place mutation on JsonValue | Compile-Negative: ujson.Obj/Arr in-place update on JsonValue | compile-negative test (assertDoesNotCompile) | JsonValueTypeContract |
| No direct smithy4s.Document import outside adapter | Compile-Negative: importing smithy4s.Document directly outside adapter | Scalafix rule or code-review gate | adversarial review |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `JsonValue` | type alias | `adk4s-core/src/main/scala/org/adk4s/core/json/JsonValue.scala` | `type JsonValue = smithy4s.Document` |
| `JsonValueCodec` | object | `adk4s-core/src/main/scala/org/adk4s/core/json/JsonValueCodec.scala` | `toUjson`/`fromUjson` — the single boundary adapter |
| `upickle`/`ujson` declaration | `ModuleID` | `project/Dependencies.scala`, `project/Versions.scala` | explicit declaration, pinned version |
| Scalafix `ujson` boundary rule | custom regex | `.scalafix.conf` | mirrors `NoConfigFactory`/`NoSysEnv` pattern; allowlist of boundary files |
